package dev.motopoly.world

import dev.motopoly.terrain.GenerateParams
import dev.motopoly.terrain.GeneratePlan
import dev.motopoly.terrain.TerrainGenerator
import dev.motopoly.terrain.TerrainStats

/** Everything one /mt gen run decided, ready to apply. */
data class Generation(
    val plan: GeneratePlan,
    val changes: ChangeSet,
    /** Params actually used (the edge band may have been widened for an island). */
    val params: GenerateParams,
    val water: WaterInfo,
) {
    companion object {
        /**
         * Below water the design treats the surroundings as no deeper than
         * this under the surface: new land ends in a shallow shelf and the
         * real floor drops away beyond it, instead of the whole selection
         * ramping down into a deep lake or ocean trench.
         */
        private const val SHELF_DEPTH = 4

        /** Off the main thread: reads only snapshots. */
        fun design(
            grid: SurfaceGrid, reference: SurfaceGrid?, params: GenerateParams,
            maxFillDepth: Int, copyPlants: Boolean, beachWidth: Int, fillOres: Boolean,
        ): Generation {
            val water = WaterInfo.of(grid)
            val boundary = grid.heights.copyOf()
            for (i in boundary.indices) {
                if (grid.surface[i] > grid.heights[i]) boundary[i] = maxOf(boundary[i], grid.surface[i] - SHELF_DEPTH)
            }
            val w = grid.width - 2 * grid.margin
            val d = grid.depth - 2 * grid.margin
            // Mostly water around: rise from the shelf like an island, with a ramp long enough for the climb.
            val used = if (water.island && params.baseY() != null) {
                val floor = TerrainStats.ofBand(boundary, grid.width, grid.depth, grid.margin).median().toDouble()
                params.withEdge(TerrainGenerator.islandEdge(params.baseY(), floor, params.edge(), w, d, params.maxStep()))
            } else {
                params
            }
            val plan = TerrainGenerator.plan(
                boundary, grid.width, grid.depth, grid.margin, grid.originX, grid.originZ, used, reference?.heights
            )
            val changes = GenerateJob.computeChanges(
                grid, plan, maxFillDepth, reference, copyPlants, water.level, beachWidth, params.seed(), fillOres,
            )
            return Generation(plan, changes, used, water)
        }
    }
}
