package dev.motopoly.world

import dev.motopoly.terrain.FlattenPlan
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/** The material stack of a column, copied so rebuilt ground matches the local biome. */
private class ColumnProfile(val top: BlockData, val filler: BlockData, val fillerDepth: Int, val deep: BlockData)

object FlattenJob {
    private const val MAX_FILLER_DEPTH = 6
    private val AIR = Material.AIR.createBlockData()
    private val STONE = Material.STONE.createBlockData()

    /**
     * Works out every block edit for [plan]. Reads only from the snapshots in
     * [grid], so it is safe to call off the main thread.
     */
    fun computeChanges(grid: SurfaceGrid, plan: FlattenPlan, clearTrees: Boolean): ChangeSet {
        val changes = ChangeSet(grid.world.uid)
        val m = grid.margin
        val w = grid.width - 2 * m
        val d = grid.depth - 2 * m
        val ceiling = grid.world.maxHeight - 1

        for (iz in 0 until d) {
            for (ix in 0 until w) {
                val x = grid.originX + m + ix
                val z = grid.originZ + m + iz
                val gi = grid.index(x, z)
                val old = grid.heights[gi]
                val new = plan.target()[iz * w + ix]
                if (new == old && !clearTrees) continue

                val profile = profileOf(grid, x, old, z)
                fun set(y: Int, data: BlockData) {
                    if (y in grid.world.minHeight..ceiling) changes.add(x, y, z, grid.blockData(x, y, z), data)
                }

                // Everything above the new surface goes: lowered ground, trees, plants, standing water.
                var clearTo = maxOf(old, grid.skyHeights[gi])
                // Grass, flowers and snow layers don't show up in the heightmap.
                while (clearTo < ceiling) {
                    val above = grid.type(x, clearTo + 1, z)
                    if (above.isAir || above.isSolid) break
                    clearTo++
                }
                for (y in new + 1..clearTo) set(y, AIR)

                set(new, profile.top)
                for (i in 1..profile.fillerDepth) set(new - i, profile.filler)
                // Raised ground: everything between the old surface and the filler band.
                for (y in old..new - profile.fillerDepth - 1) set(y, profile.deep)
            }
        }
        return changes
    }

    private fun profileOf(grid: SurfaceGrid, x: Int, surface: Int, z: Int): ColumnProfile {
        val floor = grid.world.minHeight
        val top = grid.blockData(x, surface, z)
        if (surface - 1 < floor || !SurfaceScanner.isTerrain(grid.type(x, surface - 1, z))) {
            return ColumnProfile(top, top, 0, top)
        }
        val filler = grid.blockData(x, surface - 1, z)
        var depth = 1
        while (depth < MAX_FILLER_DEPTH && surface - 1 - depth >= floor &&
            grid.type(x, surface - 1 - depth, z) == filler.material
        ) depth++
        val deepY = surface - 1 - depth
        val deep = if (deepY >= floor && SurfaceScanner.isTerrain(grid.type(x, deepY, z))) {
            grid.blockData(x, deepY, z)
        } else {
            STONE
        }
        return ColumnProfile(top, filler, depth, deep)
    }

    /**
     * Writes [changes] on the main thread, [blocksPerTick] at a time, without
     * physics so water and sand don't cascade mid-edit.
     */
    fun apply(
        plugin: Plugin, changes: ChangeSet, undo: Boolean, blocksPerTick: Int,
        onProgress: (done: Int, total: Int) -> Unit, onDone: () -> Unit,
    ): BukkitTask {
        val world = Bukkit.getWorld(changes.worldId) ?: error("world is not loaded")
        var cursor = 0
        var lastReport = 0
        lateinit var task: BukkitTask
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val end = minOf(cursor + blocksPerTick, changes.size)
            changes.forEach(cursor until end, undo) { x, y, z, data ->
                world.getBlockAt(x, y, z).setBlockData(data, false)
            }
            cursor = end
            if (cursor >= changes.size) {
                task.cancel()
                onDone()
            } else if (cursor - lastReport >= changes.size / 5) {
                lastReport = cursor
                onProgress(cursor, changes.size)
            }
        }, 1L, 1L)
        return task
    }
}
