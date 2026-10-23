package dev.motopoly.terrain;

/**
 * Inputs to {@link TerrainGenerator}.
 *
 * @param baseY        ground level to aim for inside the selection, or {@code null}
 *                     to follow the surrounding terrain only
 * @param roughness    peak undulation in blocks, or {@code NaN} to derive it from the surroundings
 * @param scale        horizontal size of the undulations in blocks
 * @param edge         width of the transition band along the selection edge, in blocks
 * @param smoothPasses 3x3 blur passes applied at the end
 * @param seed         noise seed (use the world seed so neighbouring runs line up)
 * @param minY         lowest allowed surface Y
 * @param maxY         highest allowed surface Y
 */
public record GenerateParams(
        Integer baseY,
        double roughness,
        double scale,
        int edge,
        int smoothPasses,
        long seed,
        int minY,
        int maxY) {

    public GenerateParams {
        if (scale <= 0) throw new IllegalArgumentException("scale must be > 0");
        if (edge < 0) throw new IllegalArgumentException("edge must be >= 0");
        if (smoothPasses < 0) throw new IllegalArgumentException("smoothPasses must be >= 0");
        if (!Double.isNaN(roughness) && roughness < 0) throw new IllegalArgumentException("roughness must be >= 0");
        if (minY > maxY) throw new IllegalArgumentException("minY must be <= maxY");
    }
}
