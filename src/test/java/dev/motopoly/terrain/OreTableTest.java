package dev.motopoly.terrain;

import dev.motopoly.terrain.OreTable.Ore;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OreTableTest {
    private static Map<Ore, Integer> countLayer(int y, int size) {
        Map<Ore, Integer> counts = new EnumMap<>(Ore.class);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                Ore ore = OreTable.pick(x, y, z, 42L);
                if (ore != null) counts.merge(ore, 1, Integer::sum);
            }
        }
        return counts;
    }

    @Test
    void diamondsOnlyDeepDown() {
        for (int y = 17; y < 200; y += 7) {
            assertNull(countLayer(y, 200).get(Ore.DIAMOND), "diamond at y=" + y);
        }
        assertTrue(countLayer(-58, 300).getOrDefault(Ore.DIAMOND, 0) > 0);
    }

    @Test
    void coalIsCommonHighUpAndAbsentDeep() {
        assertTrue(countLayer(96, 300).getOrDefault(Ore.COAL, 0) > countLayer(96, 300).getOrDefault(Ore.IRON, 0));
        assertNull(countLayer(-40, 300).get(Ore.COAL));
    }

    @Test
    void ratesMatchTheTable() {
        int size = 400;
        int y = 48; // copper peak
        int got = countLayer(y, size).getOrDefault(Ore.COPPER, 0);
        double expected = OreTable.expectedShare(Ore.COPPER, y) * size * size;
        assertEquals(expected, got, expected * 0.25, "copper at its peak");
    }

    @Test
    void oresComeInVeins() {
        // Most ore blocks have another ore block of the same kind right next to them.
        int ores = 0;
        int withNeighbour = 0;
        for (int x = 0; x < 300; x++) {
            for (int z = 0; z < 300; z++) {
                Ore ore = OreTable.pick(x, 20, z, 7L);
                if (ore == null) continue;
                ores++;
                if (ore == OreTable.pick(x + 1, 20, z, 7L) || ore == OreTable.pick(x - 1, 20, z, 7L)
                        || ore == OreTable.pick(x, 21, z, 7L) || ore == OreTable.pick(x, 19, z, 7L)
                        || ore == OreTable.pick(x, 20, z + 1, 7L) || ore == OreTable.pick(x, 20, z - 1, 7L)) {
                    withNeighbour++;
                }
            }
        }
        assertTrue(ores > 0);
        assertTrue(withNeighbour > ores * 0.6, withNeighbour + " of " + ores);
    }

    @Test
    void stableForTheSameSeed() {
        for (int i = 0; i < 1000; i++) {
            assertEquals(OreTable.pick(i, i % 100 - 50, -i, 9L), OreTable.pick(i, i % 100 - 50, -i, 9L));
        }
    }
}
