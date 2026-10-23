package dev.motopoly.terrain;

/**
 * Designs brand-new ground for a selection so it reads as part of the
 * surrounding landscape.
 *
 * <p>Whatever is inside the selection now (buildings, water, holes) is ignored.
 * The new surface is built only from the terrain just outside it:
 * <ol>
 *   <li><b>Membrane.</b> The ring of columns bordering the selection is held
 *       fixed and the inside is solved as a harmonic surface (Laplace's
 *       equation, via SOR). That is the smoothest surface that touches every
 *       edge, so slopes on one side fade naturally into the other.</li>
 *   <li><b>Target level.</b> If a base Y is given, the surface eases from the
 *       membrane at the edge to that level over the {@code edge} band.</li>
 *   <li><b>Natural detail.</b> Vanilla plains are the high-erosion case of the
 *       terrain density function: little offset, just low-amplitude octave
 *       noise. That noise is added here, modulated by a slower "erosion"
 *       noise, and faded in over the edge band so the edge still lines up.</li>
 *   <li>A light blur, then rounding to whole blocks.</li>
 * </ol>
 *
 * <p>Input is the selection plus a margin on every side. The margin is never
 * changed; it supplies the edge values and the roughness analysis.
 */
public final class TerrainGenerator {
    // Octave noise rarely reaches its theoretical range; this stretches typical
    // output so `roughness` is close to the real peak deviation in blocks.
    private static final double NOISE_GAIN = 1.6;
    private static final long EROSION_SALT = 0x5DEECE66DL;
    private static final double CONVERGED = 0.005;

    private TerrainGenerator() {
    }

    /**
     * @param heights surface Y over the expanded grid, row-major; only the margin is read
     * @param fullW   expanded grid width  (selection width  + 2 * margin)
     * @param fullD   expanded grid depth  (selection depth  + 2 * margin)
     * @param margin  margin width on each side, at least 1
     * @param originX world X of the expanded grid's first column
     * @param originZ world Z of the expanded grid's first row
     */
    public static GeneratePlan plan(int[] heights, int fullW, int fullD, int margin,
                                    int originX, int originZ, GenerateParams params) {
        if (heights.length != fullW * fullD) {
            throw new IllegalArgumentException("heights must be fullW*fullD");
        }
        int w = fullW - 2 * margin;
        int d = fullD - 2 * margin;
        if (margin < 1 || w <= 0 || d <= 0) {
            throw new IllegalArgumentException("need margin >= 1 and a selection of at least 1x1");
        }

        TerrainStats around = TerrainStats.ofBand(heights, fullW, fullD, margin);
        double roughness = Double.isNaN(params.roughness()) ? autoRoughness(around) : params.roughness();

        double[] field = membrane(heights, fullW, fullD, margin, w, d);

        OctaveNoise detail = new OctaveNoise(params.seed(), 4);
        OctaveNoise erosion = new OctaveNoise(params.seed() ^ EROSION_SALT, 2);
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int fi = (z + margin) * fullW + (x + margin);
                double t = edgeWeight(x, z, w, d, params.edge());
                double h = field[fi];
                if (params.baseY() != null) {
                    h += (params.baseY() - h) * t;
                }
                int wx = originX + margin + x;
                int wz = originZ + margin + z;
                double n = clamp(detail.sample(wx / params.scale(), wz / params.scale()) * NOISE_GAIN, -1, 1);
                double e = erosion.sample(wx / (params.scale() * 4), wz / (params.scale() * 4));
                double amp = roughness * (0.35 + 0.65 * smoothstep((e + 1) * 0.5));
                field[fi] = h + amp * n * t;
            }
        }

        for (int pass = 0; pass < params.smoothPasses(); pass++) {
            field = blur(field, fullW, fullD, margin);
        }

        int[] target = new int[w * d];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int y = (int) Math.round(field[(z + margin) * fullW + (x + margin)]);
                target[z * w + x] = Math.max(params.minY(), Math.min(params.maxY(), y));
            }
        }
        return new GeneratePlan(target, roughness, around);
    }

    /**
     * Harmonic surface over the selection with the bordering ring as fixed
     * boundary. Starts from a transfinite blend of the four edges, which is
     * already close, then relaxes with successive over-relaxation.
     */
    static double[] membrane(int[] heights, int fullW, int fullD, int margin, int w, int d) {
        double[] u = new double[fullW * fullD];
        for (int i = 0; i < u.length; i++) {
            u[i] = heights[i];
        }
        int left = margin - 1;
        int right = margin + w;
        int top = margin - 1;
        int bottom = margin + d;
        for (int z = 0; z < d; z++) {
            int row = z + margin;
            double hl = heights[row * fullW + left];
            double hr = heights[row * fullW + right];
            for (int x = 0; x < w; x++) {
                int col = x + margin;
                double ht = heights[top * fullW + col];
                double hb = heights[bottom * fullW + col];
                double wl = 1.0 / (x + 1);
                double wr = 1.0 / (w - x);
                double wt = 1.0 / (z + 1);
                double wb = 1.0 / (d - z);
                u[row * fullW + col] = (hl * wl + hr * wr + ht * wt + hb * wb) / (wl + wr + wt + wb);
            }
        }

        int n = Math.max(w, d) + 1;
        double omega = 2.0 / (1.0 + Math.sin(Math.PI / n));
        int maxIterations = Math.max(50, 4 * n);
        for (int iter = 0; iter < maxIterations; iter++) {
            double maxDelta = 0;
            for (int z = margin; z < margin + d; z++) {
                for (int x = margin; x < margin + w; x++) {
                    int i = z * fullW + x;
                    double gs = (u[i - 1] + u[i + 1] + u[i - fullW] + u[i + fullW]) * 0.25;
                    double delta = omega * (gs - u[i]);
                    u[i] += delta;
                    maxDelta = Math.max(maxDelta, Math.abs(delta));
                }
            }
            if (maxDelta < CONVERGED) break;
        }
        return u;
    }

    /** Flat land stays nearly flat; rugged surroundings leave gentle bumps. */
    public static double autoRoughness(TerrainStats stats) {
        return clamp(0.4 + 0.5 * stats.roughness(), 0.4, 2.0);
    }

    /** 0 at the outside edge, 1 once {@code edge} blocks in. */
    static double edgeWeight(int x, int z, int w, int d, int edge) {
        if (edge == 0) return 1;
        int dist = Math.min(Math.min(x, z), Math.min(w - 1 - x, d - 1 - z));
        return smoothstep(Math.min(1.0, (dist + 1.0) / (edge + 1.0)));
    }

    private static double[] blur(double[] src, int fullW, int fullD, int margin) {
        double[] out = src.clone();
        for (int z = margin; z < fullD - margin; z++) {
            for (int x = margin; x < fullW - margin; x++) {
                double sum = 0;
                double weight = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        // 1-2-1 kernel: centre 4, edges 2, corners 1
                        double k = (dx == 0 ? 2 : 1) * (dz == 0 ? 2 : 1);
                        sum += src[(z + dz) * fullW + (x + dx)] * k;
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
