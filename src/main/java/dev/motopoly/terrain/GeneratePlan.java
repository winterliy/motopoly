package dev.motopoly.terrain;

/**
 * Result of {@link TerrainGenerator#plan}.
 *
 * @param target         new surface Y per column of the selection plus {@code band} columns on
 *                       every side, row-major (z * (width + 2 * band) + x)
 * @param band           width of the transition band around the selection
 * @param zone           per column of {@code target}: true where it is the generating zone
 *                       (the selection minus its rounded-off corners), false in the band
 * @param roughness      undulation amplitude actually used
 * @param surroundings   statistics of the terrain around the selection
 * @param vanillaRelief  true when the detail came from vanilla-generated terrain, false for the noise fallback
 */
public record GeneratePlan(int[] target, int band, boolean[] zone, double roughness, TerrainStats surroundings, boolean vanillaRelief) {
}
