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
        return new GenerateParams(base, rough, 48, edge, smooth, 1234L, -64, 319);
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
    void autoRoughnessTracksSurroundings() {
        assertEquals(0.4, TerrainGenerator.autoRoughness(new TerrainStats(60, 62, 61, 61, 0.5, 0.0)), 1e-9);
        assertEquals(2.0, TerrainGenerator.autoRoughness(new TerrainStats(60, 120, 90, 90, 20, 8.0)), 1e-9);
    }
}
