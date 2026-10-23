package dev.motopoly.terrain;

import java.util.Random;

/**
 * Fractal sum of {@link ImprovedNoise} octaves, like Minecraft's {@code PerlinNoise}:
 * each octave doubles the frequency and halves the amplitude. The result is
 * normalised by the total amplitude so it stays roughly in [-1, 1].
 */
public final class OctaveNoise {
    private final ImprovedNoise[] octaves;
    private final double amplitudeSum;

    public OctaveNoise(long seed, int octaveCount) {
        if (octaveCount < 1) {
            throw new IllegalArgumentException("octaveCount must be >= 1");
        }
        Random random = new Random(seed);
        octaves = new ImprovedNoise[octaveCount];
        double sum = 0;
        for (int i = 0; i < octaveCount; i++) {
            octaves[i] = new ImprovedNoise(random);
            sum += 1.0 / (1 << i);
        }
        amplitudeSum = sum;
    }

    public double sample(double x, double z) {
        double total = 0;
        double frequency = 1;
        double amplitude = 1;
        for (ImprovedNoise octave : octaves) {
            total += octave.noise(x * frequency, 0, z * frequency) * amplitude;
            frequency *= 2;
            amplitude *= 0.5;
        }
        return total / amplitudeSum;
    }
}
