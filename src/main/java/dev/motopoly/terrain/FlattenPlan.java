package dev.motopoly.terrain;

/**
 * Result of {@link FlattenPlanner#plan}.
 *
 * @param target    new surface Y per selection column, row-major (z * width + x)
 * @param baseY     ground level the plan was built around
 * @param roughness undulation amplitude actually used
 * @param before    statistics of the original terrain, margin included
 * @param cut       blocks removed above the new surface (terrain only)
 * @param fill      blocks added below the new surface
 */
public record FlattenPlan(int[] target, int baseY, double roughness, TerrainStats before, long cut, long fill) {
}
