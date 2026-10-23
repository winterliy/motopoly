package dev.motopoly.command

import dev.motopoly.MotopolyPlugin
import dev.motopoly.msg
import dev.motopoly.selection.BlockPoint
import dev.motopoly.selection.Region
import dev.motopoly.selection.WandListener
import dev.motopoly.terrain.FlattenParams
import dev.motopoly.terrain.FlattenPlanner
import dev.motopoly.terrain.TerrainStats
import dev.motopoly.world.FlattenJob
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

    private val subcommands = listOf("help", "wand", "pos1", "pos2", "info", "flatten", "edge", "undo", "reload")
    private val flattenKeys = listOf("height=", "rough=", "scale=", "feather=", "smooth=", "trees=", "seed=")

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
            "flatten" -> region(sender)?.let { region ->
                val params = parseFlatten(sender, region, args.drop(1)) ?: return true
                flatten(sender, region, params.first, params.second)
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
            <yellow>/$label info</yellow> <gray>선택 영역 지형 분석
            <yellow>/$label flatten</yellow> <gray>[height=<y|auto>] [rough=<n|auto>] [scale=<n>] [feather=<n>] [smooth=<n>] [trees=clear|keep]
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
                val note = if (n == 0) " <gray>(경계가 각지게 끊겨요)" else " <gray>(영역 테두리 ${n}블록이 주변 지형으로 이어지는 경사가 돼요)"
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

    private fun parseFlatten(player: Player, region: Region, args: List<String>): Pair<FlattenParams, Boolean>? {
        val s = plugin.settings
        var height: Int? = null
        var rough = s.defaultRough
        var scale = s.defaultScale
        var feather = edgeOf(player)
        var smooth = s.defaultSmooth
        var clearTrees = s.defaultClearTrees
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
                "feather" -> value.toIntOrNull()?.takeIf { it >= 0 }?.also { feather = it } != null
                "smooth" -> value.toIntOrNull()?.takeIf { it in 0..10 }?.also { smooth = it } != null
                "trees" -> when (value.lowercase()) {
                    "clear" -> { clearTrees = true; true }
                    "keep" -> { clearTrees = false; true }
                    else -> false
                }
                "seed" -> value.toLongOrNull()?.also { seed = it } != null
                else -> false
            }
            if (!ok) {
                player.msg("<red>잘못된 옵션: $arg")
                return null
            }
        }
        val world = region.world
        return FlattenParams(height, rough, scale, feather, smooth, seed, world.minHeight + 1, world.maxHeight - 2) to clearTrees
    }

    private fun info(player: Player, region: Region) {
        busy += player.uniqueId
        player.msg("<gray>지형을 분석하는 중...")
        SurfaceScanner.scan(region, plugin.settings.analysisMargin, plugin.async).thenApply { grid ->
            val m = grid.margin
            TerrainStats.of(grid.innerHeights(), grid.width - 2 * m, grid.depth - 2 * m) to
                TerrainStats.of(grid.heights, grid.width, grid.depth)
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
                    <white>높이</white> <gray>최저 ${inside.min()} / 최고 ${inside.max()} / 중앙값 ${inside.median()} / 표준편차 ${"%.2f".format(inside.stdDev())}
                    <white>거칠기</white> <gray>${"%.2f".format(inside.roughness())} → <aqua>${inside.describe()}</aqua> <gray>(주변 포함: ${around.describe()})
                    <white>추천</white> <gray>/mt flatten height=${inside.median()} rough=${"%.1f".format(FlattenPlanner.autoRoughness(around))}
                    """.trimIndent()
                )
            })
        }
    }

    private fun flatten(player: Player, region: Region, params: FlattenParams, clearTrees: Boolean) {
        busy += player.uniqueId
        player.msg("<gray>지형을 분석하고 평지를 설계하는 중...")
        val settings = plugin.settings
        SurfaceScanner.scan(region, settings.analysisMargin, plugin.async).thenApply { grid ->
            val plan = FlattenPlanner.plan(
                grid.heights, grid.width, grid.depth, grid.margin, grid.originX, grid.originZ, params
            )
            plan to FlattenJob.computeChanges(grid, plan, clearTrees)
        }.whenComplete { result, error ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (error != null) {
                    busy -= player.uniqueId
                    player.msg("<red>설계 실패: ${error.cause?.message ?: error.message}")
                    plugin.slF4JLogger.warn("flatten failed", error)
                    return@Runnable
                }
                val (plan, changes) = result
                player.msg(
                    "<white>기준 높이 <aqua>${plan.baseY()}</aqua>, 기복 <aqua>${"%.1f".format(plan.roughness())}</aqua>블록 " +
                        "<gray>(깎기 ${plan.cut()} / 채우기 ${plan.fill()}, 블록 변경 ${changes.size}개)"
                )
                FlattenJob.apply(plugin, changes, undo = false, blocksPerTick = settings.blocksPerTick,
                    onProgress = { done, total -> player.msg("<gray>적용 중... ${done * 100 / total}%") },
                    onDone = {
                        busy -= player.uniqueId
                        plugin.history.push(player.uniqueId, changes)
                        player.msg("<green>평탄화 완료! <gray>되돌리려면 /mt undo")
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
        FlattenJob.apply(plugin, changes, undo = true, blocksPerTick = plugin.settings.blocksPerTick,
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
            args[0].equals("flatten", true) -> {
                val used = args.dropLast(1).map { it.substringBefore('=') + "=" }.toSet()
                when {
                    last.startsWith("trees=") -> listOf("trees=clear", "trees=keep").filter { it.startsWith(last) }
                    last.startsWith("height=") || last.startsWith("rough=") ->
                        listOf(last.substringBefore('=') + "=auto").filter { it.startsWith(last) }
                    else -> flattenKeys.filter { it !in used && it.startsWith(last.lowercase()) }
                }
            }
            else -> emptyList()
        }
    }
}
