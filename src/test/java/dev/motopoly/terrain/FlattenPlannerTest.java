package dev.motopoly.terrain;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FlattenPlannerTest {
    private static final int MARGIN = 8;

    private static int[] bumpyTerrain(int fullW, int fullD, long seed) {
        Random r = new Random(seed);
        int[] h = new int[fullW * fullD];
        for (int z = 0; z < fullD; z++) {
            for (int x = 0; x < fullW; x++) {
                h[z * fullW + x] = 70 + (int) (8 * Math.sin(x / 6.0) + 6 * Math.cos(z / 9.0)) + r.nextInt(3);
            }
        }
        return h;
    }

    private static FlattenParams params(Integer base, double rough, int feather) {
        return new FlattenParams(base, rough, 48, feather, 2, 1234L, -64, 319);
    }

    @Test
    void zeroRoughnessWithoutFeatherIsPerfectlyFlat() {
        int w = 40, d = 30;
        int[] h = bumpyTerrain(w + 2 * MARGIN, d + 2 * MARGIN, 1);
        FlattenPlan plan = FlattenPlanner.plan(h, w + 2 * MARGIN, d + 2 * MARGIN, MARGIN, 0, 0, params(72, 0, 0));
        for (int y : plan.target()) {
            assertEquals(72, y);
        }
    }

    @Test
    void autoBaseIsSelectionMedian() {
        int w = 32, d = 32;
        int fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
        int[] h = bumpyTerrain(fullW, fullD, 2);
        int[] inner = new int[w * d];
        for (int z = 0; z < d; z++) {
            System.arraycopy(h, (z + MARGIN) * fullW + MARGIN, inner, z * w, w);
        }
        FlattenPlan plan = FlattenPlanner.plan(h, fullW, fullD, MARGIN, 0, 0, params(null, 0, 0));
        assertEquals(TerrainStats.of(inner, w, d).median(), plan.baseY());
    }

    @Test
    void undulationStaysWithinRoughness() {
        int w = 64, d = 64;
        int[] h = bumpyTerrain(w + 2 * MARGIN, d + 2 * MARGIN, 3);
        FlattenPlan plan = FlattenPlanner.plan(h, w + 2 * MARGIN, d + 2 * MARGIN, MARGIN, 100, -50, params(80, 2, 0));
        for (int y : plan.target()) {
            assertTrue(Math.abs(y - 80) <= 2, "y=" + y);
        }
    }

    @Test
    void featherMeetsSurroundingTerrain() {
        int w = 48, d = 48;
        int fullW = w + 2 * MARGIN, fullD = d + 2 * MARGIN;
        int[] h = bumpyTerrain(fullW, fullD, 4);
        FlattenPlan plan = FlattenPlanner.plan(h, fullW, fullD, MARGIN, 0, 0, params(60, 0, 8));
        int originalMaxStep = 0;
        for (int z = 0; z < fullD; z++) {
            for (int x = 1; x < fullW; x++) {
                originalMaxStep = Math.max(originalMaxStep, Math.abs(h[z * fullW + x] - h[z * fullW + x - 1]));
            }
        }
        // The result is never steeper than the original terrain, including across the selection edge.
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int y = plan.target()[z * w + x];
                if (x == 0) {
                    int outside = h[(z + MARGIN) * fullW + MARGIN - 1];
                    assertTrue(Math.abs(y - outside) <= originalMaxStep, "edge jump " + (y - outside));
                }
                if (x > 0) {
                    int step = Math.abs(y - plan.target()[z * w + x - 1]);
                    assertTrue(step <= originalMaxStep, "step " + step + " > " + originalMaxStep);
                }
            }
        }
        // The centre is fully flattened.
        assertEquals(60, plan.target()[(d / 2) * w + w / 2]);
    }

    @Test
    void cutAndFillAreBalancedAroundMedian() {
        int w = 50, d = 50;
        int[] h = bumpyTerrain(w + 2 * MARGIN, d + 2 * MARGIN, 5);
        FlattenPlan plan = FlattenPlanner.plan(h, w + 2 * MARGIN, d + 2 * MARGIN, MARGIN, 0, 0, params(null, 0, 0));
        assertTrue(plan.cut() > 0 && plan.fill() > 0);
        double ratio = (double) plan.cut() / plan.fill();
        assertTrue(ratio > 0.5 && ratio < 2.0, "ratio=" + ratio);
    }

    @Test
    void sameSeedGivesSameTerrain() {
        int w = 20, d = 20;
        int[] h = bumpyTerrain(w + 2 * MARGIN, d + 2 * MARGIN, 6);
        FlattenPlan a = FlattenPlanner.plan(h, w + 2 * MARGIN, d + 2 * MARGIN, MARGIN, 7, 7, params(64, 1.5, 4));
        FlattenPlan b = FlattenPlanner.plan(h, w + 2 * MARGIN, d + 2 * MARGIN, MARGIN, 7, 7, params(64, 1.5, 4));
        assertArrayEquals(a.target(), b.target());
    }

    @Test
    void autoRoughnessTracksSurroundings() {
        assertEquals(0.4, FlattenPlanner.autoRoughness(new TerrainStats(60, 62, 61, 61, 0.5, 0.0)), 1e-9);
        assertEquals(2.0, FlattenPlanner.autoRoughness(new TerrainStats(60, 120, 90, 90, 20, 8.0)), 1e-9);
    }
}
