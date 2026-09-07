package fr.tofuxia.terrain;

public final class TerrainBrush {
    public boolean apply(TerrainData terrain, BrushSettings settings, float worldX, float worldZ, float dt) {
        int centerX = terrain.sampleX(worldX);
        int centerY = terrain.sampleY(worldZ);
        int sampleRadius = Math.max(1, Math.round(settings.radius() / terrain.worldSize() * terrain.width()));
        float signed = settings.subtract() ? -1.0f : 1.0f;
        float amount = settings.strength() * dt * signed;
        boolean changed = false;

        for (int y = centerY - sampleRadius; y <= centerY + sampleRadius; y++) {
            if (y < 0 || y >= terrain.height()) continue;
            for (int x = centerX - sampleRadius; x <= centerX + sampleRadius; x++) {
                if (x < 0 || x >= terrain.width()) continue;
                float dx = terrain.worldX(x) - worldX;
                float dz = terrain.worldZ(y) - worldZ;
                float distance = (float) Math.sqrt(dx * dx + dz * dz);
                if (distance > settings.radius()) continue;
                float normalized = distance / settings.radius();
                float weight = (float) Math.pow(1.0f - normalized, 0.35f + settings.falloff() * 2.0f);
                editSample(terrain, settings.channel(), terrain.index(x, y), amount * weight, x, y);
                changed = true;
            }
        }
        if (changed && isMaterial(settings.channel())) {
            terrain.normalizeMaterials();
        }
        return changed;
    }

    private void editSample(TerrainData terrain, TerrainChannel channel, int i, float amount, int x, int y) {
        switch (channel) {
            case HEIGHT -> terrain.heightField[i] += amount * 0.35f;
            case HEIGHT_SMOOTH -> terrain.heightField[i] = smoothHeight(terrain, x, y, Math.abs(amount));
            case GRASS -> terrain.grassMask[i] = TerrainData.clamp01(terrain.grassMask[i] + amount);
            case DIRT -> terrain.dirtMask[i] = TerrainData.clamp01(terrain.dirtMask[i] + amount);
            case ROCK -> terrain.rockMask[i] = TerrainData.clamp01(terrain.rockMask[i] + amount);
            case SAND -> terrain.sandMask[i] = TerrainData.clamp01(terrain.sandMask[i] + amount);
            case HUMIDITY -> terrain.humidityField[i] = TerrainData.clamp01(terrain.humidityField[i] + amount);
            case TEMPERATURE -> terrain.temperatureField[i] = TerrainData.clamp01(terrain.temperatureField[i] + amount);
            case WATER_DEPTH -> terrain.waterDepthField[i] = Math.max(0.0f, terrain.waterDepthField[i] + amount * 0.35f);
            case WATER_SURFACE_HEIGHT -> terrain.waterSurfaceHeightField[i] += amount * 0.35f;
            case WATER_FLOW_DIRECTION -> terrain.waterFlowDirectionField[i] += amount * 2.0f;
            case WATER_FLOW_SPEED -> terrain.waterFlowSpeedField[i] = Math.max(0.0f, terrain.waterFlowSpeedField[i] + amount);
        }
    }

    private float smoothHeight(TerrainData terrain, int x, int y, float amount) {
        float sum = 0.0f;
        int count = 0;
        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                int sx = TerrainData.clamp(x + ox, 0, terrain.width() - 1);
                int sy = TerrainData.clamp(y + oy, 0, terrain.height() - 1);
                sum += terrain.heightField[terrain.index(sx, sy)];
                count++;
            }
        }
        float average = sum / count;
        int i = terrain.index(x, y);
        float blend = TerrainData.clamp01(amount * 2.0f);
        return terrain.heightField[i] + (average - terrain.heightField[i]) * blend;
    }

    private boolean isMaterial(TerrainChannel channel) {
        return channel == TerrainChannel.GRASS || channel == TerrainChannel.DIRT
                || channel == TerrainChannel.ROCK || channel == TerrainChannel.SAND;
    }
}
