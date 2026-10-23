package dev.motopoly.terrain;

/**
 * Inputs to {@link FlattenPlanner}.
 *
 * @param baseY        target ground level, or {@code null} to use the selection median
 * @param roughness    peak undulation in blocks, or {@code NaN} to derive it from the surrounding terrain
 * @param scale        horizontal size of the undulations in blocks
 * @param feather      width of the blend band along the selection edge; 0 means a hard edge
 * @param smoothPasses 3x3 blur passes applied after blending
 * @param seed         noise seed (use the world seed so neighbouring runs line up)
 * @param minY         lowest allowed surface Y
 * @param maxY         highest allowed surface Y
 */
public record FlattenParams(
        Integer baseY,
        double roughness,
        double scale,
        int feather,
        int smoothPasses,
        long seed,
        int minY,
        int maxY) {

    public FlattenParams {
        if (scale <= 0) throw new IllegalArgumentException("scale must be > 0");
        if (feather < 0) throw new IllegalArgumentException("feather must be >= 0");
        if (smoothPasses < 0) throw new IllegalArgumentException("smoothPasses must be >= 0");
        if (!Double.isNaN(roughness) && roughness < 0) throw new IllegalArgumentException("roughness must be >= 0");
        if (minY > maxY) throw new IllegalArgumentException("minY must be <= maxY");
    }
}
