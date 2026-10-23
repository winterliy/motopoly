package dev.motopoly.world

import dev.motopoly.terrain.GeneratePlan
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/** The material stack of a column, copied so new ground matches the local biome. */
private class ColumnProfile(val top: BlockData, val filler: BlockData, val fillerDepth: Int, val deep: BlockData)

object GenerateJob {
    private const val MAX_FILLER_DEPTH = 6
    private val AIR = Material.AIR.createBlockData()
    private val STONE = Material.STONE.createBlockData()

    /**
     * Works out every block edit for [plan]. Reads only from the snapshots in
     * [grid], so it is safe to call off the main thread.
     *
     * Each column: everything above the new surface is removed, the surface
     * gets the material stack of the nearest column outside the selection, and
     * any air or water underneath is filled down to solid ground (at most
     * [maxFillDepth] blocks).
     */
    fun computeChanges(grid: SurfaceGrid, plan: GeneratePlan, maxFillDepth: Int): ChangeSet {
        val changes = ChangeSet(grid.world.uid)
        val m = grid.margin
        val w = grid.width - 2 * m
        val d = grid.depth - 2 * m
        val floor = grid.world.minHeight
        val ceiling = grid.world.maxHeight - 1
        val profiles = HashMap<Int, ColumnProfile>()

        for (iz in 0 until d) {
            for (ix in 0 until w) {
                val x = grid.originX + m + ix
                val z = grid.originZ + m + iz
                val surface = plan.target()[iz * w + ix]

                val (bx, bz) = nearestOutside(ix, iz, w, d)
                val boundaryX = grid.originX + m + bx
                val boundaryZ = grid.originZ + m + bz
                val profile = profiles.getOrPut(grid.index(boundaryX, boundaryZ)) {
                    profileOf(grid, boundaryX, grid.heights[grid.index(boundaryX, boundaryZ)], boundaryZ)
                }

                fun set(y: Int, data: BlockData) {
                    if (y in floor..ceiling) changes.add(x, y, z, grid.blockData(x, y, z), data)
                }

                for (y in surface + 1..grid.skyHeights[grid.index(x, z)]) set(y, AIR)
                set(surface, profile.top)
                for (i in 1..profile.fillerDepth) set(surface - i, profile.filler)
                var y = surface - profile.fillerDepth - 1
                val lowest = surface - maxFillDepth
                while (y >= floor && y >= lowest && !grid.type(x, y, z).isSolid) {
                    set(y, profile.deep)
                    y--
                }
            }
        }
        return changes
    }

    /** Column just outside the selection, on the side closest to (ix, iz). Selection-local coordinates. */
    private fun nearestOutside(ix: Int, iz: Int, w: Int, d: Int): Pair<Int, Int> {
        val toLeft = ix
        val toRight = w - 1 - ix
        val toTop = iz
        val toBottom = d - 1 - iz
        return when (minOf(minOf(toLeft, toRight), minOf(toTop, toBottom))) {
            toLeft -> -1 to iz
            toRight -> w to iz
            toTop -> ix to -1
            else -> ix to d
        }
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
