package dev.motopoly.terrain;

/**
 * Which ore, if any, belongs at a position inside freshly filled stone.
 *
 * <p>Rates and height bands follow vanilla's overworld ore placement since
 * 1.18 (coal high up, diamonds and redstone deep down, iron in two bands,
 * and so on), simplified to a triangular or flat density per band. Ores come
 * in small veins rather than lone blocks: the world is cut into 2x2x2 cells,
 * a cell is picked as a vein by one hash, and each block in it is ore with
 * {@link #VEIN_FILL} probability by another. Everything is a pure function of
 * position and seed, so re-running or undoing never shuffles anything.
 */
public final class OreTable {
    public enum Ore { COAL, IRON, COPPER, GOLD, REDSTONE, LAPIS, DIAMOND }

    static final double VEIN_FILL = 0.6;

    /**
     * One height band of an ore.
     *
     * @param perBlock average share of stone blocks in the band that are this ore
     * @param peak     Y with the highest density; density falls linearly to the band ends.
     *                 {@code Integer.MIN_VALUE} for a flat band
     */
    private record Band(Ore ore, int minY, int maxY, int peak, double perBlock) {
        double density(int y) {
            if (y < minY || y > maxY) return 0;
            if (peak == Integer.MIN_VALUE) return perBlock;
            double half = y <= peak ? (double) (y - minY) / Math.max(1, peak - minY)
                                    : (double) (maxY - y) / Math.max(1, maxY - peak);
            // A triangle averages half its peak, so the peak is twice the band average.
            return 2 * perBlock * half;
        }
    }

    private static final int FLAT = Integer.MIN_VALUE;
    private static final Band[] BANDS = {
            new Band(Ore.COAL, 0, 192, 96, 0.0100),
            new Band(Ore.COAL, 136, 320, FLAT, 0.0060),
            new Band(Ore.IRON, -24, 56, 16, 0.0060),
            new Band(Ore.IRON, 80, 320, 232, 0.0040),
            new Band(Ore.IRON, -64, 72, FLAT, 0.0015),
            new Band(Ore.COPPER, -16, 112, 48, 0.0050),
            new Band(Ore.GOLD, -64, 32, -16, 0.0012),
            new Band(Ore.REDSTONE, -64, 15, FLAT, 0.0030),
            new Band(Ore.LAPIS, -64, 64, 0, 0.0010),
            new Band(Ore.DIAMOND, -64, 16, -64, 0.0008),
    };

    private OreTable() {
    }

    /** The ore at this block, or {@code null} for plain stone. */
    public static Ore pick(int x, int y, int z, long seed) {
        long cell = hash(seed, x >> 1, y >> 1, z >> 1);
        double roll = unit(cell);
        // Walk the bands as slices of [0, 1): a cell lands in at most one ore's slice.
        double acc = 0;
        int cy = (y >> 1) << 1;
        for (Band band : BANDS) {
            acc += band.density(cy) / VEIN_FILL;
            if (roll < acc) {
                return unit(hash(seed ^ 0x2545F4914F6CDD1DL, x, y, z)) < VEIN_FILL ? band.ore() : null;
            }
        }
        return null;
    }

    /** Average share of blocks at this Y that come out as {@code ore}, for tests and tuning. */
    static double expectedShare(Ore ore, int y) {
        double total = 0;
        for (Band band : BANDS) {
            if (band.ore() == ore) total += band.density(y);
        }
        return total;
    }

    private static long hash(long seed, int x, int y, int z) {
        long h = seed;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= y * 0xBF58476D1CE4E5B9L;
        h ^= z * 0x94D049BB133111EBL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static double unit(long h) {
        return (h >>> 11) / (double) (1L << 53);
    }
}
