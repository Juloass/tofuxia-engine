package fr.tofuxia.terrain;

public enum DebugVisualization {
    MATERIALS("materials"),
    HEIGHT("height"),
    HUMIDITY("humidity"),
    TEMPERATURE("temp"),
    WATER("water");

    private final String label;

    DebugVisualization(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
