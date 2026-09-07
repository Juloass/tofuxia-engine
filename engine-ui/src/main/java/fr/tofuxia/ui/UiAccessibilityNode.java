package fr.tofuxia.ui;

/** Immutable semantic tree leaf for platform accessibility adapters and tests. */
public record UiAccessibilityNode(String id, UiRole role, String label, String value,
                                  UiRect bounds, boolean enabled, boolean focused, boolean selected) {
}
