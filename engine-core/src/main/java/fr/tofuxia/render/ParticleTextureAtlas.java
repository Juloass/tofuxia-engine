package fr.tofuxia.render;

import java.util.Map;

public record ParticleTextureAtlas(int width, int height, byte[] rgba, Map<String, Region> regions) {
    public static ParticleTextureAtlas white() {
        return new ParticleTextureAtlas(1, 1, new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255},
                Map.of("__white", new Region(0, 0, 1, 1, 1, 1)));
    }

    public Region region(String texture) {
        Region region = regions.get(texture);
        return region != null ? region : regions.get("__white");
    }

    public record Region(int x, int y, int width, int height, float atlasWidth, float atlasHeight) {
        public float u0() {
            return x / atlasWidth;
        }

        public float v0() {
            return y / atlasHeight;
        }

        public float u1() {
            return (x + width) / atlasWidth;
        }

        public float v1() {
            return (y + height) / atlasHeight;
        }

        public float uSize() {
            return width / atlasWidth;
        }

        public float vSize() {
            return height / atlasHeight;
        }
    }
}
