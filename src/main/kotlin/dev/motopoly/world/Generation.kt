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
         * Off the main thread: reads only snapshots. [params]' edge is the band
         * width; [grid]'s margin must be wider than that.
         */
        fun design(
            grid: SurfaceGrid, vanilla: VanillaColumns?, params: GenerateParams,
            maxFillDepth: Int, copyPlants: Boolean, beachWidth: Int, fillOres: Boolean,
        ): Generation {
            val waterLevel = WaterInfo.levelOf(grid)
            // Under water the band meets the real floor, just like on land: the zone is pinned,
            // so a deep sea nearby only lengthens the slope down to it instead of dragging the zone along.
            val plan = TerrainGenerator.plan(
                grid.heights, grid.width, grid.depth, grid.margin, grid.originX, grid.originZ, params, vanilla?.heights
            )
            val changes = GenerateJob.computeChanges(
                grid, plan, maxFillDepth, vanilla, copyPlants, waterLevel, beachWidth, params.seed(), fillOres,
            )
            return Generation(plan, changes)
        }
    }
}
