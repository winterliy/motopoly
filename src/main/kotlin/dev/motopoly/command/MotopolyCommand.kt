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
import dev.motopoly.world.SurfaceScanner
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

private const val MAX_SMOOTH = 64
private const val BLUR_PASSES = 2

class MotopolyCommand(private val plugin: MotopolyPlugin, private val wand: WandListener) : CommandExecutor, TabCompleter {
    private val busy = HashSet<UUID>()
    private val smoothKey = NamespacedKey(plugin, "smooth")
    private val roughKey = NamespacedKey(plugin, "rough")

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
            <yellow>2.</yellow> <yellow>/$label gen <y></yellow> <gray>구역 땅을 Y 높이로 올리거나 내리고, 주변과 자연스럽게 이어요
            <yellow>3.</yellow> <yellow>/$label config <smooth|rough> [값]</yellow> <gray>설정 변경, 값을 빼면 기본값으로
            <yellow>4.</yellow> <yellow>/$label undo</yellow> <gray>마지막 작업 되돌리기
            <gray>기타: /$label info (지형 분석), /$label config (현재 설정 보기), /$label reload
            """.trimIndent()
        )
    }

    private fun smoothOf(player: Player): Int =
        player.persistentDataContainer.get(smoothKey, PersistentDataType.INTEGER) ?: plugin.settings.defaultSmooth

    private fun roughOf(player: Player): Double =
        player.persistentDataContainer.get(roughKey, PersistentDataType.DOUBLE) ?: plugin.settings.defaultRough

    private fun roughText(rough: Double) = if (rough.isNaN()) "auto" else "%.1f".format(rough)

    private fun config(player: Player, label: String, key: String?, value: String?) {
        val pdc = player.persistentDataContainer
        val s = plugin.settings
        when (key?.lowercase()) {
            null -> player.msg(
                "<white>현재 설정: smooth <aqua>${smoothOf(player)}</aqua>블록, rough <aqua>${roughText(roughOf(player))}</aqua> " +
                    "<gray>(/$label config <smooth|rough> [값])"
            )
            "smooth" -> when {
                value == null -> {
                    pdc.remove(smoothKey)
                    player.msg("<green>smooth를 기본값 <aqua>${s.defaultSmooth}</aqua>블록으로 되돌렸어요.")
                }
                else -> {
                    val n = value.toIntOrNull()?.takeIf { it in 0..MAX_SMOOTH } ?: run {
                        player.msg("<red>smooth는 0~$MAX_SMOOTH 사이 정수예요.")
                        return
                    }
                    pdc.set(smoothKey, PersistentDataType.INTEGER, n)
                    val note = if (n == 0) "테두리부터 바로 목표 높이가 돼요" else "테두리 ${n}블록에 걸쳐 주변 지형에서 목표 높이로 바뀌어요"
                    player.msg("<green>smooth: <aqua>$n</aqua>블록 <gray>($note)")
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
                        r == 0.0 -> "기복 없는 완전 평면이에요"
                        else -> "최대 ${roughText(r)}블록 높낮이가 생겨요"
                    }
                    player.msg("<green>rough: <aqua>${roughText(r)}</aqua> <gray>($note)")
                }
            }
            else -> player.msg("<red>설정 항목은 smooth, rough 중 하나예요.")
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
        if (yArg == null) {
            player.msg("<red>높이를 입력해 주세요: <yellow>/$label gen <y></yellow> <gray>(지금 서 있는 높이: ${player.location.blockY - 1})")
            return
        }
        val y = yArg.toIntOrNull()?.takeIf { it in minY..maxY } ?: run {
            player.msg("<red>y는 $minY~$maxY 사이 정수예요.")
            return
        }
        val region = region(player) ?: return
        val params = GenerateParams(y, roughOf(player), plugin.settings.scale, smoothOf(player), BLUR_PASSES, world.seed, minY, maxY)
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
        player.msg("<gray>주변 지형을 분석하고 땅을 설계하는 중...")
        val settings = plugin.settings
        SurfaceScanner.scan(region, settings.analysisMargin, plugin.async).thenApply { grid ->
            // Water around the selection counts at its surface, so new land meets it like a shore.
            val plan = TerrainGenerator.plan(
                grid.surface, grid.width, grid.depth, grid.margin, grid.originX, grid.originZ, params
            )
            plan to GenerateJob.computeChanges(grid, plan, settings.maxFillDepth)
        }.whenComplete { result, error ->
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
                    "<white>목표 Y <aqua>${params.baseY()}</aqua>, 지표면 <aqua>${target.min()}~${target.max()}</aqua>, " +
                        "smooth <aqua>${params.edge()}</aqua>, 기복 <aqua>${"%.1f".format(plan.roughness())}</aqua> " +
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
            sub == "config" && args.size == 2 -> listOf("smooth", "rough")
            sub == "config" && args.size == 3 && args[1].equals("smooth", true) -> listOf("0", "5", "10", "16")
            sub == "config" && args.size == 3 && args[1].equals("rough", true) -> listOf("auto", "0", "1", "2")
            (sub == "gen" || sub == "generate") && args.size == 2 && sender is Player ->
                listOf((sender.location.blockY - 1).toString())
            else -> emptyList()
        }
        return suggestions.filter { it.startsWith(last) }
    }
}
