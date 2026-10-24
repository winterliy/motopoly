package dev.motopoly.command

import dev.motopoly.MotopolyPlugin
import dev.motopoly.Settings
import dev.motopoly.msg
import dev.motopoly.selection.BlockPoint
import dev.motopoly.selection.Region
import dev.motopoly.selection.WandListener
import dev.motopoly.terrain.GenerateParams
import dev.motopoly.terrain.TerrainGenerator
import dev.motopoly.terrain.TerrainStats
import dev.motopoly.world.GenerateJob
import dev.motopoly.world.ReferenceWorlds
import dev.motopoly.world.SurfaceGrid
import dev.motopoly.world.SurfaceScanner
import dev.motopoly.world.Generation
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.CompletableFuture

private const val MAX_DISTANCE = 64
private const val MAX_STEP = 16
private const val MAX_ROUND = 100
private const val BLUR_PASSES = 2

class MotopolyCommand(private val plugin: MotopolyPlugin, private val wand: WandListener) : CommandExecutor, TabCompleter {
    private val busy = HashSet<UUID>()
    private val distanceKey = NamespacedKey(plugin, "distance")
    // Before 2.0 the band was called smooth; a saved value still counts.
    private val legacySmoothKey = NamespacedKey(plugin, "smooth")
    private val roundKey = NamespacedKey(plugin, "round")
    private val roughKey = NamespacedKey(plugin, "rough")
    private val stepKey = NamespacedKey(plugin, "step")

    private val subcommands = listOf("help", "wand", "pos1", "pos2", "info", "gen", "config", "undo", "reload")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.firstOrNull()?.lowercase() == "help") {
            help(sender, label)
            return true
        }
        if (args.firstOrNull()?.lowercase() == "reload") {
            plugin.loadSettings()
            sender.msg("<green>설정을 다시 불러왔어요. (되돌리기 기록은 초기화됨)")
            return true
        }
        if (sender !is Player) {
            sender.msg("<red>플레이어만 쓸 수 있어요.")
            return true
        }
        when (args.firstOrNull()?.lowercase()) {
            "wand" -> {
                sender.inventory.addItem(wand.createWand())
                sender.msg("<green>선택 도구를 지급했어요. 좌클릭/우클릭으로 두 지점을 찍으세요.")
            }
            "pos1" -> wand.setPoint(sender, 1, BlockPoint.of(sender.location))
            "pos2" -> wand.setPoint(sender, 2, BlockPoint.of(sender.location))
            "info" -> region(sender)?.let { info(sender, it) }
            "gen", "generate" -> gen(sender, label, args.getOrNull(1))
            "config" -> config(sender, label, args.getOrNull(1), args.getOrNull(2))
            "undo" -> undo(sender)
            else -> help(sender, label)
        }
        return true
    }

    private fun help(sender: CommandSender, label: String) {
        sender.msg(
            """
            <gold>사용 순서</gold>
            <yellow>1.</yellow> <yellow>/$label pos1</yellow>, <yellow>/$label pos2</yellow> <gray>구역 지정 (또는 /$label wand 로 클릭)
            <yellow>2.</yellow> <yellow>/$label gen [y]</yellow> <gray>구역 안을 새 땅으로 채우고, 구역 바깥 경사로 주변과 이어요
               <gray>두 지점의 Y가 같으면 그 높이의 평지, 다르면 그 범위 안에서 높낮이. y를 주면 그 높이의 평지
            <yellow>3.</yellow> <yellow>/$label config <distance|round|rough|step> [값]</yellow> <gray>설정 변경, 값을 빼면 기본값으로
            <yellow>4.</yellow> <yellow>/$label undo</yellow> <gray>마지막 작업 되돌리기
            <gray>기타: /$label info (지형 분석), /$label config (현재 설정 보기), /$label reload
            """.trimIndent()
        )
    }

    private fun distanceOf(player: Player): Int {
        val pdc = player.persistentDataContainer
        return pdc.get(distanceKey, PersistentDataType.INTEGER)
            ?: pdc.get(legacySmoothKey, PersistentDataType.INTEGER)
            ?: plugin.settings.defaultDistance
    }

    private fun roundOf(player: Player): Int =
        player.persistentDataContainer.get(roundKey, PersistentDataType.INTEGER) ?: plugin.settings.defaultRound

    private fun roughOf(player: Player): Double =
        player.persistentDataContainer.get(roughKey, PersistentDataType.DOUBLE) ?: plugin.settings.defaultRough

    private fun stepOf(player: Player): Int =
        player.persistentDataContainer.get(stepKey, PersistentDataType.INTEGER) ?: plugin.settings.maxStep.toInt()

    private fun roughText(rough: Double) = if (rough.isNaN()) "auto" else "%.1f".format(rough)

    private fun stepText(step: Int) = if (step == 0) "끔" else "${step}블록"

    private fun targetText(params: GenerateParams): String =
        if (params.isFlat()) "${params.lowY()}(평지)" else "${params.lowY()}~${params.highY()}"

    private fun config(player: Player, label: String, key: String?, value: String?) {
        val pdc = player.persistentDataContainer
        val s = plugin.settings
        when (key?.lowercase()) {
            null -> player.msg(
                "<white>현재 설정: distance <aqua>${distanceOf(player)}</aqua>블록, round <aqua>${roundOf(player)}</aqua>, rough <aqua>${roughText(roughOf(player))}</aqua>, " +
                    "step <aqua>${stepText(stepOf(player))}</aqua> <gray>(/$label config <distance|round|rough|step> [값])"
            )
            "step" -> when {
                value == null -> {
                    pdc.remove(stepKey)
                    player.msg("<green>step을 기본값 <aqua>${stepText(s.maxStep.toInt())}</aqua>(으)로 되돌렸어요.")
                }
                else -> {
                    val n = value.toIntOrNull()?.takeIf { it in 0..MAX_STEP } ?: run {
                        player.msg("<red>step은 0~$MAX_STEP 사이 정수예요. (0이면 경사 제한을 꺼요)")
                        return
                    }
                    pdc.set(stepKey, PersistentDataType.INTEGER, n)
                    val note = when (n) {
                        0 -> "경사 제한 없음, 절벽이 생길 수 있어요"
                        1 -> "한 칸씩 걸어 올라갈 수 있는 가장 완만한 경사예요"
                        else -> "옆 칸과 최대 ${n}블록까지 차이 나요, 경사가 짧고 가팔라져요"
                    }
                    player.msg("<green>step: <aqua>${stepText(n)}</aqua> <gray>($note)")
                }
            }
            "distance" -> when {
                value == null -> {
                    pdc.remove(distanceKey)
                    pdc.remove(legacySmoothKey)
                    player.msg("<green>distance를 기본값 <aqua>${s.defaultDistance}</aqua>블록으로 되돌렸어요.")
                }
                else -> {
                    val n = value.toIntOrNull()?.takeIf { it in 0..MAX_DISTANCE } ?: run {
                        player.msg("<red>distance는 0~$MAX_DISTANCE 사이 정수예요.")
                        return
                    }
                    pdc.set(distanceKey, PersistentDataType.INTEGER, n)
                    pdc.remove(legacySmoothKey)
                    val note = if (n == 0) "구역 바깥은 건드리지 않아요, 경계에 턱이 생길 수 있어요"
                               else "구역 바깥 ${n}블록에 걸쳐 주변 지형과 이어져요, 높이 차가 크면 경사가 가팔라져요"
                    player.msg("<green>distance: <aqua>$n</aqua>블록 <gray>($note)")
                }
            }
            "round" -> when {
                value == null -> {
                    pdc.remove(roundKey)
                    player.msg("<green>round를 기본값 <aqua>${s.defaultRound}</aqua>(으)로 되돌렸어요.")
                }
                else -> {
                    val n = value.toIntOrNull()?.takeIf { it in 0..MAX_ROUND } ?: run {
                        player.msg("<red>round는 0~$MAX_ROUND 사이 정수예요. (0이면 네모, 100이면 가장 둥글게)")
                        return
                    }
                    pdc.set(roundKey, PersistentDataType.INTEGER, n)
                    val note = when (n) {
                        0 -> "구역 모서리를 그대로 네모나게 둬요"
                        MAX_ROUND -> "모서리 반지름이 짧은 변의 절반이에요, 정사각형 구역이면 원이 돼요"
                        else -> "모서리 반지름이 짧은 변 절반의 ${n}%예요"
                    }
                    player.msg("<green>round: <aqua>$n</aqua> <gray>($note)")
                }
            }
            "rough" -> when {
                value == null -> {
                    pdc.remove(roughKey)
                    player.msg("<green>rough를 기본값 <aqua>${roughText(s.defaultRough)}</aqua>(으)로 되돌렸어요.")
                }
                else -> {
                    val r = Settings.parseRough(value)?.takeIf { it.isNaN() || it <= 16 } ?: run {
                        player.msg("<red>rough는 auto 또는 0~16 사이 숫자예요.")
                        return
                    }
                    pdc.set(roughKey, PersistentDataType.DOUBLE, r)
                    val note = when {
                        r.isNaN() -> "주변 지형에 맞춰 자동으로 정해요"
                        r == 0.0 -> "잔 굴곡 없이 큰 높낮이만 있어요"
                        else -> "최대 ${roughText(r)}블록 잔 굴곡이 생겨요"
                    }
                    player.msg("<gray>rough는 두 지점의 Y가 다를 때만 써요. 한 층이면 항상 평지예요.")
                    player.msg("<green>rough: <aqua>${roughText(r)}</aqua> <gray>($note)")
                }
            }
            else -> player.msg("<red>설정 항목은 distance, rough, step 중 하나예요.")
        }
    }

    private fun region(player: Player): Region? {
        val region = plugin.selections.of(player.uniqueId).region(player.world)
        if (region == null) {
            player.msg("<red>이 월드에서 두 지점을 먼저 찍어 주세요. <gray>(/mt pos1, /mt pos2)")
            return null
        }
        if (region.columns > plugin.settings.maxColumns) {
            player.msg("<red>영역이 너무 커요: ${region.columns}칸 (최대 ${plugin.settings.maxColumns}칸)")
            return null
        }
        if (player.uniqueId in busy) {
            player.msg("<red>이전 작업이 아직 진행 중이에요.")
            return null
        }
        return region
    }

    private fun gen(player: Player, label: String, yArg: String?) {
        val world = player.world
        val minY = world.minHeight + 1
        val maxY = world.maxHeight - 2
        // Without a height, the two corners' Y set the range: equal means flat, apart means rolling between them.
        val (low, high) = if (yArg == null) {
            val range = plugin.selections.of(player.uniqueId).heights(world) ?: run {
                player.msg("<red>이 월드에서 두 지점을 먼저 찍어 주세요. <gray>(/mt pos1, /mt pos2)")
                return
            }
            if (range.first < minY || range.last > maxY) {
                player.msg("<red>두 지점의 Y는 $minY~$maxY 사이여야 해요. <gray>(지금 ${range.first}~${range.last})")
                return
            }
            range.first to range.last
        } else {
            val y = yArg.toIntOrNull()?.takeIf { it in minY..maxY } ?: run {
                player.msg("<red>y는 $minY~$maxY 사이 정수예요. <gray>(/$label gen 만 치면 두 지점의 Y 범위를 써요)")
                return
            }
            y to y
        }
        val region = region(player) ?: return
        val params = GenerateParams(
            low, high, roughOf(player), plugin.settings.scale, distanceOf(player), BLUR_PASSES, stepOf(player).toDouble(),
            world.seed, minY, maxY, roundOf(player),
        )
        generate(player, region, params)
    }

    private fun info(player: Player, region: Region) {
        busy += player.uniqueId
        player.msg("<gray>지형을 분석하는 중...")
        SurfaceScanner.scan(region, plugin.settings.analysisMargin, plugin.async).thenApply { grid ->
            val m = grid.margin
            TerrainStats.of(grid.innerHeights(), grid.width - 2 * m, grid.depth - 2 * m) to
                TerrainStats.ofBand(grid.surface, grid.width, grid.depth, m)
        }.whenComplete { result, error ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                busy -= player.uniqueId
                if (error != null) {
                    player.msg("<red>분석 실패: ${error.cause?.message ?: error.message}")
                    plugin.slF4JLogger.warn("info failed", error)
                    return@Runnable
                }
                val (inside, around) = result
                player.msg(
                    """
                    <gold>지형 분석</gold> <gray>${region.width} x ${region.depth}
                    <white>주변</white> <gray>높이 ${around.min()}~${around.max()} (중앙값 ${around.median()}), 거칠기 ${"%.2f".format(around.roughness())} → <aqua>${around.describe()}</aqua>
                    <white>안쪽(현재)</white> <gray>높이 ${inside.min()}~${inside.max()} (중앙값 ${inside.median()})
                    <white>추천</white> <gray>/mt gen ${around.median()} <dark_gray>(auto일 때 기복 ${"%.1f".format(TerrainGenerator.autoRoughness(around))}블록)
                    """.trimIndent()
                )
            })
        }
    }

    private fun generate(player: Player, region: Region, params: GenerateParams) {
        busy += player.uniqueId
        val settings = plugin.settings
        val refWorld = if (settings.referenceTerrain) {
            if (Bukkit.getWorld(ReferenceWorlds.PREFIX + region.world.name) == null) {
                player.msg("<gray>바닐라 참조 월드를 준비하는 중... (처음 한 번만)")
            }
            plugin.references.of(region.world)
        } else {
            null
        }
        player.msg("<gray>주변 지형을 분석하고 땅을 설계하는 중...")
        // The band plus a fixed ring beyond it that holds the band's outer end.
        val margin = params.edge() + settings.analysisMargin
        val main = SurfaceScanner.scan(region, margin, plugin.async)
        val ref: CompletableFuture<SurfaceGrid?> = refWorld
            ?.let { SurfaceScanner.scan(region.copy(world = it), margin, plugin.async).thenApply<SurfaceGrid?> { g -> g } }
            ?: CompletableFuture.completedFuture(null)
        main.thenCombineAsync(ref, { grid, refGrid ->
            Generation.design(
                grid, refGrid, params, settings.maxFillDepth, settings.copyPlants, settings.beachWidth, settings.fillOres,
            )
        }, plugin.async).whenComplete { result, error ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (error != null) {
                    busy -= player.uniqueId
                    player.msg("<red>생성 실패: ${error.cause?.message ?: error.message}")
                    plugin.slF4JLogger.warn("generate failed", error)
                    return@Runnable
                }
                val (plan, changes) = result
                val target = plan.target()
                player.msg(
                        "<white>목표 Y <aqua>${targetText(params)}</aqua>, 지표면 <aqua>${target.min()}~${target.max()}</aqua>, " +
                        "distance <aqua>${params.edge()}</aqua>블록, round <aqua>${params.round()}</aqua>, step <aqua>${stepText(params.maxStep().toInt())}</aqua>" +
                        (if (params.isFlat()) " " else ", 기복 <aqua>${"%.1f".format(plan.roughness())}</aqua>" +
                            (if (plan.vanillaRelief()) "(바닐라 지형) " else "(노이즈) ")) +
                        "<gray>(블록 변경 ${changes.size}개)"
                )
                GenerateJob.apply(plugin, changes, undo = false, blocksPerTick = settings.blocksPerTick,
                    onProgress = { done, total -> player.msg("<gray>적용 중... ${done * 100 / total}%") },
                    onDone = {
                        busy -= player.uniqueId
                        plugin.history.push(player.uniqueId, changes)
                        player.msg("<green>땅 생성 완료! <gray>되돌리려면 /mt undo")
                    })
            })
        }
    }

    private fun undo(player: Player) {
        if (player.uniqueId in busy) {
            player.msg("<red>이전 작업이 아직 진행 중이에요.")
            return
        }
        val changes = plugin.history.pop(player.uniqueId)
        if (changes == null) {
            player.msg("<red>되돌릴 작업이 없어요.")
            return
        }
        if (Bukkit.getWorld(changes.worldId) == null) {
            player.msg("<red>작업했던 월드가 로드되어 있지 않아요.")
            return
        }
        busy += player.uniqueId
        GenerateJob.apply(plugin, changes, undo = true, blocksPerTick = plugin.settings.blocksPerTick,
            onProgress = { done, total -> player.msg("<gray>되돌리는 중... ${done * 100 / total}%") },
            onDone = {
                busy -= player.uniqueId
                player.msg("<green>되돌리기 완료 <gray>(블록 ${changes.size}개)")
            })
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        val last = args.lastOrNull()?.lowercase() ?: return emptyList()
        val sub = args[0].lowercase()
        val suggestions = when {
            args.size == 1 -> subcommands
            sub == "config" && args.size == 2 -> listOf("distance", "round", "rough", "step")
            sub == "config" && args.size == 3 && args[1].equals("step", true) -> listOf("0", "1", "2", "3")
            sub == "config" && args.size == 3 && args[1].equals("distance", true) -> listOf("0", "5", "10", "16")
            sub == "config" && args.size == 3 && args[1].equals("round", true) -> listOf("0", "25", "50", "100")
            sub == "config" && args.size == 3 && args[1].equals("rough", true) -> listOf("auto", "0", "1", "2")
            (sub == "gen" || sub == "generate") && args.size == 2 && sender is Player ->
                listOf((sender.location.blockY - 1).toString())
            else -> emptyList()
        }
        return suggestions.filter { it.startsWith(last) }
    }
}

