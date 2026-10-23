package dev.motopoly.terrain;

/**
 * Turns a surface heightmap into a "natural flat" one.
 *
 * <p>Vanilla terrain gets its shape from density functions driven by
 * continentalness, erosion and peaks-and-valleys noise. Plains are the
 * high-erosion case: the terrain offset barely moves and what is left is a
 * low-amplitude octave noise. This planner rebuilds exactly that case:
 * <ol>
 *   <li>pick a base level (median of the selection, i.e. balanced cut/fill);</li>
 *   <li>add octave Perlin undulation, modulated by a slower "erosion" noise so
 *       some patches are flatter than others, like vanilla plains;</li>
 *   <li>blend toward the original surface across a feather band so the
 *       selection meets the surrounding terrain without cliffs;</li>
 *   <li>blur a few times and round to whole blocks.</li>
 * </ol>
 *
 * <p>Input is the selection plus a margin on every side; the margin is never
 * changed, it is only read for analysis and edge continuity.
 */
public final class FlattenPlanner {
    // Octave noise rarely reaches its theoretical range; this stretches typical
    // output so `roughness` is close to the real peak deviation in blocks.
    private static final double NOISE_GAIN = 1.6;
    private static final long EROSION_SALT = 0x5DEECE66DL;

    private FlattenPlanner() {
    }

    /**
     * @param heights  original surface Y over the expanded grid, row-major
     * @param fullW    expanded grid width  (selection width  + 2 * margin)
     * @param fullD    expanded grid depth  (selection depth  + 2 * margin)
     * @param margin   margin width on each side
     * @param originX  world X of the expanded grid's first column
     * @param originZ  world Z of the expanded grid's first row
     */
    public static FlattenPlan plan(int[] heights, int fullW, int fullD, int margin,
                                   int originX, int originZ, FlattenParams params) {
        if (heights.length != fullW * fullD) {
            throw new IllegalArgumentException("heights must be fullW*fullD");
        }
        int w = fullW - 2 * margin;
        int d = fullD - 2 * margin;
        if (margin < 0 || w <= 0 || d <= 0) {
            throw new IllegalArgumentException("selection must be at least 1x1 inside the margin");
        }

        TerrainStats all = TerrainStats.of(heights, fullW, fullD);
        int[] inner = new int[w * d];
        for (int z = 0; z < d; z++) {
            System.arraycopy(heights, (z + margin) * fullW + margin, inner, z * w, w);
        }
        TerrainStats innerStats = TerrainStats.of(inner, w, d);

        int base = params.baseY() != null ? params.baseY() : innerStats.median();
        double roughness = Double.isNaN(params.roughness()) ? autoRoughness(all) : params.roughness();

        OctaveNoise detail = new OctaveNoise(params.seed(), 4);
        OctaveNoise erosion = new OctaveNoise(params.seed() ^ EROSION_SALT, 2);

        double[] field = new double[fullW * fullD];
        for (int i = 0; i < field.length; i++) {
            field[i] = heights[i];
        }

        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int wx = originX + margin + x;
                int wz = originZ + margin + z;
                double n = clamp(detail.sample(wx / params.scale(), wz / params.scale()) * NOISE_GAIN, -1, 1);
                double e = erosion.sample(wx / (params.scale() * 4), wz / (params.scale() * 4));
                double amp = roughness * (0.35 + 0.65 * smoothstep((e + 1) * 0.5));
                double target = base + amp * n;

                int fi = (z + margin) * fullW + (x + margin);
                double t = blendWeight(x, z, w, d, params.feather());
                field[fi] = heights[fi] + (target - heights[fi]) * t;
            }
        }

        // With feather 0 the user wants a crisp edge, so don't let the blur
        // reach out into the untouched margin.
        boolean blurIntoMargin = params.feather() > 0;
        for (int pass = 0; pass < params.smoothPasses(); pass++) {
            field = blur(field, fullW, fullD, margin, blurIntoMargin);
        }

        int[] target = new int[w * d];
        long cut = 0;
        long fill = 0;
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int fi = (z + margin) * fullW + (x + margin);
                int y = (int) Math.round(field[fi]);
                y = Math.max(params.minY(), Math.min(params.maxY(), y));
                target[z * w + x] = y;
                int diff = y - heights[fi];
                if (diff > 0) fill += diff;
                else cut -= diff;
            }
        }
        return new FlattenPlan(target, base, roughness, all, cut, fill);
    }

    /**
     * Keeps a bit of the surroundings' character: flat land stays nearly flat,
     * flattening inside rugged terrain leaves gentle bumps so it doesn't look pasted in.
     */
    public static double autoRoughness(TerrainStats stats) {
        return clamp(0.4 + 0.5 * stats.roughness(), 0.4, 2.0);
    }

    /** 0 just outside the selection, 1 once {@code feather} blocks in. */
    static double blendWeight(int x, int z, int w, int d, int feather) {
        if (feather == 0) return 1;
        int dist = Math.min(Math.min(x, z), Math.min(w - 1 - x, d - 1 - z));
        return smoothstep(Math.min(1.0, (dist + 1.0) / (feather + 1.0)));
    }

    private static double[] blur(double[] src, int fullW, int fullD, int margin, boolean useMargin) {
        double[] out = src.clone();
        int lo = useMargin ? 0 : margin;
        int hiX = useMargin ? fullW - 1 : fullW - 1 - margin;
        int hiZ = useMargin ? fullD - 1 : fullD - 1 - margin;
        for (int z = margin; z < fullD - margin; z++) {
            for (int x = margin; x < fullW - margin; x++) {
                double sum = 0;
                double weight = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int zz = z + dz;
                    if (zz < lo || zz > hiZ) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < lo || xx > hiX) continue;
                        // 1-2-1 kernel: centre 4, edges 2, corners 1
                        double k = (dx == 0 ? 2 : 1) * (dz == 0 ? 2 : 1);
                        sum += src[zz * fullW + xx] * k;
                        weight += k;
                    }
                }
                out[z * fullW + x] = sum / weight;
            }
        }
        return out;
    }

    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
