package dev.motopoly.world

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ProtoChunk
import net.minecraft.world.level.chunk.UpgradeData
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.NoiseChunk
import net.minecraft.world.level.levelgen.NoiseSettings
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.blending.Blender
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume
import net.minecraft.world.level.levelgen.material.rule.MaterialRule
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.slf4j.Logger
import java.lang.reflect.Method
import java.util.function.Supplier
import java.util.stream.IntStream

/**
 * What vanilla generates over a grid of columns, worked out on the spot from
 * the world's own generator and seed.
 *
 * @param heights Y of the top solid block vanilla places (the sea floor under oceans)
 * @param stacks  per column, the blocks vanilla lays from that top block down, [VanillaTerrain.STACK_DEPTH] deep
 * @param biomes  biome key at the surface, e.g. `minecraft:desert`
 * @param snowy   whether vanilla would let snow settle on the surface (cold enough there)
 */
class VanillaColumns(
    val heights: IntArray,
    val stacks: Array<Array<BlockData>>,
    val biomes: Array<String>,
    val snowy: BooleanArray,
    private val seaLevel: Int,
) {
    /** Whether vanilla's top block at column [i] is above sea level. */
    fun dry(i: Int) = heights[i] >= seaLevel
}

/**
 * Runs vanilla's own terrain steps for every chunk under the grid, in
 * throwaway chunks that never reach the world: biomes, the noise fill
 * (stone, water, aquifers) and the surface rules (grass, sand, terracotta
 * bands, gravel and coarse dirt patches…). Biomes are read through vanilla's
 * fuzzy zoom, so borders wander exactly as they do in generated terrain.
 * Structures and carvers are left out: they would need the real world's chunks.
 */
object VanillaTerrain {
    const val STACK_DEPTH = 8

    // Steps the generator keeps private; looked up once.
    private val chunkVolume: Method by lazy {
        NoiseBasedChunkGenerator::class.java
            .getDeclaredMethod("chunkVolume", ChunkAccess::class.java, NoiseSettings::class.java).apply { isAccessible = true }
    }
    private val doFill: Method by lazy {
        NoiseBasedChunkGenerator::class.java
            .getDeclaredMethod("doFill", NoiseChunk::class.java, ChunkAccess::class.java).apply { isAccessible = true }
    }
    private val buildSurface: Method by lazy {
        NoiseBasedChunkGenerator::class.java.getDeclaredMethod(
            "buildSurface", ChunkAccess::class.java, NoiseChunk::class.java, RandomState::class.java,
            BiomeManager::class.java, Set::class.java, MaterialRule::class.java, ServerLevel::class.java,
        ).apply { isAccessible = true }
    }
    private val fluidPicker by lazy {
        NoiseBasedChunkGenerator::class.java.getDeclaredField("globalFluidPicker").apply { isAccessible = true }
    }

    /**
     * Samples the columns [originX]..+[width], [originZ]..+[depth] of [world].
     * Safe off the main thread; chunks are spread over the common pool. Null
     * if the world doesn't use vanilla's noise generator or a step fails (logged).
     */
    fun sample(world: World, originX: Int, originZ: Int, width: Int, depth: Int, logger: Logger): VanillaColumns? = try {
        val level = (world as CraftWorld).handle
        val generator = level.chunkSource.generator as? NoiseBasedChunkGenerator
            ?: error("${world.name} does not use vanilla's noise generator")
        val random = level.chunkSource.randomState()
        val settings = generator.generatorSettings().value()
        val sea = generator.seaLevel
        @Suppress("UNCHECKED_CAST")
        val fluids = (fluidPicker.get(generator) as Supplier<Aquifer.FluidPicker>).get()
        val possibleBiomes = generator.biomeSource.possibleBiomes()

        val n = width * depth
        val heights = IntArray(n)
        val stacks = arrayOfNulls<Array<BlockData>>(n)
        val biomes = arrayOfNulls<String>(n)
        val snowy = BooleanArray(n)
        val minCx = originX shr 4
        val minCz = originZ shr 4
        val chunksX = ((originX + width - 1) shr 4) - minCx + 1
        val chunksZ = ((originZ + depth - 1) shr 4) - minCz + 1

        IntStream.range(0, chunksX * chunksZ).parallel().forEach { c ->
            val pos = ChunkPos(minCx + c % chunksX, minCz + c / chunksX)
            val chunk = ProtoChunk(pos, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null)
            generator.createBiomes(random, Blender.empty(), level.structureManager(), chunk).join()
            // Same zoom as a generation region: fuzzy borders over the noise biomes, which reach past the chunk edge.
            val biomeManager = BiomeManager(generator.biomeSource.createUncachedResolver(random), BiomeManager.obfuscateSeed(level.seed))
            val noiseSettings = settings.noiseSettings().clampToHeightAccessor(chunk.heightAccessorForGeneration)
            val volume = chunkVolume.invoke(null, chunk, noiseSettings) as DensityVolume
            NoiseChunk(random, null, settings, fluids, Blender.empty(), volume).use { noise ->
                doFill.invoke(generator, noise, chunk)
                buildSurface.invoke(generator, chunk, noise, random, biomeManager, possibleBiomes, settings.materialRule().value(), level)
            }

            val surfacePos = BlockPos.MutableBlockPos()
            for (lz in 0 until 16) {
                val z = pos.minBlockZ + lz - originZ
                if (z !in 0 until depth) continue
                for (lx in 0 until 16) {
                    val x = pos.minBlockX + lx - originX
                    if (x !in 0 until width) continue
                    val i = z * width + x
                    val wx = pos.minBlockX + lx
                    val wz = pos.minBlockZ + lz
                    val top = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, lx, lz)
                    heights[i] = top
                    stacks[i] = Array(STACK_DEPTH) { d -> CraftBlockData.createData(chunk.getBlockState(wx, top - d, wz)) }
                    surfacePos.set(wx, top + 1, wz)
                    val biome = biomeManager.getBiome(surfacePos)
                    biomes[i] = biome.registeredName
                    snowy[i] = biome.value().coldEnoughToSnow(surfacePos, sea)
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        VanillaColumns(heights, stacks as Array<Array<BlockData>>, biomes as Array<String>, snowy, sea)
    } catch (e: Throwable) {
        logger.warn("Could not sample vanilla terrain in {}; falling back to noise", world.name, e)
        null
    }
}
