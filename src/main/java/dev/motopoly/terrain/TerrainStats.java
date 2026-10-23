package dev.motopoly.terrain;

import java.util.Arrays;

/**
 * Height statistics of a surface heightmap.
 *
 * @param roughness mean absolute 4-neighbour Laplacian. Vanilla plains sit
 *                  around 0.2-0.8, hills 1-2, mountains well above 2.
 */
public record TerrainStats(int min, int max, double mean, int median, double stdDev, double roughness) {

    /** Statistics over a whole width*depth grid. */
    public static TerrainStats of(int[] heights, int width, int depth) {
        return of(heights, width, depth, -1);
    }

    /**
     * Statistics over the band of {@code margin} cells around the edge of the
     * grid only, i.e. the terrain surrounding a selection.
     */
    public static TerrainStats ofBand(int[] heights, int fullW, int fullD, int margin) {
        if (margin <= 0) throw new IllegalArgumentException("margin must be > 0");
        return of(heights, fullW, fullD, margin);
    }

    private static TerrainStats of(int[] heights, int width, int depth, int bandMargin) {
        if (heights.length != width * depth || heights.length == 0) {
            throw new IllegalArgumentException("heights must be a non-empty width*depth grid");
        }
        int[] values = new int[heights.length];
        int count = 0;
        double lapSum = 0;
        int lapCount = 0;
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                if (bandMargin > 0 && x >= bandMargin && x < width - bandMargin
                        && z >= bandMargin && z < depth - bandMargin) {
                    continue;
                }
                int i = z * width + x;
                values[count++] = heights[i];
                if (x > 0 && x < width - 1 && z > 0 && z < depth - 1) {
                    int lap = heights[i - 1] + heights[i + 1] + heights[i - width] + heights[i + width] - 4 * heights[i];
                    lapSum += Math.abs(lap);
                    lapCount++;
                }
            }
        }
        int[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);

        double sum = 0;
        for (int h : sorted) sum += h;
        double mean = sum / count;
        double var = 0;
        for (int h : sorted) var += (h - mean) * (h - mean);

        return new TerrainStats(
                sorted[0],
                sorted[count - 1],
                mean,
                sorted[count / 2],
                Math.sqrt(var / count),
                lapCount == 0 ? 0 : lapSum / lapCount);
    }

    /** Rough vanilla-biome reading of the terrain, for the /mt info report. */
    public String describe() {
        if (roughness < 0.6 && stdDev < 2.5) return "평원";
        if (roughness < 1.5 && stdDev < 6) return "완만한 구릉";
        if (roughness < 3) return "언덕";
        return "산악";
    }
}
