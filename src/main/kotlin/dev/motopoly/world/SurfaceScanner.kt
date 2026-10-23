package dev.motopoly.world

import dev.motopoly.selection.Region
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.data.BlockData
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Surface heights over a region plus [margin] columns on each side, read from
 * chunk snapshots so the heavy part can run off the main thread.
 */
class SurfaceGrid(
    val world: World,
    val originX: Int,
    val originZ: Int,
    val width: Int,
    val depth: Int,
    val margin: Int,
    /** Y of the topmost terrain block (trees, plants and water skipped). */
    val heights: IntArray,
    /** Y of the topmost non-air block, trees and water included. */
    val skyHeights: IntArray,
    private val snapshots: Map<Long, ChunkSnapshot>,
) {
    fun index(x: Int, z: Int) = (z - originZ) * width + (x - originX)

    fun snapshot(x: Int, z: Int): ChunkSnapshot = snapshots.getValue(SurfaceScanner.chunkKey(x shr 4, z shr 4))

    fun blockData(x: Int, y: Int, z: Int): BlockData = snapshot(x, z).getBlockData(x and 15, y, z and 15)

    fun type(x: Int, y: Int, z: Int): Material = snapshot(x, z).getBlockType(x and 15, y, z and 15)

    /** Heights of the selection only, without the margin. */
    fun innerHeights(): IntArray {
        val w = width - 2 * margin
        val d = depth - 2 * margin
        val out = IntArray(w * d)
        for (z in 0 until d) System.arraycopy(heights, (z + margin) * width + margin, out, z * w, w)
        return out
    }
}

object SurfaceScanner {
    private val NON_TERRAIN = setOf(
        Material.MUSHROOM_STEM, Material.BROWN_MUSHROOM_BLOCK, Material.RED_MUSHROOM_BLOCK,
        Material.BAMBOO, Material.CACTUS, Material.PUMPKIN, Material.MELON,
        Material.BEE_NEST, Material.MANGROVE_ROOTS, Material.MUDDY_MANGROVE_ROOTS,
        Material.POINTED_DRIPSTONE, Material.CHORUS_PLANT, Material.CHORUS_FLOWER,
    )

    /** Ground the flattener should treat as the land surface. */
    fun isTerrain(type: Material) =
        type.isSolid && type !in NON_TERRAIN && !Tag.LOGS.isTagged(type) && !Tag.LEAVES.isTagged(type)

    fun chunkKey(cx: Int, cz: Int) = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)

    /**
     * Loads the chunks (async on Paper), snapshots them on the main thread and
     * builds the grid on [async].
     */
    fun scan(region: Region, margin: Int, async: Executor): CompletableFuture<SurfaceGrid> {
        val world = region.world
        val minX = region.minX - margin
        val minZ = region.minZ - margin
        val maxX = region.maxX + margin
        val maxZ = region.maxZ + margin

        val futures = ArrayList<CompletableFuture<ChunkSnapshot>>()
        for (cx in (minX shr 4)..(maxX shr 4)) {
            for (cz in (minZ shr 4)..(maxZ shr 4)) {
                // Paper completes this future on the main thread, where snapshotting is allowed.
                futures += world.getChunkAtAsync(cx, cz).thenApply { it.getChunkSnapshot(true, false, false) }
            }
        }

        return CompletableFuture.allOf(*futures.toTypedArray()).thenApplyAsync({
            val snapshots = futures.associate { f -> f.join().let { chunkKey(it.x, it.z) to it } }
            build(world, minX, minZ, maxX, maxZ, margin, snapshots)
        }, async)
    }

    private fun build(
        world: World, minX: Int, minZ: Int, maxX: Int, maxZ: Int, margin: Int,
        snapshots: Map<Long, ChunkSnapshot>,
    ): SurfaceGrid {
        val width = maxX - minX + 1
        val depth = maxZ - minZ + 1
        val heights = IntArray(width * depth)
        val sky = IntArray(width * depth)
        val floor = world.minHeight

        for (z in minZ..maxZ) {
            for (x in minX..maxX) {
                val snap = snapshots.getValue(chunkKey(x shr 4, z shr 4))
                val lx = x and 15
                val lz = z and 15
                val top = snap.getHighestBlockYAt(lx, lz)
                var y = top
                while (y > floor && !isTerrain(snap.getBlockType(lx, y, lz))) y--
                val i = (z - minZ) * width + (x - minX)
                heights[i] = y
                sky[i] = maxOf(top, y)
            }
        }
        return SurfaceGrid(world, minX, minZ, width, depth, margin, heights, sky, snapshots)
    }
}
