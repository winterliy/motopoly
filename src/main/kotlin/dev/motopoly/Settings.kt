package dev.motopoly

import org.bukkit.configuration.file.FileConfiguration

data class Settings(
    val blocksPerTick: Int,
    val maxColumns: Int,
    val analysisMargin: Int,
    val undoDepth: Int,
    val maxFillDepth: Int,
    /** NaN = auto */
    val defaultRough: Double,
    val defaultScale: Double,
    val defaultFeather: Int,
    val defaultSmooth: Int,
) {
    companion object {
        fun load(config: FileConfiguration) = Settings(
            blocksPerTick = config.getInt("blocks-per-tick", 20000).coerceAtLeast(100),
            maxColumns = config.getInt("max-columns", 262144).coerceAtLeast(1),
            analysisMargin = config.getInt("analysis-margin", 8).coerceIn(1, 64),
            undoDepth = config.getInt("undo-depth", 5).coerceAtLeast(0),
            maxFillDepth = config.getInt("max-fill-depth", 64).coerceIn(1, 512),
            defaultRough = config.getString("defaults.rough", "auto")!!.let {
                if (it.equals("auto", ignoreCase = true)) Double.NaN else it.toDoubleOrNull() ?: Double.NaN
            },
            defaultScale = config.getDouble("defaults.scale", 48.0).coerceAtLeast(1.0),
            defaultFeather = config.getInt("defaults.feather", 10).coerceAtLeast(0),
            defaultSmooth = config.getInt("defaults.smooth", 2).coerceAtLeast(0),
        )
    }
}
