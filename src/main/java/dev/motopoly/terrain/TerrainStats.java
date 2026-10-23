package dev.motopoly.terrain;

import java.util.Arrays;

/**
 * Height statistics of a surface heightmap.
 *
 * @param roughness mean absolute 4-neighbour Laplacian. Vanilla plains sit
 *                  around 0.2-0.8, hills 1-2, mountains well above 2.
 */
public record TerrainStats(int min, int max, double mean, int median, double stdDev, double roughness) {

    public static TerrainStats of(int[] heights, int width, int depth) {
        if (heights.length != width * depth || heights.length == 0) {
            throw new IllegalArgumentException("heights must be a non-empty width*depth grid");
        }
        int[] sorted = heights.clone();
        Arrays.sort(sorted);

        double sum = 0;
        for (int h : heights) {
            sum += h;
        }
        double mean = sum / heights.length;
        double var = 0;
        for (int h : heights) {
            var += (h - mean) * (h - mean);
        }

        double lapSum = 0;
        int lapCount = 0;
        for (int z = 1; z < depth - 1; z++) {
            for (int x = 1; x < width - 1; x++) {
                int i = z * width + x;
                int lap = heights[i - 1] + heights[i + 1] + heights[i - width] + heights[i + width] - 4 * heights[i];
                lapSum += Math.abs(lap);
                lapCount++;
            }
        }

        return new TerrainStats(
                sorted[0],
                sorted[sorted.length - 1],
                mean,
                sorted[sorted.length / 2],
                Math.sqrt(var / heights.length),
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
