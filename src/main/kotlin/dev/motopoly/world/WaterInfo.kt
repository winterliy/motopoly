package dev.motopoly.world

/** Water around a selection, read from its margin. */
object WaterInfo {
    /** Y of the top water block (median over the wet margin columns), or null when there is no water around. */
    fun levelOf(grid: SurfaceGrid): Int? {
        val m = grid.margin
        val levels = ArrayList<Int>()
        for (z in 0 until grid.depth) {
            for (x in 0 until grid.width) {
                if (x in m until grid.width - m && z in m until grid.depth - m) continue
                val i = z * grid.width + x
                if (grid.surface[i] > grid.heights[i]) levels += grid.surface[i]
            }
        }
        if (levels.isEmpty()) return null
        levels.sort()
        return levels[levels.size / 2]
    }
}
