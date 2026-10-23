package dev.motopoly.command

import dev.motopoly.MotopolyPlugin
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

private const val MAX_EDGE = 64

class MotopolyCommand(private val plugin: MotopolyPlugin, private val wand: WandListener) : CommandExecutor, TabCompleter {
    private val busy = HashSet<UUID>()
    private val edgeKey = NamespacedKey(plugin, "edge")

    private val subcommands = listOf("help", "wand", "pos1", "pos2", "info", "generate", "gen", "edge", "undo", "reload")
    private val generateKeys = listOf("height=", "rough=", "scale=", "edge=", "smooth=", "seed=")

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
            "generate", "gen" -> region(sender)?.let { region ->
                val params = parseGenerate(sender, region, args.drop(1)) ?: return true
                generate(sender, region, params)
            }
            "edge" -> edge(sender, args.getOrNull(1))
            "undo" -> undo(sender)
            else -> help(sender, label)
        }
        return true
    }

    private fun help(sender: CommandSender, label: String) {
        sender.msg(
            """
            <gold>명령어</gold>
            <yellow>/$label help</yellow> <gray>이 도움말 보기
            <yellow>/$label wand</yellow> <gray>선택 도구 받기
            <yellow>/$label pos1</yellow>, <yellow>pos2</yellow> <gray>현재 위치를 지점으로
            <yellow>/$label info</yellow> <gray>선택 영역과 주변 지형 분석
            <yellow>/$label gen</yellow> <gray>[height=<y|auto>] [rough=<n|auto>] [scale=<n>] [edge=<n>] [smooth=<n>] — 주변과 이어지는 땅 생성
            <yellow>/$label edge</yellow> <gray>[<n>|reset] 가장자리 스무딩 폭 확인/설정
            <yellow>/$label undo</yellow> <gray>마지막 작업 되돌리기
            <yellow>/$label reload</yellow> <gray>config.yml 다시 불러오기
            """.trimIndent()
        )
    }

    /** Player's edge smoothing width, kept in their persistent data so it survives restarts. */
    private fun edgeOf(player: Player): Int =
        player.persistentDataContainer.get(edgeKey, PersistentDataType.INTEGER) ?: plugin.settings.defaultFeather

    private fun edge(player: Player, value: String?) {
        when {
            value == null -> player.msg("<white>가장자리 스무딩 폭: <aqua>${edgeOf(player)}</aqua>블록 <gray>(/mt edge <0~$MAX_EDGE>|reset)")
            value.equals("reset", true) -> {
                player.persistentDataContainer.remove(edgeKey)
                player.msg("<green>가장자리 스무딩 폭을 기본값 <aqua>${plugin.settings.defaultFeather}</aqua>블록으로 되돌렸어요.")
            }
            else -> {
                val n = value.toIntOrNull()?.takeIf { it in 0..MAX_EDGE } ?: run {
                    player.msg("<red>0~$MAX_EDGE 사이 숫자를 입력해 주세요.")
                    return
                }
                player.persistentDataContainer.set(edgeKey, PersistentDataType.INTEGER, n)
                val note = if (n == 0) " <gray>(기복과 목표 높이가 테두리부터 바로 적용돼요)"
                           else " <gray>(테두리 ${n}블록에 걸쳐 주변 지형에서 서서히 바뀌어요)"
                player.msg("<green>가장자리 스무딩 폭: <aqua>$n</aqua>블록$note")
            }
        }
    }

    private fun region(player: Player): Region? {
        val region = plugin.selections.of(player.uniqueId).region(player.world)
        if (region == null) {
            player.msg("<red>이 월드에서 두 지점을 먼저 찍어 주세요. <gray>(/mt wand)")
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

    private fun parseGenerate(player: Player, region: Region, args: List<String>): GenerateParams? {
        val s = plugin.settings
        var height: Int? = null
        var rough = s.defaultRough
        var scale = s.defaultScale
        var edge = edgeOf(player)
        var smooth = s.defaultSmooth
        var seed = region.world.seed
        for (arg in args) {
            val (key, value) = arg.split('=', limit = 2).takeIf { it.size == 2 } ?: run {
                player.msg("<red>'$arg'은(는) key=value 형식이 아니에요.")
                return null
            }
            val ok = when (key.lowercase()) {
                "height" -> value.equals("auto", true) || value.toIntOrNull()?.also { height = it } != null
                "rough" -> if (value.equals("auto", true)) { rough = Double.NaN; true }
                           else value.toDoubleOrNull()?.takeIf { it >= 0 }?.also { rough = it } != null
                "scale" -> value.toDoubleOrNull()?.takeIf { it > 0 }?.also { scale = it } != null
                "edge" -> value.toIntOrNull()?.takeIf { it in 0..MAX_EDGE }?.also { edge = it } != null
                "smooth" -> value.toIntOrNull()?.takeIf { it in 0..10 }?.also { smooth = it } != null
                "seed" -> value.toLongOrNull()?.also { seed = it } != null
                else -> false
            }
            if (!ok) {
                player.msg("<red>잘못된 옵션: $arg")
                return null
            }
        }
        val world = region.world
        return GenerateParams(height, rough, scale, edge, smooth, seed, world.minHeight + 1, world.maxHeight - 2)
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
                    <white>안쪽(현재)</white> <gray>높이 ${inside.min()}~${inside.max()} — 생성할 때는 무시돼요
                    <white>추천</white> <gray>/mt gen rough=${"%.1f".format(TerrainGenerator.autoRoughness(around))} <dark_gray>(평평하게: height=${around.median()} rough=0)
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
                    "<white>새 지표면 높이 <aqua>${target.min()}~${target.max()}</aqua>, 기복 <aqua>${"%.1f".format(plan.roughness())}</aqua>블록 " +
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
        val last = args.lastOrNull() ?: return emptyList()
        return when {
            args.size == 1 -> subcommands.filter { it.startsWith(last.lowercase()) }
            args.size == 2 && args[0].equals("edge", true) ->
                listOf("0", "5", "10", "16", "reset").filter { it.startsWith(last.lowercase()) }
            args[0].equals("generate", true) || args[0].equals("gen", true) -> {
                val used = args.dropLast(1).map { it.substringBefore('=') + "=" }.toSet()
                if (last.startsWith("height=") || last.startsWith("rough=")) {
                    listOf(last.substringBefore('=') + "=auto").filter { it.startsWith(last) }
                } else {
                    generateKeys.filter { it !in used && it.startsWith(last.lowercase()) }
                }
            }
            else -> emptyList()
        }
    }
}
