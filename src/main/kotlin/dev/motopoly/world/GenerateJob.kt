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
    private val WATER = Material.WATER.createBlockData()
    // Vanilla beaches: four or so blocks of sand over sandstone.
    private val BEACH = ColumnProfile(
        Material.SAND.createBlockData(), Material.SAND.createBlockData(), 3, Material.SANDSTONE.createBlockData()
    )
    // Sea floor where the neighbouring floor can't be copied (it opens into a cave).
    private val SEA_FLOOR = ColumnProfile(
        Material.SAND.createBlockData(), Material.SAND.createBlockData(), 2, Material.GRAVEL.createBlockData()
    )
    // Ground for land with no dry neighbour to copy from, e.g. an island out at sea.
    private val MEADOW = ColumnProfile(
        Material.GRASS_BLOCK.createBlockData(), Material.DIRT.createBlockData(), 3, STONE
    )
    private val SHORT_GRASS = Material.SHORT_GRASS.createBlockData()
    private val FLOWERS = listOf(Material.DANDELION.createBlockData(), Material.POPPY.createBlockData())

    /**
     * Works out every block edit for [plan]. Reads only from the snapshots in
     * [grid] and [reference], so it is safe to call off the main thread.
     *
     * Each column: everything above the new surface is removed (or, below
     * [waterLevel], flooded), the surface gets a material stack, and any air or
     * water underneath is filled down to solid ground (at most [maxFillDepth]
     * blocks). The stack is chosen by where the column ends up:
     * - well under water: the sea floor of the nearest column outside;
     * - at the waterline within [beachWidth] blocks of water: sand over sandstone;
     * - inland: the vanilla [reference] column when it is dry land, else the
     *   nearest dry column outside, else grass over dirt.
     * Plants come from the reference column, or are scattered over bare grass.
     */
    fun computeChanges(
        grid: SurfaceGrid, plan: GeneratePlan, maxFillDepth: Int,
        reference: SurfaceGrid?, copyPlants: Boolean,
        waterLevel: Int?, beachWidth: Int, seed: Long,
    ): ChangeSet {
        val changes = ChangeSet(grid.world.uid)
        val m = grid.margin
        val w = grid.width - 2 * m
        val d = grid.depth - 2 * m
        val floor = grid.world.minHeight
        val ceiling = grid.world.maxHeight - 1
        val profiles = HashMap<Int, ColumnProfile>()
        val toWater = waterLevel?.let { distanceToWater(grid, plan, it) }

        for (iz in 0 until d) {
            for (ix in 0 until w) {
                val x = grid.originX + m + ix
                val z = grid.originZ + m + iz
                val surface = plan.target()[iz * w + ix]
                val gi = grid.index(x, z)

                val (bx, bz) = nearestOutside(ix, iz, w, d)
                val outsideX = grid.originX + m + bx
                val outsideZ = grid.originZ + m + bz
                val outsideIndex = grid.index(outsideX, outsideZ)
                val outsideDry = grid.surface[outsideIndex] == grid.heights[outsideIndex]
                fun outsideProfile() = profiles[outsideIndex]
                    ?: profileOf(grid, outsideX, grid.heights[outsideIndex], outsideZ)?.also { profiles[outsideIndex] = it }

                // Same coordinates in the twin world; dry land there means its column is usable.
                val refTop = reference?.heights?.get(gi)
                val vanilla = refTop != null && reference.surface[gi] == refTop
                val underwater = waterLevel != null && surface < waterLevel - 2
                val beach = waterLevel != null && !underwater && surface <= waterLevel + 2 &&
                    toWater!![gi] <= beachWidth
                val profile = when {
                    underwater -> outsideProfile() ?: SEA_FLOOR
                    beach -> BEACH
                    vanilla -> profileOf(reference, x, refTop, z) ?: MEADOW
                    outsideDry -> outsideProfile() ?: MEADOW
                    else -> MEADOW
                }
                val plants = when {
                    !copyPlants || underwater || beach -> emptyList()
                    vanilla -> plantsAbove(reference, x, refTop, z)
                    profile.top.material == Material.GRASS_BLOCK -> scatteredPlant(x, z, seed)
                    else -> emptyList()
                }

                fun set(y: Int, data: BlockData) {
                    if (y in floor..ceiling) changes.add(x, y, z, grid.blockData(x, y, z), data)
                }

                val flooded = waterLevel != null && surface < waterLevel
                val top = maxOf(grid.skyHeights[gi], surface + plants.size, if (flooded) waterLevel else surface)
                for (y in surface + 1..top) {
                    set(y, if (flooded && y <= waterLevel) WATER else plants.getOrNull(y - surface - 1) ?: AIR)
                }
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

    /**
     * Blocks from each column to the nearest water, over the whole grid: water
     * outside the selection, plus selection columns that end up under water.
     */
    private fun distanceToWater(grid: SurfaceGrid, plan: GeneratePlan, waterLevel: Int): IntArray {
        val m = grid.margin
        val w = grid.width - 2 * m
        val dist = IntArray(grid.width * grid.depth) { Int.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        for (z in 0 until grid.depth) {
            for (x in 0 until grid.width) {
                val i = z * grid.width + x
                val inner = x in m until grid.width - m && z in m until grid.depth - m
                val wet = if (inner) plan.target()[(z - m) * w + (x - m)] < waterLevel
                          else grid.surface[i] > grid.heights[i]
                if (wet) {
                    dist[i] = 0
                    queue.add(i)
                }
            }
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % grid.width
            val z = i / grid.width
            for ((nx, nz) in listOf(x + 1 to z, x - 1 to z, x to z + 1, x to z - 1)) {
                if (nx !in 0 until grid.width || nz !in 0 until grid.depth) continue
                val j = nz * grid.width + nx
                if (dist[j] > dist[i] + 1) {
                    dist[j] = dist[i] + 1
                    queue.add(j)
                }
            }
        }
        return dist
    }

    /** Sparse grass and the odd flower, roughly vanilla plains density; stable per position. */
    private fun scatteredPlant(x: Int, z: Int, seed: Long): List<BlockData> {
        var h = seed xor (x.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (z.toLong() * 0xC2B2AE3D27D4EB4FuL.toLong())
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        val r = (h ushr 11).toDouble() / (1L shl 53)
        return when {
            r < 0.16 -> listOf(SHORT_GRASS)
            r < 0.175 -> listOf(FLOWERS[((h ushr 3) and 1).toInt()])
            else -> emptyList()
        }
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

    /** Grass, flowers, ferns, snow layers… sitting on the terrain (at most two blocks, e.g. tall grass). */
    private fun plantsAbove(grid: SurfaceGrid, x: Int, surface: Int, z: Int): List<BlockData> {
        val ceiling = grid.world.maxHeight - 1
        val plants = ArrayList<BlockData>(2)
        for (y in surface + 1..minOf(surface + 2, ceiling)) {
            val type = grid.type(x, y, z)
            if (type.isAir || type.isSolid || SurfaceScanner.isLiquid(type)) break
            plants += grid.blockData(x, y, z)
        }
        return plants
    }

    /** Null when the column has no ground to copy at [surface] (e.g. water opening into a cave). */
    private fun profileOf(grid: SurfaceGrid, x: Int, surface: Int, z: Int): ColumnProfile? {
        val floor = grid.world.minHeight
        if (!SurfaceScanner.isTerrain(grid.type(x, surface, z))) return null
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
