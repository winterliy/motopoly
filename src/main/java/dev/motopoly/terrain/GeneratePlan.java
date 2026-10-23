package dev.motopoly.terrain;

/**
 * Result of {@link TerrainGenerator#plan}.
 *
 * @param target         new surface Y per selection column, row-major (z * width + x)
 * @param roughness      undulation amplitude actually used
 * @param surroundings   statistics of the terrain around the selection
 * @param vanillaRelief  true when the detail came from vanilla-generated terrain, false for the noise fallback
 */
public record GeneratePlan(int[] target, double roughness, TerrainStats surroundings, boolean vanillaRelief) {
}
