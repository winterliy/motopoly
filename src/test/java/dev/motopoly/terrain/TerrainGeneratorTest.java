package dev.motopoly.terrain;

import org.junit.jupiter.api.Test;

import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class TerrainGeneratorTest {
    private static final int MARGIN = 8;

    private static int[] grid(int fullW, int fullD, IntBinaryOperator height) {
        int[] h = new int[fullW * fullD];
        for (int z = 0; z < fullD; z++) {
            for (int x = 0; x < fullW; x++) {
                h[z * fullW + x] = height.applyAsInt(x, z);
            }
        }
        return h;
    }

    private static GenerateParams params(Integer base, double rough, int edge, int smooth) {
        return new GenerateParams(base, rough, 48, edge, smooth, 0, 1234L, -64, 319);
    }

    private static GeneratePlan plan(int[] h, int w, int d, GenerateParams p) {
        return TerrainGenerator.plan(h, w + 2 * MARGIN, d + 2 * MARGIN, MARGIN, 0, 0, p);
    }

    @Test
    void flatSurroundingsGiveFlatGround() {
        int w = 40, d = 30;
        int[] h = grid(w + 2 * MARGIN, d + 2 * MARGIN, (x, z) -> 70);
        for (int y : plan(h, w, d, params(null, 0, 10, 2)).target()) {
            assertEquals(70, y);
        }
    }

    @Test
    void insideOfSelectionIsIgnored() {
        int w = 32, d = 32, fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
        int[] plain = grid(fullW, fullD, (x, z) -> 64 + x / 8);
        // Same surroundings, but a tower and a pit inside the selection.
        int[] built = grid(fullW, fullD, (x, z) -> {
            boolean inside = x >= MARGIN && x < MARGIN + w && z >= MARGIN && z < MARGIN + d;
            if (!inside) return 64 + x / 8;
            return (x + z) % 2 == 0 ? 200 : 10;
        });
        GenerateParams p = params(null, 1.5, 10, 2);
        assertArrayEquals(plan(plain, w, d, p).target(), plan(built, w, d, p).target());
    }

    @Test
    void slopeAcrossSelectionIsBridgedSmoothly() {
        // Ground rises 1 block every 2 blocks west to east; the inside must follow it.
        int w = 60, d = 40, fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
        int[] h = grid(fullW, fullD, (x, z) -> 60 + x / 2);
        int[] target = plan(h, w, d, params(null, 0, 10, 0)).target();
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int expected = 60 + (x + MARGIN) / 2;
                assertTrue(Math.abs(target[z * w + x] - expected) <= 1,
                        "(" + x + "," + z + ") " + target[z * w + x] + " vs " + expected);
            }
        }
    }

    @Test
    void baseLevelReachedInsideAndEdgesMeetSurroundings() {
        int w = 60, d = 60, fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
        int[] h = grid(fullW, fullD, (x, z) -> 80 + (int) (6 * Math.sin(x / 7.0)));
        int[] target = plan(h, w, d, params(70, 0, 10, 2)).target();
        assertEquals(70, target[(d / 2) * w + w / 2]);
        for (int z = 0; z < d; z++) {
            int outside = h[(z + MARGIN) * fullW + MARGIN - 1];
            assertTrue(Math.abs(target[z * w] - outside) <= 2, "edge gap at z=" + z);
        }
        // No cliffs: the 10-block band spreads a ~10 block drop.
        for (int z = 0; z < d; z++) {
            for (int x = 1; x < w; x++) {
                assertTrue(Math.abs(target[z * w + x] - target[z * w + x - 1]) <= 3);
            }
        }
    }

    @Test
    void undulationStaysWithinRoughness() {
        int w = 64, d = 64;
        int[] h = grid(w + 2 * MARGIN, d + 2 * MARGIN, (x, z) -> 75);
        for (int y : plan(h, w, d, params(null, 2, 0, 0)).target()) {
            assertTrue(Math.abs(y - 75) <= 2, "y=" + y);
        }
    }

    @Test
    void sameSeedGivesSameTerrain() {
        int w = 20, d = 20;
        int[] h = grid(w + 2 * MARGIN, d + 2 * MARGIN, (x, z) -> 64 + (x * z) % 5);
        GenerateParams p = params(null, 1.5, 4, 2);
        assertArrayEquals(plan(h, w, d, p).target(), plan(h, w, d, p).target());
    }

    @Test
    void surroundingsStatsUseOnlyTheMargin() {
        int w = 10, d = 10, fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
        int[] h = grid(fullW, fullD, (x, z) ->
                x >= MARGIN && x < MARGIN + w && z >= MARGIN && z < MARGIN + d ? 300 : 64);
        TerrainStats band = TerrainStats.ofBand(h, fullW, fullD, MARGIN);
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
                double r = relief[z * w + x];
                assertTrue(Math.abs(r) < 1.0, "slope leaked into relief: " + r);
            }
        }
        double spread = 0;
        for (int x = 30; x < 50; x++) spread = Math.max(spread, Math.abs(relief[40 * w + x]));
        assertTrue(spread > 0.3, "bumps were flattened away");
    }

    @Test
    void vanillaReferenceDrivesDetail() {
        int w = 48, d = 48, fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
        int[] around = grid(fullW, fullD, (x, z) -> 70);
        // Reference terrain: a mountain slope with small bumps on it.
        int[] reference = grid(fullW, fullD, (x, z) -> 90 + x / 2 + ((x * 7 + z * 13) % 5 == 0 ? 2 : 0));
        GeneratePlan plan = TerrainGenerator.plan(around, fullW, fullD, MARGIN, 0, 0, params(70, Double.NaN, 4, 0), reference);
        assertTrue(plan.vanillaRelief());
        // The mountain itself must not come through: the centre stays near the target.
        for (int y : plan.target()) {
            assertTrue(Math.abs(y - 70) <= 4, "y=" + y);
        }
        assertTrue(java.util.Arrays.stream(plan.target()).distinct().count() > 1, "no detail at all");
    }

    @Test
    void roughZeroIgnoresReference() {
        int w = 30, d = 30, fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
        int[] around = grid(fullW, fullD, (x, z) -> 64);
        int[] reference = grid(fullW, fullD, (x, z) -> 64 + (x * z) % 3);
        GeneratePlan plan = TerrainGenerator.plan(around, fullW, fullD, MARGIN, 0, 0, params(64, 0, 4, 2), reference);
        for (int y : plan.target()) assertEquals(64, y);
    }

    @Test
    void flatReferenceFallsBackToNoise() {
        int w = 30, d = 30, fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
        int[] flat = grid(fullW, fullD, (x, z) -> 64);
        GeneratePlan plan = TerrainGenerator.plan(flat, fullW, fullD, MARGIN, 0, 0, params(64, 1, 4, 2), flat);
        assertFalse(plan.vanillaRelief());
    }

    @Test
    void poissonSeamKeepsDetailButMatchesEdge() {
        int w = 40, d = 40, fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
        int[] flat = grid(fullW, fullD, (x, z) -> 64);
        int[] reference = grid(fullW, fullD, (x, z) -> 64 + ((x / 2 + z / 3) % 3));
        // edge 0: no fade band at all, the seam fix alone must keep the edge in line.
        int[] target = TerrainGenerator.plan(flat, fullW, fullD, MARGIN, 0, 0, params(64, 1.5, 0, 0), reference).target();
        // No seam: stepping over the edge is never bigger than the texture's own steps inside.
        int innerMaxStep = 0;
        for (int z = 0; z < d; z++) {
            for (int x = 1; x < w; x++) {
                innerMaxStep = Math.max(innerMaxStep, Math.abs(target[z * w + x] - target[z * w + x - 1]));
            }
        }
        for (int z = 0; z < d; z++) {
            assertTrue(Math.abs(target[z * w] - 64) <= innerMaxStep, "left edge z=" + z);
            assertTrue(Math.abs(target[z * w + w - 1] - 64) <= innerMaxStep, "right edge z=" + z);
        }
        // Texture survives one block in from the edge (it used to be faded to nothing there).
        long distinctNearEdge = java.util.stream.IntStream.range(0, d).map(z -> target[z * w + 2]).distinct().count();
        assertTrue(distinctNearEdge > 1, "detail faded out near the edge");
    }

    @Test
    void thermalErosionCapsSlopes() {
        // A 30-block drop squeezed into a 3-block edge band would be a cliff.
        int w = 80, d = 80, fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
        int[] h = grid(fullW, fullD, (x, z) -> 90);
        GenerateParams p = new GenerateParams(60, 0, 48, 3, 0, 1.0, 1L, -64, 319);
        int[] target = TerrainGenerator.plan(h, fullW, fullD, MARGIN, 0, 0, p).target();
        for (int z = 0; z < d; z++) {
            assertTrue(Math.abs(target[z * w] - 90) <= 1, "edge must meet the 90-high surroundings");
            for (int x = 1; x < w; x++) {
                int step = Math.abs(target[z * w + x] - target[z * w + x - 1]);
                assertTrue(step <= 1, "step " + step + " at (" + x + "," + z + ")");
            }
        }
        assertEquals(60, target[(d / 2) * w + w / 2], "centre still reaches the target");
    }

    @Test
    void ravineOutsideDoesNotDragGroundDown() {
        int w = 40, d = 40, fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
        // Flat sea floor at 50 with a 2-wide ravine down to 14 running into the west edge.
        int[] h = grid(fullW, fullD, (x, z) -> x < MARGIN && (z == 24 || z == 25) ? 14 : 50);
        int[] target = TerrainGenerator.plan(h, fullW, fullD, MARGIN, 0, 0, params(50, 0, 6, 0)).target();
        for (int y : target) assertEquals(50, y);
    }

    @Test
    void randomTerrainNeverStepsMoreThanOne() {
        java.util.Random r = new java.util.Random(5);
        for (int trial = 0; trial < 100; trial++) {
            int w = 20 + r.nextInt(50), d = 20 + r.nextInt(50), fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
            double a = r.nextDouble() * 20;
            int phase = trial;
            int[] h = grid(fullW, fullD, (x, z) -> 64 + (int) (a * Math.sin(x / 5.0 + phase) + a * Math.cos(z / 7.0)) + r.nextInt(4));
            int[] reference = grid(fullW, fullD, (x, z) -> 64 + r.nextInt(3));
            GenerateParams p = new GenerateParams(60 + r.nextInt(20), Double.NaN, 48, r.nextInt(12), 2, 1.0, trial, -64, 319);
            int[] t = TerrainGenerator.plan(h, fullW, fullD, MARGIN, 0, 0, p, r.nextBoolean() ? reference : null).target();
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    if (x > 0) assertTrue(Math.abs(t[z * w + x] - t[z * w + x - 1]) <= 1, "trial " + trial);
                    if (z > 0) assertTrue(Math.abs(t[z * w + x] - t[(z - 1) * w + x]) <= 1, "trial " + trial);
                }
            }
        }
    }

    @Test
    void islandEdgeWidensForDeepWater() {
        // Sea floor at 40, island top at 70: 30 blocks to climb at <= 1 per block.
        assertEquals(45, TerrainGenerator.islandEdge(70, 40, 10, 200, 200, 1.0));
        // Never below the player's smooth setting, never more than half the selection.
        assertEquals(10, TerrainGenerator.islandEdge(64, 63, 10, 200, 200, 1.0));
        assertEquals(15, TerrainGenerator.islandEdge(70, 40, 10, 30, 40, 1.0));
    }

    @Test
    void autoRoughnessTracksSurroundings() {
        assertEquals(0.4, TerrainGenerator.autoRoughness(new TerrainStats(60, 62, 61, 61, 0.5, 0.0)), 1e-9);
        assertEquals(2.0, TerrainGenerator.autoRoughness(new TerrainStats(60, 120, 90, 90, 20, 8.0)), 1e-9);
    }
}
