package dev.motopoly.world

/**
 * Water around a selection, read from its margin.
 *
 * @param level Y of the top water block, or null when there is no water around
 * @param island true when most of the surroundings are water: new land rises like an island
 */
data class WaterInfo(val level: Int?, val island: Boolean) {
    companion object {
        private const val ISLAND_SHARE = 0.5

        fun of(grid: SurfaceGrid): WaterInfo {
            val m = grid.margin
            val levels = ArrayList<Int>()
            var total = 0
            for (z in 0 until grid.depth) {
                for (x in 0 until grid.width) {
                    if (x in m until grid.width - m && z in m until grid.depth - m) continue
                    total++
                    val i = z * grid.width + x
                    if (grid.surface[i] > grid.heights[i]) levels += grid.surface[i]
                }
            }
            if (levels.isEmpty()) return WaterInfo(null, false)
            levels.sort()
            return WaterInfo(levels[levels.size / 2], levels.size > total * ISLAND_SHARE)
        }
    }
}
