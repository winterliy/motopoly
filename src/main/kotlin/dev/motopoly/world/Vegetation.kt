package dev.motopoly.world

import dev.motopoly.terrain.OctaveNoise
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.FlowerBed

/**
 * Ground cover for new land, after what vanilla grows in each biome: grass,
 * ferns, flowers, dead bushes, petals… Two slow noises keep it from looking
 * sprinkled: one thins and thickens the cover into meadows and bare patches,
 * the other picks which flower grows, so the same flowers gather in clumps.
 * Everything depends only on position and seed, so neighbouring runs agree.
 */
class Vegetation(private val seed: Long) {
    private val patches = OctaveNoise(seed xor PATCH_SALT, 2)
    private val clumps = OctaveNoise(seed xor CLUMP_SALT, 2)

    /** Plants to stand on [ground] at (x, z) in [biome], bottom first; empty for bare ground. */
    fun plantsAt(biome: String, ground: Material, x: Int, z: Int): List<BlockData> {
        val cover = COVER[biome] ?: DEFAULT
        val density = (1.0 + PATCHINESS * patches.sample(x / PATCH_SCALE, z / PATCH_SCALE)).coerceAtLeast(0.0)
        var r = unit(hash(seed, x, z))
        for (plant in cover) {
            if (ground !in plant.soil) continue
            val chance = plant.chance * density
            if (r < chance) return plant.pick(clumps.sample(x / CLUMP_SCALE, z / CLUMP_SCALE), hash(seed + 1, x, z))
            r -= chance
        }
        return emptyList()
    }

    /** One entry of a biome's cover: [chance] per column, one of [kinds] (chosen by clump noise). */
    private class Plant(val chance: Double, val soil: Set<Material>, val kinds: List<(Long) -> List<BlockData>>) {
        fun pick(clump: Double, h: Long): List<BlockData> {
            // Mostly the clump's own kind, now and then a neighbour's, so clumps blend at their edges.
            val base = ((clump + 1) / 2 * kinds.size).toInt().coerceIn(0, kinds.size - 1)
            val k = if (unit(h ushr 7) < 0.2) (base + 1) % kinds.size else base
            return kinds[k](h)
        }
    }

    private companion object {
        const val PATCH_SALT = 0x1B873593L
        const val CLUMP_SALT = 0x5BD1E995L
        // Meadows and bare patches about this wide; flower clumps smaller.
        const val PATCH_SCALE = 18.0
        const val CLUMP_SCALE = 9.0
        const val PATCHINESS = 0.9

        val SOIL = setOf(Material.GRASS_BLOCK, Material.PODZOL, Material.DIRT, Material.COARSE_DIRT, Material.MOSS_BLOCK)
        val SAND = setOf(Material.SAND, Material.RED_SAND, Material.TERRACOTTA) + Tag.TERRACOTTA.values
        val MYCELIUM = setOf(Material.MYCELIUM)

        fun single(m: Material): (Long) -> List<BlockData> {
            val data = m.createBlockData()
            return { listOf(data) }
        }

        fun tall(m: Material): (Long) -> List<BlockData> {
            val lower = (m.createBlockData() as Bisected).apply { half = Bisected.Half.BOTTOM }
            val upper = (m.createBlockData() as Bisected).apply { half = Bisected.Half.TOP }
            return { listOf(lower, upper) }
        }

        /** Petal-like beds: one to four pieces, facing any way. */
        fun bed(m: Material): (Long) -> List<BlockData> = { h ->
            listOf((m.createBlockData() as FlowerBed).apply {
                flowerAmount = 1 + (h and 3).toInt()
                facing = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)[((h ushr 2) and 3).toInt()]
            })
        }

        fun plant(chance: Double, vararg kinds: (Long) -> List<BlockData>, soil: Set<Material> = SOIL) =
            Plant(chance, soil, kinds.toList())

        val grass = single(Material.SHORT_GRASS)
        val tallGrass = tall(Material.TALL_GRASS)
        val fern = single(Material.FERN)
        val largeFern = tall(Material.LARGE_FERN)
        val plainsFlowers = arrayOf(
            single(Material.DANDELION), single(Material.POPPY), single(Material.AZURE_BLUET), single(Material.OXEYE_DAISY),
            single(Material.CORNFLOWER), single(Material.RED_TULIP), single(Material.ORANGE_TULIP),
            single(Material.WHITE_TULIP), single(Material.PINK_TULIP),
        )
        val forestFlowers = arrayOf(single(Material.DANDELION), single(Material.POPPY), single(Material.LILY_OF_THE_VALLEY))
        val forestShrubs = arrayOf(tall(Material.LILAC), tall(Material.ROSE_BUSH), tall(Material.PEONY))
        val mushrooms = arrayOf(single(Material.BROWN_MUSHROOM), single(Material.RED_MUSHROOM))

        val DEFAULT = listOf(plant(0.18, grass), plant(0.01, *plainsFlowers))
        val PLAINS = listOf(plant(0.30, grass), plant(0.03, tallGrass), plant(0.02, *plainsFlowers))
        val FOREST = listOf(plant(0.20, grass), plant(0.015, *forestFlowers), plant(0.006, *forestShrubs))
        val BIRCH = listOf(plant(0.18, grass), plant(0.05, bed(Material.WILDFLOWERS)), plant(0.01, *forestFlowers))
        val TAIGA = listOf(
            plant(0.12, grass), plant(0.15, fern), plant(0.03, largeFern), plant(0.004, single(Material.SWEET_BERRY_BUSH)),
        )
        val SAVANNA = listOf(plant(0.45, grass), plant(0.10, tallGrass))
        val JUNGLE = listOf(plant(0.35, grass), plant(0.15, fern), plant(0.05, largeFern))
        val DRY = listOf(
            plant(0.02, single(Material.SHORT_DRY_GRASS), single(Material.TALL_DRY_GRASS), soil = SAND),
            plant(0.008, single(Material.DEAD_BUSH), soil = SAND),
        )

        val COVER: Map<String, List<Plant>> = mapOf(
            "minecraft:plains" to PLAINS,
            "minecraft:sunflower_plains" to PLAINS + plant(0.02, tall(Material.SUNFLOWER)),
            "minecraft:meadow" to listOf(plant(0.45, grass), plant(0.08, tallGrass), plant(0.06, *plainsFlowers, single(Material.ALLIUM))),
            "minecraft:flower_forest" to listOf(
                plant(0.10, grass),
                plant(0.12, *plainsFlowers, single(Material.ALLIUM), single(Material.LILY_OF_THE_VALLEY)),
                plant(0.01, *forestShrubs),
            ),
            "minecraft:forest" to FOREST,
            "minecraft:dark_forest" to FOREST + plant(0.01, *mushrooms),
            "minecraft:dappled_forest" to listOf(
                plant(0.20, grass), plant(0.02, single(Material.RED_SHRUB)), plant(0.005, single(Material.BROWN_MUSHROOM)),
            ),
            "minecraft:pale_garden" to listOf(plant(0.10, grass), plant(0.01, single(Material.CLOSED_EYEBLOSSOM))),
            "minecraft:birch_forest" to BIRCH,
            "minecraft:old_growth_birch_forest" to BIRCH,
            "minecraft:taiga" to TAIGA,
            "minecraft:old_growth_pine_taiga" to TAIGA,
            "minecraft:old_growth_spruce_taiga" to TAIGA,
            "minecraft:savanna" to SAVANNA,
            "minecraft:savanna_plateau" to SAVANNA,
            "minecraft:windswept_savanna" to SAVANNA,
            "minecraft:jungle" to JUNGLE,
            "minecraft:sparse_jungle" to JUNGLE,
            "minecraft:bamboo_jungle" to JUNGLE,
            "minecraft:swamp" to listOf(plant(0.15, grass), plant(0.01, single(Material.BLUE_ORCHID))),
            "minecraft:mangrove_swamp" to listOf(plant(0.05, grass)),
            "minecraft:cherry_grove" to listOf(plant(0.25, grass), plant(0.15, bed(Material.PINK_PETALS))),
            "minecraft:windswept_hills" to listOf(plant(0.15, grass)),
            "minecraft:windswept_forest" to listOf(plant(0.15, grass)),
            "minecraft:windswept_gravelly_hills" to listOf(plant(0.10, grass)),
            "minecraft:desert" to DRY,
            "minecraft:badlands" to DRY,
            "minecraft:eroded_badlands" to DRY,
            "minecraft:wooded_badlands" to DRY + plant(0.10, grass),
            "minecraft:mushroom_fields" to listOf(plant(0.01, *mushrooms, soil = MYCELIUM)),
            // Bare by nature.
            "minecraft:beach" to emptyList(),
            "minecraft:snowy_beach" to emptyList(),
            "minecraft:stony_shore" to emptyList(),
            "minecraft:stony_peaks" to emptyList(),
            "minecraft:jagged_peaks" to emptyList(),
            "minecraft:frozen_peaks" to emptyList(),
            "minecraft:snowy_slopes" to emptyList(),
            "minecraft:ice_spikes" to emptyList(),
            "minecraft:snowy_plains" to emptyList(),
        )

        fun hash(seed: Long, x: Int, z: Int): Long {
            var h = seed xor (x.toLong() * -0x61c8864680b583ebL) xor (z.toLong() * -0x3d4d51c2d82b14b1L)
            h = (h xor (h ushr 33)) * -0xae502812aa7333L
            h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
            return h xor (h ushr 33)
        }

        fun unit(h: Long) = (h ushr 11).toDouble() / (1L shl 53)
    }
}
