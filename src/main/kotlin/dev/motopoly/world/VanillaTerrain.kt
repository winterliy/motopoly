package dev.motopoly.world

import net.minecraft.core.QuartPos
import net.minecraft.world.level.levelgen.Heightmap
import org.bukkit.World
import org.bukkit.craftbukkit.CraftWorld
import org.slf4j.Logger
import java.util.stream.IntStream

/**
 * What vanilla generates over a grid of columns, computed on the spot from the
 * world's own generator and seed: no chunks are built and nothing is loaded.
 *
 * @param heights Y of the top solid block vanilla would place (the sea floor under oceans)
 * @param dry     true where that top block is above sea level
 * @param biomes  biome key at the surface, e.g. `minecraft:desert`
 */
class VanillaColumns(val heights: IntArray, val dry: BooleanArray, val biomes: Array<String>)

object VanillaTerrain {
    /**
     * Samples vanilla terrain for the columns [originX]..+[width], [originZ]..+[depth]
     * of [world]. Safe off the main thread; rows are spread over the common pool.
     * Null if the world's generator can't be asked (logged once per call).
     */
    fun sample(world: World, originX: Int, originZ: Int, width: Int, depth: Int, logger: Logger): VanillaColumns? = try {
        val level = (world as CraftWorld).handle
        val source = level.chunkSource
        val generator = source.generator
        val random = source.randomState()
        val sea = generator.seaLevel
        val heights = IntArray(width * depth)
        val dry = BooleanArray(width * depth)
        val biomes = arrayOfNulls<String>(width * depth)
        IntStream.range(0, depth).parallel().forEach { z ->
            // A resolver keeps per-thread state, so each row gets its own.
            val resolver = generator.biomeSource.createUncachedResolver(random)
            for (x in 0 until width) {
                val wx = originX + x
                val wz = originZ + z
                // The heightmap is the first free Y above the column.
                val top = generator.getBaseHeight(wx, wz, Heightmap.Types.OCEAN_FLOOR_WG, level, random) - 1
                val i = z * width + x
                heights[i] = top
                dry[i] = top >= sea
                biomes[i] = resolver.getNoiseBiome(QuartPos.fromBlock(wx), QuartPos.fromBlock(top), QuartPos.fromBlock(wz))
                    .registeredName
            }
        }
        @Suppress("UNCHECKED_CAST")
        VanillaColumns(heights, dry, biomes as Array<String>)
    } catch (e: Throwable) {
        logger.warn("Could not sample vanilla terrain in {}; falling back to noise", world.name, e)
        null
    }
}
