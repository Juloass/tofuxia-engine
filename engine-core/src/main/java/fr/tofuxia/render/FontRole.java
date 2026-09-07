package fr.tofuxia.render;

/** Distinct client presentation roles that can select different font assets. */
public enum FontRole {
    UI("ui"),
    DEBUG("debug"),
    WORLD_NAME_TAG("world-name-tag"),
    GAME_TITLE("game-title");

    private final String key;

    FontRole(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    static FontRole fromKey(String key) {
        for (FontRole role : values()) {
            if (role.key.equals(key)) return role;
        }
        throw new IllegalArgumentException("Unknown font role '" + key + "'");
    }
}
