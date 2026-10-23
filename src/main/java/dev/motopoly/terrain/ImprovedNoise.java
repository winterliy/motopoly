package dev.motopoly.terrain;

import java.util.Random;

/**
 * Ken Perlin's improved noise, laid out the same way as Minecraft's
 * {@code net.minecraft.world.level.levelgen.synth.ImprovedNoise}: a shuffled
 * 256-entry permutation plus a random per-instance offset, and the 16-entry
 * gradient table (12 cube edges + 4 repeats). Output is roughly in [-1, 1].
 */
public final class ImprovedNoise {
    private static final int[][] GRADIENT = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
            {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
    };

    private final byte[] perm = new byte[256];
    private final double xo;
    private final double yo;
    private final double zo;

    public ImprovedNoise(Random random) {
        xo = random.nextDouble() * 256.0;
        yo = random.nextDouble() * 256.0;
        zo = random.nextDouble() * 256.0;
        for (int i = 0; i < 256; i++) {
            perm[i] = (byte) i;
        }
        for (int i = 0; i < 256; i++) {
            int j = random.nextInt(256 - i);
            byte tmp = perm[i];
            perm[i] = perm[i + j];
            perm[i + j] = tmp;
        }
    }

    public double noise(double x, double y, double z) {
        double dx = x + xo;
        double dy = y + yo;
        double dz = z + zo;
        int xi = (int) Math.floor(dx);
        int yi = (int) Math.floor(dy);
        int zi = (int) Math.floor(dz);
        double xr = dx - xi;
        double yr = dy - yi;
        double zr = dz - zi;

        int a = p(xi);
        int b = p(xi + 1);
        int aa = p(a + yi);
        int ab = p(a + yi + 1);
        int ba = p(b + yi);
        int bb = p(b + yi + 1);

        double g000 = gradDot(p(aa + zi), xr, yr, zr);
        double g100 = gradDot(p(ba + zi), xr - 1, yr, zr);
        double g010 = gradDot(p(ab + zi), xr, yr - 1, zr);
        double g110 = gradDot(p(bb + zi), xr - 1, yr - 1, zr);
        double g001 = gradDot(p(aa + zi + 1), xr, yr, zr - 1);
        double g101 = gradDot(p(ba + zi + 1), xr - 1, yr, zr - 1);
        double g011 = gradDot(p(ab + zi + 1), xr, yr - 1, zr - 1);
        double g111 = gradDot(p(bb + zi + 1), xr - 1, yr - 1, zr - 1);

        double u = fade(xr);
        double v = fade(yr);
        double w = fade(zr);
        return lerp(w,
                lerp(v, lerp(u, g000, g100), lerp(u, g010, g110)),
                lerp(v, lerp(u, g001, g101), lerp(u, g011, g111)));
    }

    private int p(int i) {
        return perm[i & 255] & 255;
    }

    private static double gradDot(int hash, double x, double y, double z) {
        int[] g = GRADIENT[hash & 15];
        return g[0] * x + g[1] * y + g[2] * z;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
