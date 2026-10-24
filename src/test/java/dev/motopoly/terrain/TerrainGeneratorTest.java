package dev.motopoly.terrain;

import org.junit.jupiter.api.Test;

import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class TerrainGeneratorTest {
    // Fixed columns beyond the band.
    private static final int RING = 8;

    private static int[] grid(int fullW, int fullD, IntBinaryOperator height) {
        int[] h = new int[fullW * fullD];
        for (int z = 0; z < fullD; z++) {
            for (int x = 0; x < fullW; x++) {
                h[z * fullW + x] = height.applyAsInt(x, z);
            }
        }
        return h;
    }

    private static GenerateParams flat(int y, int band, double step) {
        return new GenerateParams(y, y, 0, 48, band, 2, step, 1234L, -64, 319);
    }

    private static GenerateParams range(int lo, int hi, double rough, int band) {
        return new GenerateParams(lo, hi, rough, 48, band, 2, 1.0, 1234L, -64, 319);
    }

    /** A selection of w x d with its band and fixed ring, ready to plan. */
    private record Site(int w, int d, int band, int margin, int fullW, int fullD) {
        Site(int w, int d, int band) {
            this(w, d, band, band + RING, w + 2 * (band + RING), d + 2 * (band + RING));
        }

        int[] heights(IntBinaryOperator height) {
            return grid(fullW, fullD, height);
        }

        GeneratePlan plan(int[] h, GenerateParams p, int[] reference) {
            return TerrainGenerator.plan(h, fullW, fullD, margin, 0, 0, p, reference);
        }

        boolean inZone(int fx, int fz) {
            return fx >= margin && fx < margin + w && fz >= margin && fz < margin + d;
        }

        /** Target at expanded-grid coordinates (inside the selection or its band). */
        int at(int[] target, int fx, int fz) {
            int o = margin - band;
            return target[(fz - o) * (w + 2 * band) + (fx - o)];
        }

        int bandW() {
            return w + 2 * band;
        }

        int bandD() {
            return d + 2 * band;
        }
    }

    private static void assertSteps(int[] t, int bw, int bd, int max, String what) {
        for (int z = 0; z < bd; z++) {
            for (int x = 0; x < bw; x++) {
                if (x > 0) assertTrue(Math.abs(t[z * bw + x] - t[z * bw + x - 1]) <= max, what + " x step at (" + x + "," + z + ")");
                if (z > 0) assertTrue(Math.abs(t[z * bw + x] - t[(z - 1) * bw + x]) <= max, what + " z step at (" + x + "," + z + ")");
            }
        }
    }

    @Test
    void singleLevelFillsTheWholeSelectionFlat() {
        Site s = new Site(40, 30, 10);
        int[] h = s.heights((x, z) -> 64 + (int) (5 * Math.sin(x / 6.0) + 4 * Math.cos(z / 5.0)));
        int[] t = s.plan(h, flat(70, 10, 1.0), h).target();
        for (int fz = s.margin; fz < s.margin + s.d; fz++) {
            for (int fx = s.margin; fx < s.margin + s.w; fx++) assertEquals(70, s.at(t, fx, fz), "(" + fx + "," + fz + ")");
        }
    }

    @Test
    void insideOfSelectionIsIgnored() {
        Site s = new Site(32, 32, 10);
        int[] plain = s.heights((x, z) -> 64 + x / 8);
        // Same surroundings, but a tower and a pit inside the selection.
        int[] built = s.heights((x, z) -> s.inZone(x, z) ? ((x + z) % 2 == 0 ? 200 : 10) : 64 + x / 8);
        GenerateParams p = range(62, 66, 1.5, 10);
        assertArrayEquals(s.plan(plain, p, null).target(), s.plan(built, p, null).target());
    }

    @Test
    void matchingSurroundingsAreLeftAsTheyAre() {
        Site s = new Site(20, 20, 10);
        int[] h = s.heights((x, z) -> 70);
        for (int y : s.plan(h, flat(70, 10, 1.0), null).target()) assertEquals(70, y);
    }

    @Test
    void bandEasesIntoTheSurroundingsOutsideTheSelection() {
        // 8 blocks up from 62 to 70 over a 16-wide band.
        Site s = new Site(30, 30, 16);
        int[] h = s.heights((x, z) -> 62);
        int[] t = s.plan(h, flat(70, 16, 1.0), null).target();
        assertSteps(t, s.bandW(), s.bandD(), 1, "flat");
        int row = s.margin + s.d / 2;
        // Outermost band column meets the untouched ground, the climb happens outside the selection.
        assertTrue(Math.abs(s.at(t, s.margin - s.band, row) - 62) <= 1, "outer end " + s.at(t, s.margin - s.band, row));
        assertEquals(70, s.at(t, s.margin, row));
        for (int fx = s.margin - s.band + 1; fx <= s.margin; fx++) {
            assertTrue(s.at(t, fx, row) >= s.at(t, fx - 1, row), "ramp climbs at " + fx);
        }
        // Smoothstep: no crease, so the ramp is flatter at both ends than in the middle.
        int ends = (s.at(t, s.margin - s.band + 3, row) - s.at(t, s.margin - s.band, row))
                + (s.at(t, s.margin, row) - s.at(t, s.margin - 3, row));
        int middle = s.at(t, s.margin - s.band / 2 + 3, row) - s.at(t, s.margin - s.band / 2 - 3, row);
        assertTrue(ends <= middle, "ends " + ends + " vs middle " + middle);
    }

    @Test
    void zoneStaysPinnedWhenBandIsShort() {
        // 25 blocks up with only a 6-block band: the ramp gets steeper, the zone never sinks.
        Site s = new Site(20, 20, 6);
        int[] h = s.heights((x, z) -> 64);
        int[] t = s.plan(h, flat(89, 6, 1.0), null).target();
        for (int fz = s.margin; fz < s.margin + s.d; fz++) {
            for (int fx = s.margin; fx < s.margin + s.w; fx++) assertEquals(89, s.at(t, fx, fz));
        }
        int row = s.margin + s.d / 2;
        assertTrue(s.at(t, s.margin - s.band, row) < 89 - 10, "outer column must stay near the surroundings");
    }

    @Test
    void shortBandMayDropSeveralBlocksPerColumn() {
        // 30 blocks down over a 5-block band: steep steps are fine, but the band still connects both sides.
        Site s = new Site(20, 20, 5);
        int[] h = s.heights((x, z) -> 90);
        int[] t = s.plan(h, flat(60, 5, 1.0), null).target();
        int row = s.margin + s.d / 2;
        assertEquals(60, s.at(t, s.margin, row));
        assertTrue(s.at(t, s.margin - s.band, row) > 80, "outer end " + s.at(t, s.margin - s.band, row));
        for (int fx = s.margin - s.band + 1; fx <= s.margin; fx++) {
            assertTrue(s.at(t, fx, row) <= s.at(t, fx - 1, row), "ramp descends at " + fx);
        }
    }

    @Test
    void noBandMeansNothingOutsideChanges() {
        Site s = new Site(12, 12, 0);
        int[] h = s.heights((x, z) -> 60);
        GeneratePlan plan = s.plan(h, flat(66, 0, 1.0), null);
        assertEquals(0, plan.band());
        assertEquals(12 * 12, plan.target().length);
        for (int y : plan.target()) assertEquals(66, y);
    }

    @Test
    void rangeKeepsGroundBetweenItsEnds() {
        Site s = new Site(60, 50, 10);
        int[] flat = s.heights((x, z) -> 62);
        int[] hills = s.heights((x, z) -> 60 + x / 3 + (int) (2 * Math.sin(z / 4.0)));
        for (int[] reference : new int[][] {null, flat, hills}) {
            int[] t = s.plan(flat, range(61, 64, 1.5, 10), reference).target();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int fz = s.margin; fz < s.margin + s.d; fz++) {
                for (int fx = s.margin; fx < s.margin + s.w; fx++) {
                    int y = s.at(t, fx, fz);
                    assertTrue(y >= 61 && y <= 64, "y " + y + " at (" + fx + "," + fz + ")");
                    seen.add(y);
                }
            }
            assertTrue(seen.size() >= 3, "range barely used: " + seen);
            assertSteps(t, s.bandW(), s.bandD(), 1, "range");
        }
    }

    @Test
    void sameSeedGivesSameTerrain() {
        Site s = new Site(20, 20, 6);
        int[] h = s.heights((x, z) -> 64 + (x * z) % 5);
        GenerateParams p = range(62, 67, 1.5, 6);
        assertArrayEquals(s.plan(h, p, null).target(), s.plan(h, p, null).target());
    }

    @Test
    void vanillaReferenceDrivesDetail() {
        Site s = new Site(48, 48, 6);
        int[] around = s.heights((x, z) -> 70);
        // Reference terrain: a mountain slope with small bumps on it.
        int[] reference = s.heights((x, z) -> 90 + x / 2 + ((x * 7 + z * 13) % 5 == 0 ? 2 : 0));
        GeneratePlan plan = s.plan(around, range(68, 72, Double.NaN, 6), reference);
        assertTrue(plan.vanillaRelief());
        assertTrue(java.util.Arrays.stream(plan.target()).distinct().count() > 1, "no detail at all");
    }

    @Test
    void flatReferenceFallsBackToNoise() {
        Site s = new Site(30, 30, 4);
        int[] flat = s.heights((x, z) -> 64);
        assertFalse(s.plan(flat, range(62, 66, 1, 4), flat).vanillaRelief());
    }

    @Test
    void ravineOutsideDoesNotDragGroundDown() {
        Site s = new Site(40, 40, 6);
        // Flat sea floor at 50 with a 2-wide ravine down to 14 running into the west edge.
        int[] h = s.heights((x, z) -> x < s.margin && (z == 30 || z == 31) ? 14 : 50);
        for (int y : s.plan(h, flat(50, 6, 1.0), null).target()) assertEquals(50, y);
    }

    @Test
    void randomTerrainNeverStepsMoreThanOne() {
        java.util.Random r = new java.util.Random(5);
        for (int trial = 0; trial < 60; trial++) {
            int lo = 60 + r.nextInt(20);
            int hi = lo + (r.nextBoolean() ? 0 : r.nextInt(6));
            int w = 30 + r.nextInt(60), d = 30 + r.nextInt(60);
            double a = r.nextDouble() * 12;
            int phase = trial;
            IntBinaryOperator terrain = (x, z) -> 64 + (int) (a * Math.sin(x / 5.0 + phase) + a * Math.cos(z / 7.0));
            // Band wide enough for the climb, so the slope limit can always hold.
            Site s = new Site(w, d, 48);
            GenerateParams p = new GenerateParams(lo, hi, Double.NaN, 48, 48, 2, 1.0, trial, -64, 319);
            int[] h = s.heights(terrain);
            int[] reference = s.heights((x, z) -> 64 + r.nextInt(3));
            int[] t = s.plan(h, p, r.nextBoolean() ? reference : null).target();
            assertSteps(t, s.bandW(), s.bandD(), 1, "trial " + trial);
            for (int fz = s.margin; fz < s.margin + s.d; fz++) {
                for (int fx = s.margin; fx < s.margin + s.w; fx++) {
                    int y = s.at(t, fx, fz);
                    assertTrue(y >= lo && y <= hi, "trial " + trial + ": " + y + " outside " + lo + "~" + hi);
                }
            }
        }
    }

    @Test
    void surroundingsStatsUseOnlyTheMargin() {
        int w = 10, d = 10, m = 8, fullW = w + 2 * m, fullD = d + 2 * m;
        int[] h = grid(fullW, fullD, (x, z) -> x >= m && x < m + w && z >= m && z < m + d ? 300 : 64);
        TerrainStats band = TerrainStats.ofBand(h, fullW, fullD, m);
        assertEquals(64, band.max());
        assertEquals(64, band.median());
    }

    @Test
    void reliefRemovesSlopesAndKeepsBumps() {
        int w = 80, d = 80;
        // A steep ramp (should vanish) plus 1-block bumps every 6 blocks (should stay).
        int[] h = grid(w, d, (x, z) -> 60 + x + ((x / 3 + z / 3) % 2 == 0 ? 1 : 0));
        double[] relief = TerrainGenerator.relief(h, w, d, 12);
        for (int z = 30; z < 50; z++) {
            for (int x = 30; x < 50; x++) {
                double v = relief[z * w + x];
                assertTrue(Math.abs(v) < 1.0, "slope leaked into relief: " + v);
            }
        }
        double spread = 0;
        for (int x = 30; x < 50; x++) spread = Math.max(spread, Math.abs(relief[40 * w + x]));
        assertTrue(spread > 0.3, "bumps were flattened away");
    }

    @Test
    void roundCutsCornersIntoTheBand() {
        // Square selection at 70 in 60-high surroundings; round 100 makes the zone a circle.
        int w = 41, d = 41, band = 6, margin = band + RING, fullW = w + 2 * margin, fullD = d + 2 * margin;
        int[] h = grid(fullW, fullD, (x, z) -> 60);
        GenerateParams p = new GenerateParams(70, 70, 0, 48, band, 2, 1.0, 1234L, -64, 319, 100);
        GeneratePlan plan = TerrainGenerator.plan(h, fullW, fullD, margin, 0, 0, p);
        int bw = w + 2 * band;
        java.util.function.IntBinaryOperator at = (x, z) -> plan.target()[(z + band) * bw + (x + band)];
        java.util.function.BiPredicate<Integer, Integer> zone = (x, z) -> plan.zone()[(z + band) * bw + (x + band)];
        assertEquals(70, at.applyAsInt(20, 20));
        assertEquals(70, at.applyAsInt(0, 20), "edge middle stays in the zone");
        assertTrue(zone.test(0, 20) && zone.test(20, 0));
        assertFalse(zone.test(0, 0), "corner is cut off");
        assertEquals(60, at.applyAsInt(0, 0), "far corner keeps the surroundings' height");
        assertTrue(at.applyAsInt(6, 6) > 60 && at.applyAsInt(6, 6) <= 70, "corner ramps between the two");
        // Round 0 keeps the old square zone.
        GeneratePlan square = TerrainGenerator.plan(h, fullW, fullD, margin, 0, 0,
                new GenerateParams(70, 70, 0, 48, band, 2, 1.0, 1234L, -64, 319, 0));
        assertEquals(70, square.target()[band * bw + band]);
        assertTrue(square.zone()[band * bw + band]);
    }

    @Test
    void autoRoughnessTracksSurroundings() {
        assertEquals(0.4, TerrainGenerator.autoRoughness(new TerrainStats(60, 62, 61, 61, 0.5, 0.0)), 1e-9);
        assertEquals(2.0, TerrainGenerator.autoRoughness(new TerrainStats(60, 120, 90, 90, 20, 8.0)), 1e-9);
    }
}
