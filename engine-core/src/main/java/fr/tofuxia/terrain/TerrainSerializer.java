package fr.tofuxia.terrain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class TerrainSerializer {
    private TerrainSerializer() {
    }

    public static void save(TerrainData terrain, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder out = new StringBuilder(terrain.width() * terrain.height() * 8);
        out.append("tofuxiaTerrain ").append(TerrainData.FORMAT_VERSION).append('\n');
        out.append("width ").append(terrain.width()).append('\n');
        out.append("height ").append(terrain.height()).append('\n');
        out.append("seed ").append(terrain.seed()).append('\n');
        out.append("worldSize ").append(terrain.worldSize()).append('\n');
        writeField(out, "heightField", terrain.heightField);
        writeField(out, "grassMask", terrain.grassMask);
        writeField(out, "dirtMask", terrain.dirtMask);
        writeField(out, "rockMask", terrain.rockMask);
        writeField(out, "sandMask", terrain.sandMask);
        writeField(out, "humidityField", terrain.humidityField);
        writeField(out, "temperatureField", terrain.temperatureField);
        writeField(out, "waterDepthField", terrain.waterDepthField);
        writeField(out, "waterSurfaceHeightField", terrain.waterSurfaceHeightField);
        writeField(out, "waterFlowDirectionField", terrain.waterFlowDirectionField);
        writeField(out, "waterFlowSpeedField", terrain.waterFlowSpeedField);
        Files.writeString(path, out.toString());
    }

    public static TerrainData load(Path path) throws IOException {
        String[] tokens = Files.readString(path).split("\\s+");
        Cursor cursor = new Cursor(tokens);
        cursor.expect("tofuxiaTerrain");
        int version = cursor.nextInt();
        if (version != TerrainData.FORMAT_VERSION) {
            throw new IOException("Unsupported terrain format version " + version);
        }
        cursor.expect("width");
        int width = cursor.nextInt();
        cursor.expect("height");
        int height = cursor.nextInt();
        cursor.expect("seed");
        long seed = cursor.nextLong();
        cursor.expect("worldSize");
        float worldSize = cursor.nextFloat();
        TerrainData terrain = new TerrainData(width, height, seed, worldSize);
        readField(cursor, "heightField", terrain.heightField);
        readField(cursor, "grassMask", terrain.grassMask);
        readField(cursor, "dirtMask", terrain.dirtMask);
        readField(cursor, "rockMask", terrain.rockMask);
        readField(cursor, "sandMask", terrain.sandMask);
        readField(cursor, "humidityField", terrain.humidityField);
        readField(cursor, "temperatureField", terrain.temperatureField);
        readField(cursor, "waterDepthField", terrain.waterDepthField);
        readField(cursor, "waterSurfaceHeightField", terrain.waterSurfaceHeightField);
        readField(cursor, "waterFlowDirectionField", terrain.waterFlowDirectionField);
        readField(cursor, "waterFlowSpeedField", terrain.waterFlowSpeedField);
        terrain.normalizeMaterials();
        return terrain;
    }

    private static void writeField(StringBuilder out, String name, float[] values) {
        out.append(name).append(' ').append(values.length).append('\n');
        for (int i = 0; i < values.length; i++) {
            out.append(String.format(Locale.ROOT, "%.5f", values[i]));
            out.append((i + 1) % 16 == 0 ? '\n' : ' ');
        }
        out.append('\n');
    }

    private static void readField(Cursor cursor, String name, float[] target) throws IOException {
        cursor.expect(name);
        int length = cursor.nextInt();
        if (length != target.length) {
            throw new IOException("Field " + name + " has length " + length + ", expected " + target.length);
        }
        for (int i = 0; i < target.length; i++) {
            target[i] = cursor.nextFloat();
        }
    }

    private static final class Cursor {
        private final String[] tokens;
        private int index;

        private Cursor(String[] tokens) {
            this.tokens = tokens;
        }

        void expect(String expected) throws IOException {
            String actual = next();
            if (!expected.equals(actual)) {
                throw new IOException("Expected '" + expected + "' but found '" + actual + "'");
            }
        }

        String next() throws IOException {
            if (index >= tokens.length) {
                throw new IOException("Unexpected end of terrain file.");
            }
            return tokens[index++];
        }

        int nextInt() throws IOException {
            return Integer.parseInt(next());
        }

        long nextLong() throws IOException {
            return Long.parseLong(next());
        }

        float nextFloat() throws IOException {
            return Float.parseFloat(next());
        }
    }
}
