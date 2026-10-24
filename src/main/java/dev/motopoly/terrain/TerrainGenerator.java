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
 *       membrane at the edge to that level over the {@code edge} band. With a
 *       height range instead, the level itself rolls between its two ends,
 *       following the large shapes of the vanilla terrain there (or slow
 *       noise), and the ground inside never leaves the range.</li>
 *   <li><b>Natural detail.</b> Preferably the real thing: the heightmap the
 *       vanilla generator produces for these very coordinates, high-pass
 *       filtered so only the small bumps remain (no mountains or valleys).
 *       Without a reference, a stand-in: vanilla plains are the high-erosion
 *       case of the density function, i.e. low-amplitude octave noise, so
 *       that noise is used, modulated by a slower "erosion" noise. Either way
 *       the detail is pasted with Poisson seamless cloning, so it reaches the
 *       edge and still lines up with the surroundings.</li>
 *   <li>A light blur, thermal erosion to cap slopes, then rounding to whole blocks.
 *       Inside the edge band the ground is pinned at the target level: the
 *       slope limit shapes the ramp around it and never lowers the plateau.
 *       If the band is too narrow for the climb, the ramp gets steeper.</li>
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
    private static final long RANGE_SALT = 0x2545F4914F6CDD1DL;
    // Large vanilla shapes flatter than this inside the selection can't shape a height range.
    private static final double MIN_SHAPE_SPAN = 1.0;
    private static final double CONVERGED = 0.005;
    // Vanilla relief keeps its natural size under rough=auto, within these bounds.
    private static final double AUTO_RELIEF_MIN = 0.4;
    private static final double AUTO_RELIEF_MAX = 2.5;
    // 5x5 median: removes holes and spikes up to two blocks wide, keeps real slopes.
    private static final int DESPIKE_RADIUS = 2;
    // A pinned plateau this far outside the ring's cones still rounds to a one-block ramp.
    private static final double FIT_SLACK = 0.5;

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
        return plan(heights, fullW, fullD, margin, originX, originZ, params, null);
    }

    /**
     * @param reference vanilla-generated surface Y over the same expanded grid,
     *                  used as the source of natural detail; {@code null} falls back to noise
     */
    public static GeneratePlan plan(int[] heights, int fullW, int fullD, int margin,
                                    int originX, int originZ, GenerateParams params, int[] reference) {
        if (heights.length != fullW * fullD) {
            throw new IllegalArgumentException("heights must be fullW*fullD");
        }
        if (reference != null && reference.length != fullW * fullD) {
            throw new IllegalArgumentException("reference must be fullW*fullD");
        }
        int w = fullW - 2 * margin;
        int d = fullD - 2 * margin;
        if (margin < 1 || w <= 0 || d <= 0) {
            throw new IllegalArgumentException("need margin >= 1 and a selection of at least 1x1");
        }
        // The innermost columns always get the full target level, even when the band is wider than the selection allows.
        int edge = Math.min(params.edge(), (Math.min(w, d) - 1) / 2);
        // A ravine or cave mouth just outside would otherwise drag the new ground down into it.
        heights = despikeMargin(heights, fullW, fullD, margin, DESPIKE_RADIUS);

        TerrainStats around = TerrainStats.ofBand(heights, fullW, fullD, margin);

        // Natural detail over the whole expanded grid (the ring is needed for the seam fix below).
        double[] detail = new double[fullW * fullD];
        double roughness;
        boolean vanilla = false;
        double[] relief = reference == null ? null : relief(reference, fullW, fullD, reliefRadius(params.scale()));
        double reliefSize = relief == null ? 0 : innerPercentile95(relief, fullW, fullD, margin);
        if (relief != null && reliefSize > 1e-6) {
            vanilla = true;
            roughness = Double.isNaN(params.roughness())
                    ? clamp(reliefSize, AUTO_RELIEF_MIN, AUTO_RELIEF_MAX) : params.roughness();
            for (int i = 0; i < detail.length; i++) {
                detail[i] = roughness * clamp(relief[i] / reliefSize, -1.5, 1.5);
            }
        } else {
            roughness = Double.isNaN(params.roughness()) ? autoRoughness(around) : params.roughness();
            OctaveNoise noise = new OctaveNoise(params.seed(), 4);
            OctaveNoise erosion = new OctaveNoise(params.seed() ^ EROSION_SALT, 2);
            for (int z = 0; z < fullD; z++) {
                for (int x = 0; x < fullW; x++) {
                    int wx = originX + x;
                    int wz = originZ + z;
                    double n = clamp(noise.sample(wx / params.scale(), wz / params.scale()) * NOISE_GAIN, -1, 1);
                    double e = erosion.sample(wx / (params.scale() * 4), wz / (params.scale() * 4));
                    detail[z * fullW + x] = roughness * (0.35 + 0.65 * smoothstep((e + 1) * 0.5)) * n;
                }
            }
        }

        double[] boundary = new double[fullW * fullD];
        for (int i = 0; i < boundary.length; i++) boundary[i] = heights[i];
        double[] field = membrane(boundary, fullW, fullD, margin, w, d);
        double[] levels = params.topY() == null ? null : rangeLevels(params.baseY(), params.topY(),
                reference, fullW, fullD, margin, originX, originZ, params.scale(), params.seed());
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int fi = (z + margin) * fullW + (x + margin);
                if (params.baseY() != null) {
                    double level = levels == null ? params.baseY() : levels[z * w + x];
                    double t = edgeWeight(x, z, w, d, edge);
                    field[fi] += (level - field[fi]) * t;
                }
            }
        }

        double[] base = field.clone();
        // Poisson seamless cloning (Pérez et al. 2003): paste the detail at full
        // strength right up to the edge, then add the harmonic correction that
        // cancels it on the boundary ring. The seam vanishes while the texture
        // keeps its gradients, instead of fading out across the edge band.
        double[] seam = new double[fullW * fullD];
        for (int i = 0; i < seam.length; i++) seam[i] = -detail[i];
        double[] correction = membrane(seam, fullW, fullD, margin, w, d);
        for (int z = margin; z < margin + d; z++) {
            for (int x = margin; x < margin + w; x++) {
                int fi = z * fullW + x;
                field[fi] += detail[fi] + correction[fi];
            }
        }

        double[] blurredBase = base;
        for (int pass = 0; pass < params.smoothPasses(); pass++) {
            field = blur(field, fullW, fullD, margin);
            blurredBase = blur(blurredBase, fullW, fullD, margin);
        }
        if (params.baseY() != null) {
            // Blurring would pull the plateau's rim toward the ramp: the level stays, only the bumps are softened.
            for (int z = edge; z < d - edge; z++) {
                for (int x = edge; x < w - edge; x++) {
                    int fi = (z + margin) * fullW + (x + margin);
                    field[fi] += base[fi] - blurredBase[fi];
                }
            }
        }
        if (params.topY() != null) {
            // Bumps may not poke out of the range; the edge band still eases into the surroundings.
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    int fi = (z + margin) * fullW + (x + margin);
                    double v = field[fi];
                    field[fi] += (clamp(v, params.baseY(), params.topY()) - v) * edgeWeight(x, z, w, d, edge);
                }
            }
        }
        if (params.maxStep() > 0) {
            boolean[] pinned = null;
            if (params.baseY() != null) {
                pinned = new boolean[fullW * fullD];
                for (int z = 0; z < d; z++) {
                    for (int x = 0; x < w; x++) {
                        int dist = Math.min(Math.min(x, z), Math.min(w - 1 - x, d - 1 - z));
                        pinned[(z + margin) * fullW + (x + margin)] = dist >= edge;
                    }
                }
            }
            thermalErosion(field, fullW, fullD, margin, params.maxStep(), pinned);
        }

        int[] target = new int[w * d];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int y = (int) Math.round(field[(z + margin) * fullW + (x + margin)]);
                target[z * w + x] = Math.max(params.minY(), Math.min(params.maxY(), y));
            }
        }
        return new GeneratePlan(target, roughness, around, vanilla);
    }

    /**
     * Median filter over the margin only (the selection's own cells are
     * neither changed nor read): narrow ravines, cave mouths, single pillars
     * and the like stop counting as the surroundings' ground level.
     */
    static int[] despikeMargin(int[] heights, int fullW, int fullD, int margin, int radius) {
        int[] out = heights.clone();
        int[] window = new int[(2 * radius + 1) * (2 * radius + 1)];
        for (int z = 0; z < fullD; z++) {
            for (int x = 0; x < fullW; x++) {
                if (isInner(x, z, fullW, fullD, margin)) continue;
                int n = 0;
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int xx = x + dx;
                        int zz = z + dz;
                        if (xx < 0 || zz < 0 || xx >= fullW || zz >= fullD || isInner(xx, zz, fullW, fullD, margin)) {
                            continue;
                        }
                        window[n++] = heights[zz * fullW + xx];
                    }
                }
                java.util.Arrays.sort(window, 0, n);
                out[z * fullW + x] = window[n / 2];
            }
        }
        return out;
    }

    private static boolean isInner(int x, int z, int fullW, int fullD, int margin) {
        return x >= margin && x < fullW - margin && z >= margin && z < fullD - margin;
    }

    /** Features wider than about this many blocks count as hills, not bumps. */
    static int reliefRadius(double scale) {
        return (int) Math.max(4, Math.min(24, Math.round(scale / 4)));
    }

    /**
     * High-pass filter: the heightmap minus a blurred copy of itself. What is
     * left are the small natural bumps; mountains, valleys and slopes cancel out.
     */
    static double[] relief(int[] heights, int width, int depth, int radius) {
        double[] smooth = lowPass(heights, width, depth, radius);
        double[] out = new double[heights.length];
        for (int i = 0; i < out.length; i++) out[i] = heights[i] - smooth[i];
        return out;
    }

    /** Blurred heightmap: the hills and valleys without the small bumps. */
    static double[] lowPass(int[] heights, int width, int depth, int radius) {
        double[] smooth = new double[heights.length];
        for (int i = 0; i < heights.length; i++) smooth[i] = heights[i];
        // Three box blurs approximate a Gaussian.
        for (int pass = 0; pass < 3; pass++) {
            smooth = boxBlur(smooth, width, depth, radius, true);
            smooth = boxBlur(smooth, width, depth, radius, false);
        }
        return smooth;
    }

    /**
     * Ground level per selection column for a height range, row-major over the
     * selection: the large vanilla shapes at these coordinates (or slow noise
     * when there are none) stretched so the lowest spot sits at {@code lo} and
     * the highest at {@code hi}.
     */
    static double[] rangeLevels(int lo, int hi, int[] reference, int fullW, int fullD, int margin,
                                int originX, int originZ, double scale, long seed) {
        int w = fullW - 2 * margin;
        int d = fullD - 2 * margin;
        double[] shape = new double[w * d];
        if (hi == lo) {
            java.util.Arrays.fill(shape, lo);
            return shape;
        }
        double[] hills = reference == null ? null : lowPass(reference, fullW, fullD, reliefRadius(scale));
        if (hills != null) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) shape[z * w + x] = hills[(z + margin) * fullW + (x + margin)];
            }
        }
        if (hills == null || span(shape) < MIN_SHAPE_SPAN) {
            OctaveNoise noise = new OctaveNoise(seed ^ RANGE_SALT, 3);
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    shape[z * w + x] = noise.sample((originX + margin + x) / scale, (originZ + margin + z) / scale);
                }
            }
        }
        double min = Double.POSITIVE_INFINITY;
        for (double v : shape) min = Math.min(min, v);
        double spread = span(shape);
        for (int i = 0; i < shape.length; i++) {
            double f = spread > 1e-9 ? (shape[i] - min) / spread : 0.5;
            shape[i] = lo + (hi - lo) * f;
        }
        return shape;
    }

    private static double span(double[] values) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return max - min;
    }

    private static double[] boxBlur(double[] src, int width, int depth, int radius, boolean horizontal) {
        double[] out = new double[src.length];
        int lines = horizontal ? depth : width;
        int len = horizontal ? width : depth;
        for (int line = 0; line < lines; line++) {
            double sum = 0;
            // Running sum with clamped edges.
            for (int k = -radius; k <= radius; k++) sum += src[at(line, clampIndex(k, len), width, horizontal)];
            for (int i = 0; i < len; i++) {
                out[at(line, i, width, horizontal)] = sum / (2 * radius + 1);
                sum += src[at(line, clampIndex(i + radius + 1, len), width, horizontal)];
                sum -= src[at(line, clampIndex(i - radius, len), width, horizontal)];
            }
        }
        return out;
    }

    private static int at(int line, int i, int width, boolean horizontal) {
        return horizontal ? line * width + i : i * width + line;
    }

    private static int clampIndex(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }

    /** 95th percentile of |values| over the selection cells: the relief's typical peak size. */
    static double innerPercentile95(double[] values, int fullW, int fullD, int margin) {
        int w = fullW - 2 * margin;
        int d = fullD - 2 * margin;
        double[] abs = new double[w * d];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                abs[z * w + x] = Math.abs(values[(z + margin) * fullW + (x + margin)]);
            }
        }
        java.util.Arrays.sort(abs);
        return abs[Math.min(abs.length - 1, (int) Math.floor(abs.length * 0.95))];
    }

    /**
     * Harmonic surface over the selection with the bordering ring as fixed
     * boundary. Starts from a transfinite blend of the four edges, which is
     * already close, then relaxes with successive over-relaxation.
     */
    static double[] membrane(double[] heights, int fullW, int fullD, int margin, int w, int d) {
        double[] u = heights.clone();
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

    /**
     * The end state of thermal erosion, solved directly instead of simulated:
     * no two neighbouring columns may differ by more than the talus step.
     * <ol>
     *   <li>The fixed ring bounds every cell to {@code ring ± talus·distance}
     *       (cones grown inward from the boundary), so pits next to high
     *       ground and plateaus above low ground both get a ramp.</li>
     *   <li>Remaining cliffs inside are cut down to the largest slope-limited
     *       surface below them (a min-plus distance transform).</li>
     * </ol>
     * Each step is two raster sweeps (exact for 4-neighbour distance). A little
     * slack keeps rounded heights within one block of each other.
     *
     * <p>{@code pinned} cells (may be {@code null}) keep their height and act
     * as fixed sources like the ring. When the gap between them and the ring
     * is too short for the talus, the talus is relaxed until the climb fits.
     */
    static void thermalErosion(double[] h, int fullW, int fullD, int margin, double maxStep, boolean[] pinned) {
        double talus = Math.max(0.05, maxStep - 0.05);
        int w = fullW - 2 * margin;
        int d = fullD - 2 * margin;
        double[] upper = new double[h.length];
        double[] lower = new double[h.length];
        if (pinned != null) {
            // Judged at the full step: the slack below the talus is only there for rounding.
            for (int tries = 0; tries < 60 && !pinnedFit(h, upper, lower, fullW, margin, w, d, talus + 0.05, pinned); tries++) {
                talus *= 1.1;
            }
        }
        bounds(h, upper, lower, fullW, margin, w, d, pinned);
        sweep(upper, fullW, margin, w, d, talus, true, pinned);
        sweep(lower, fullW, margin, w, d, -talus, false, pinned);

        for (int round = 0; round < 3; round++) {
            for (int z = margin; z < margin + d; z++) {
                for (int x = margin; x < margin + w; x++) {
                    int i = z * fullW + x;
                    if (pinned != null && pinned[i]) continue;
                    double lo = lower[i];
                    double hi = upper[i];
                    // A boundary steeper than the talus leaves no valid range: split the difference.
                    h[i] = lo > hi ? (lo + hi) / 2 : clamp(h[i], lo, hi);
                }
            }
            // Shaving bumps inside the plateau is fine; its level is already bounded by the ramp.
            sweep(h, fullW, margin, w, d, talus, true, null);
        }
    }

    /** Seeds the cone bounds: fixed heights on the ring (and pinned cells), open everywhere else. */
    private static void bounds(double[] h, double[] upper, double[] lower, int fullW, int margin, int w, int d,
                               boolean[] pinned) {
        for (int z = margin - 1; z <= margin + d; z++) {
            for (int x = margin - 1; x <= margin + w; x++) {
                int i = z * fullW + x;
                boolean inner = x >= margin && x < margin + w && z >= margin && z < margin + d;
                boolean open = inner && (pinned == null || !pinned[i]);
                upper[i] = open ? Double.POSITIVE_INFINITY : h[i];
                lower[i] = open ? Double.NEGATIVE_INFINITY : h[i];
            }
        }
    }

    /** Whether every pinned cell lies within the ring's cones at this talus. */
    private static boolean pinnedFit(double[] h, double[] upper, double[] lower, int fullW, int margin, int w, int d,
                                     double talus, boolean[] pinned) {
        bounds(h, upper, lower, fullW, margin, w, d, null);
        sweep(upper, fullW, margin, w, d, talus, true, null);
        sweep(lower, fullW, margin, w, d, -talus, false, null);
        for (int z = margin; z < margin + d; z++) {
            for (int x = margin; x < margin + w; x++) {
                int i = z * fullW + x;
                if (pinned[i] && (h[i] > upper[i] + FIT_SLACK || h[i] < lower[i] - FIT_SLACK)) return false;
            }
        }
        return true;
    }

    /**
     * Raster passes over the selection, reading the ring as fixed:
     * {@code v = min(v, neighbour + step)} when {@code takeMin}, else
     * {@code v = max(v, neighbour + step)}. Two passes are exact for a plain
     * distance transform, but the ring doesn't relay values along itself, so
     * a path like "in from the right, then down" needs another round; repeat
     * forward/backward pairs until nothing changes.
     */
    static void sweep(double[] v, int fullW, int margin, int w, int d, double step, boolean takeMin, boolean[] fixed) {
        for (int round = 0; round < w + d; round++) {
            boolean changed = false;
            for (int z = margin; z < margin + d; z++) {
                for (int x = margin; x < margin + w; x++) {
                    int i = z * fullW + x;
                    if (fixed != null && fixed[i]) continue;
                    double next = pick(v[i], v[i - 1] + step, v[i - fullW] + step, takeMin);
                    if (next != v[i]) {
                        v[i] = next;
                        changed = true;
                    }
                }
            }
            for (int z = margin + d - 1; z >= margin; z--) {
                for (int x = margin + w - 1; x >= margin; x--) {
                    int i = z * fullW + x;
                    if (fixed != null && fixed[i]) continue;
                    double next = pick(v[i], v[i + 1] + step, v[i + fullW] + step, takeMin);
                    if (next != v[i]) {
                        v[i] = next;
                        changed = true;
                    }
                }
            }
            if (!changed) return;
        }
    }

    private static double pick(double a, double b, double c, boolean takeMin) {
        return takeMin ? Math.min(a, Math.min(b, c)) : Math.max(a, Math.max(b, c));
    }

    /**
     * Edge band wide enough to climb from {@code level} (e.g. the sea floor
     * around an island) to {@code baseY} without exceeding {@code maxStep}:
     * a smoothstep ramp is 1.5x steeper than linear at its middle. Never
     * narrower than {@code edge}, never wider than half the selection.
     */
    public static int islandEdge(int baseY, double level, int edge, int w, int d, double maxStep) {
        double slope = maxStep > 0 ? maxStep : 1.0;
        int needed = (int) Math.ceil(1.5 * Math.abs(baseY - level) / slope);
        return Math.min(Math.max(edge, needed), Math.max(1, Math.min(w, d) / 2));
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
