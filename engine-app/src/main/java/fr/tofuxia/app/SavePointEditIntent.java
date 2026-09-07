package fr.tofuxia.app;

public record SavePointEditIntent(String operation, String id, long expectedRevision, String dimensionId,
                                  float x, float y, float z, float yaw, float pitch, boolean enabled) {}
