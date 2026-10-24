package dev.motopoly.world

import dev.motopoly.terrain.GeneratePlan
import dev.motopoly.terrain.OctaveNoise
import dev.motopoly.terrain.OreTable
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Snowable
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
    private val RED_BEACH = ColumnProfile(
        Material.RED_SAND.createBlockData(), Material.RED_SAND.createBlockData(), 3, Material.RED_SANDSTONE.createBlockData()
    )
    // Sea floor where no floor nearby can be copied (it all opens into caves).
    private val SEA_FLOOR = ColumnProfile(
        Material.SAND.createBlockData(), Material.SAND.createBlockData(), 2, Material.GRAVEL.createBlockData()
    )
    // Ground for land with no dry neighbour to copy from, e.g. an island out at sea.
    private val MEADOW = ColumnProfile(
        Material.GRASS_BLOCK.createBlockData(), Material.DIRT.createBlockData(), 3, STONE
    )
    private val DIRT = Material.DIRT.createBlockData()
    // Surface blocks that only make sense under open sky.
    private val TOPSOIL = setOf(
        Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM, Material.DIRT_PATH, Material.FARMLAND,
    )
    private val STONE_ORES = mapOf(
        OreTable.Ore.COAL to Material.COAL_ORE, OreTable.Ore.IRON to Material.IRON_ORE,
        OreTable.Ore.COPPER to Material.COPPER_ORE, OreTable.Ore.GOLD to Material.GOLD_ORE,
        OreTable.Ore.REDSTONE to Material.REDSTONE_ORE, OreTable.Ore.LAPIS to Material.LAPIS_ORE,
        OreTable.Ore.DIAMOND to Material.DIAMOND_ORE,
    ).mapValues { it.value.createBlockData() }
    private val DEEPSLATE_ORES = mapOf(
        OreTable.Ore.COAL to Material.DEEPSLATE_COAL_ORE, OreTable.Ore.IRON to Material.DEEPSLATE_IRON_ORE,
        OreTable.Ore.COPPER to Material.DEEPSLATE_COPPER_ORE, OreTable.Ore.GOLD to Material.DEEPSLATE_GOLD_ORE,
        OreTable.Ore.REDSTONE to Material.DEEPSLATE_REDSTONE_ORE, OreTable.Ore.LAPIS to Material.DEEPSLATE_LAPIS_ORE,
        OreTable.Ore.DIAMOND to Material.DEEPSLATE_DIAMOND_ORE,
    ).mapValues { it.value.createBlockData() }
    private val SNOW = Material.SNOW.createBlockData()
    // Snow doesn't settle on these.
    private val NO_SNOW = setOf(Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE)

    /**
     * Works out every block edit for [plan]. Reads only from the snapshots in
     * [grid] and the numbers in [vanilla], so it is safe to call off the main thread.
     *
     * Each changed column: everything above the new surface is removed (or,
     * below [waterLevel], flooded), the surface gets a material stack, and any
     * air or water underneath is filled down to solid ground (at most
     * [maxFillDepth] blocks). Band columns that keep their height are left
     * alone. The stack is chosen by where the column ends up:
     * - under water: the floor of the nearest real sea, river or lake bed, in
     *   noise-warped patches so sand, gravel, clay and dirt meet in natural blobs;
     * - at the waterline within [beachWidth] blocks of water, where that bed is
     *   sand: sand over sandstone (red sand over red sandstone);
     * - band columns on dry land: their own original stack and plants;
     * - selection columns inland: the stack vanilla's biome there would have
     *   (sand in deserts, podzol in old taiga…), else the nearest dry column
     *   outside, else grass over dirt.
     * Band columns keep their own plants; new grass gets sparse grass and
     * flowers, snowy biomes a layer of snow.
     */
    fun computeChanges(
        grid: SurfaceGrid, plan: GeneratePlan, maxFillDepth: Int,
        vanilla: VanillaColumns?, copyPlants: Boolean,
        waterLevel: Int?, beachWidth: Int, seed: Long, fillOres: Boolean,
    ): ChangeSet {
        val changes = ChangeSet(grid.world.uid)
        val m = grid.margin
        val band = plan.band()
        val w = grid.width - 2 * m
        val d = grid.depth - 2 * m
        val bw = w + 2 * band
        val floor = grid.world.minHeight
        val ceiling = grid.world.maxHeight - 1
        val profiles = HashMap<Int, ColumnProfile>()
        val toWater = waterLevel?.let { distanceToWater(grid, plan, it) }
        val seabed = waterLevel?.let { Seabed(grid, seed) { x, y, z -> floorProfile(grid, x, y, z) } }
        val vegetation = Vegetation(seed)

        for (iz in -band until d + band) {
            for (ix in -band until w + band) {
                val x = grid.originX + m + ix
                val z = grid.originZ + m + iz
                val surface = plan.target()[(iz + band) * bw + (ix + band)]
                val gi = grid.index(x, z)
                val inZone = plan.zone()[(iz + band) * bw + (ix + band)]
                // The band only reshapes ground where its height really changes.
                if (!inZone && surface == grid.heights[gi]) continue
                val ownDry = !inZone && grid.surface[gi] == grid.heights[gi]

                val (bx, bz) = nearestOutside(ix, iz, w, d)
                val outsideX = grid.originX + m + bx
                val outsideZ = grid.originZ + m + bz
                val outsideIndex = grid.index(outsideX, outsideZ)
                val outsideDry = grid.surface[outsideIndex] == grid.heights[outsideIndex]
                fun outsideProfile() = profiles[outsideIndex]
                    ?: profileOf(grid, outsideX, grid.heights[outsideIndex], outsideZ)?.also { profiles[outsideIndex] = it }

                // What vanilla lays here, where vanilla itself has dry land.
                val vanillaHere = vanilla != null && vanilla.dry(gi)
                val snowy = vanillaHere && vanilla.snowy[gi]
                val underwater = waterLevel != null && surface < waterLevel
                val bed = if (seabed != null && (underwater || surface <= waterLevel!! + 2)) seabed.profileAt(x, z) else null
                val sand = bed?.top?.material?.takeIf { it == Material.SAND || it == Material.RED_SAND }
                val beach = sand != null && !underwater && surface <= waterLevel!! + 2 && toWater!![gi] <= beachWidth
                val own = if (ownDry && !underwater && !beach) {
                    profiles[gi] ?: profileOf(grid, x, grid.heights[gi], z)?.also { profiles[gi] = it }
                } else {
                    null
                }
                val profile = when {
                    underwater -> bed ?: SEA_FLOOR
                    beach -> if (sand == Material.RED_SAND) RED_BEACH else BEACH
                    own != null -> own
                    vanillaHere -> vanillaProfile(vanilla.stacks[gi], snowy)
                    outsideDry -> outsideProfile() ?: MEADOW
                    else -> MEADOW
                }
                val plants = when {
                    !copyPlants || underwater || beach -> emptyList()
                    own != null -> plantsAbove(grid, x, grid.heights[gi], z)
                    snowy -> if (profile.top.material in NO_SNOW) emptyList() else listOf(SNOW)
                    else -> vegetation.plantsAt(if (vanillaHere) vanilla.biomes[gi] else "", profile.top.material, x, z)
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
                // Fill down to real ground: air, water, plants, and trees or leaves that would end up buried.
                while (y >= floor && y >= lowest && !SurfaceScanner.isTerrain(grid.type(x, y, z))) {
                    set(y, if (fillOres) oreIn(profile.deep, x, y, z, seed) else profile.deep)
                    y--
                }
                // Raised ground: the old topsoil is buried now, and grass or paths don't grow in the dark.
                if (y >= floor && y < surface - profile.fillerDepth && grid.type(x, y, z) in TOPSOIL) set(y, DIRT)
            }
        }
        return changes
    }

    /**
     * Blocks from each column to the nearest water, over the whole grid: water
     * beyond the band, plus selection and band columns that end up under water.
     */
    private fun distanceToWater(grid: SurfaceGrid, plan: GeneratePlan, waterLevel: Int): IntArray {
        val o = grid.margin - plan.band()
        val bw = grid.width - 2 * o
        val dist = IntArray(grid.width * grid.depth) { Int.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        for (z in 0 until grid.depth) {
            for (x in 0 until grid.width) {
                val i = z * grid.width + x
                val planned = x in o until grid.width - o && z in o until grid.depth - o
                val wet = if (planned) plan.target()[(z - o) * bw + (x - o)] < waterLevel
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

    /** Stone or deepslate fill gets vanilla-like ore veins; any other fill (sandstone, terracotta…) stays plain. */
    private fun oreIn(rock: BlockData, x: Int, y: Int, z: Int, seed: Long): BlockData {
        val ores = when (rock.material) {
            Material.STONE -> STONE_ORES
            Material.DEEPSLATE -> DEEPSLATE_ORES
            else -> return rock
        }
        return OreTable.pick(x, y, z, seed)?.let { ores.getValue(it) } ?: rock
    }

    /**
     * Column just outside the selection, on the side closest to (ix, iz).
     * Selection-local coordinates; a band column gets its own spot on that ring.
     */
    private fun nearestOutside(ix: Int, iz: Int, w: Int, d: Int): Pair<Int, Int> {
        if (ix !in 0 until w || iz !in 0 until d) return ix.coerceIn(-1, w) to iz.coerceIn(-1, d)
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

    /** A bed's stack, for [Seabed]; grass and paths drowned there become plain dirt. */
    private fun floorProfile(grid: SurfaceGrid, x: Int, surface: Int, z: Int): ColumnProfile? {
        val p = profileOf(grid, x, surface, z) ?: return null
        return if (p.top.material in TOPSOIL) ColumnProfile(DIRT, p.filler, p.fillerDepth, p.deep) else p
    }

    /** Null when the column has no ground to copy at [surface] (e.g. water opening into a cave). */
    private fun profileOf(grid: SurfaceGrid, x: Int, surface: Int, z: Int): ColumnProfile? {
        val floor = grid.world.minHeight
        return stackProfile { d -> if (surface - d >= floor) grid.blockData(x, surface - d, z) else null }
    }

    /** Vanilla's own stack for a column; snow-covered grass where snow would settle. */
    private fun vanillaProfile(stack: Array<BlockData>, snowy: Boolean): ColumnProfile {
        val p = stackProfile { d -> stack.getOrNull(d) } ?: return MEADOW
        if (!snowy || p.top.material != Material.GRASS_BLOCK) return p
        return ColumnProfile((p.top.clone() as Snowable).apply { isSnowy = true }, p.filler, p.fillerDepth, p.deep)
    }

    /**
     * Top block, the run of filler under it and what lies beneath, from [at]
     * (the block d below the top, null past the bottom of the world).
     * Null when the top isn't ground (e.g. water opening into a cave).
     */
    private fun stackProfile(at: (d: Int) -> BlockData?): ColumnProfile? {
        fun ground(d: Int) = at(d)?.takeIf { SurfaceScanner.isTerrain(it.material) }
        val top = ground(0) ?: return null
        val filler = ground(1)
            // Nothing to copy below (a cave right under the surface): soil under topsoil, then stone.
            ?: return if (top.material in TOPSOIL) ColumnProfile(top, DIRT, 3, STONE) else ColumnProfile(top, top, 0, STONE)
        var depth = 1
        while (depth < MAX_FILLER_DEPTH && at(1 + depth)?.material == filler.material) depth++
        return ColumnProfile(top, filler, depth, ground(1 + depth) ?: STONE)
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

/**
 * Where the floor under water comes from: every column of the grid that is a
 * real sea, river or lake bed (water over ground, not over a cave) is a
 * source, and each column takes the stack of its nearest source. The lookup
 * point is warped by slow noise so patches of sand, gravel, clay or dirt
 * meet along organic edges instead of straight stripes from the selection edge.
 */
private class Seabed(
    private val grid: SurfaceGrid, seed: Long,
    private val stackOf: (x: Int, y: Int, z: Int) -> ColumnProfile?,
) {
    private val source = IntArray(grid.width * grid.depth) { -1 }
    private val profiles = HashMap<Int, ColumnProfile?>()
    private val warpX = OctaveNoise(seed xor WARP_SALT_X, 2)
    private val warpZ = OctaveNoise(seed xor WARP_SALT_Z, 2)

    init {
        val queue = ArrayDeque<Int>()
        for (z in 0 until grid.depth) {
            for (x in 0 until grid.width) {
                val i = z * grid.width + x
                val wet = grid.surface[i] > grid.heights[i]
                if (wet && SurfaceScanner.isTerrain(grid.type(grid.originX + x, grid.heights[i], grid.originZ + z))) {
                    source[i] = i
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
                if (source[j] < 0) {
                    source[j] = source[i]
                    queue.add(j)
                }
            }
        }
    }

    /** Floor stack for world column (x, z), or null when there is no bed anywhere in the grid. */
    fun profileAt(x: Int, z: Int): ColumnProfile? {
        val wx = (x - grid.originX + Math.round(WARP * warpX.sample(x / WARP_SCALE, z / WARP_SCALE)).toInt())
            .coerceIn(0, grid.width - 1)
        val wz = (z - grid.originZ + Math.round(WARP * warpZ.sample(x / WARP_SCALE, z / WARP_SCALE)).toInt())
            .coerceIn(0, grid.depth - 1)
        val s = source[wz * grid.width + wx]
        if (s < 0) return null
        return profiles.getOrPut(s) {
            val sx = grid.originX + s % grid.width
            val sz = grid.originZ + s / grid.width
            stackOf(sx, grid.heights[s], sz)
        }
    }

    private companion object {
        // Patch edges wander up to this many blocks, over features about WARP_SCALE wide.
        const val WARP = 6.0
        const val WARP_SCALE = 14.0
        const val WARP_SALT_X = 0x6A09E667F3BCC909L
        const val WARP_SALT_Z = 0x3C6EF372FE94F82BL
    }
}

