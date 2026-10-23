package dev.motopoly

import org.bukkit.configuration.file.FileConfiguration

data class Settings(
    val blocksPerTick: Int,
    val maxColumns: Int,
    val analysisMargin: Int,
    val undoDepth: Int,
    val maxFillDepth: Int,
    /** Edge smoothing width in blocks; players override it with /mt config smooth. */
    val defaultSmooth: Int,
    /** Undulation in blocks, NaN = auto; players override it with /mt config rough. */
    val defaultRough: Double,
    val scale: Double,
    /** Borrow topography, materials and plants from a same-seed twin world. */
    val referenceTerrain: Boolean,
    val copyPlants: Boolean,
) {
    companion object {
        fun load(config: FileConfiguration) = Settings(
            blocksPerTick = config.getInt("blocks-per-tick", 20000).coerceAtLeast(100),
            maxColumns = config.getInt("max-columns", 262144).coerceAtLeast(1),
            analysisMargin = config.getInt("analysis-margin", 8).coerceIn(1, 64),
            undoDepth = config.getInt("undo-depth", 5).coerceAtLeast(0),
            maxFillDepth = config.getInt("max-fill-depth", 64).coerceIn(1, 512),
            defaultSmooth = config.getInt("defaults.smooth", 10).coerceIn(0, 64),
            defaultRough = parseRough(config.getString("defaults.rough", "auto")!!) ?: Double.NaN,
            scale = config.getDouble("scale", 48.0).coerceAtLeast(1.0),
            referenceTerrain = config.getBoolean("reference-terrain", true),
            copyPlants = config.getBoolean("copy-plants", true),
        )

        /** "auto" → NaN, a non-negative number → itself, anything else → null. */
        fun parseRough(value: String): Double? =
            if (value.equals("auto", ignoreCase = true)) Double.NaN
            else value.toDoubleOrNull()?.takeIf { it >= 0 }
    }
}
