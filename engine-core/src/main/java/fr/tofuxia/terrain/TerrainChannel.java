package fr.tofuxia.terrain;

public enum TerrainChannel {
    HEIGHT("height"),
    HEIGHT_SMOOTH("smooth"),
    GRASS("grass"),
    DIRT("dirt"),
    ROCK("rock"),
    SAND("sand"),
    HUMIDITY("humidity"),
    TEMPERATURE("temperature"),
    WATER_DEPTH("water_depth"),
    WATER_SURFACE_HEIGHT("water_surface"),
    WATER_FLOW_DIRECTION("flow_dir"),
    WATER_FLOW_SPEED("flow_speed");

    private final String label;

    TerrainChannel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
