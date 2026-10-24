package dev.motopoly.world

import dev.motopoly.terrain.GenerateParams
import dev.motopoly.terrain.GeneratePlan
import dev.motopoly.terrain.TerrainGenerator

/** Everything one /mt gen run decided, ready to apply. */
data class Generation(
    val plan: GeneratePlan,
    val changes: ChangeSet,
) {
    companion object {
        /**
         * Below water the design treats the surroundings as no deeper than
         * this under the surface: the band ends in a shallow shelf and the
         * real floor drops away beyond it, instead of the ramp running down
         * into a deep lake or ocean trench.
         */
        private const val SHELF_DEPTH = 4

        private fun boundary(grid: SurfaceGrid): IntArray {
            val boundary = grid.heights.copyOf()
            for (i in boundary.indices) {
                if (grid.surface[i] > grid.heights[i]) boundary[i] = maxOf(boundary[i], grid.surface[i] - SHELF_DEPTH)
            }
            return boundary
        }

        /**
         * Off the main thread: reads only snapshots. [params]' edge is the band
         * width; [grid]'s margin must be wider than that.
         */
        fun design(
            grid: SurfaceGrid, reference: SurfaceGrid?, params: GenerateParams,
            maxFillDepth: Int, copyPlants: Boolean, beachWidth: Int, fillOres: Boolean,
        ): Generation {
            val waterLevel = WaterInfo.levelOf(grid)
            val plan = TerrainGenerator.plan(
                boundary(grid), grid.width, grid.depth, grid.margin, grid.originX, grid.originZ, params, reference?.heights
            )
            val changes = GenerateJob.computeChanges(
                grid, plan, maxFillDepth, reference, copyPlants, waterLevel, beachWidth, params.seed(), fillOres,
            )
            return Generation(plan, changes)
        }
    }
}
