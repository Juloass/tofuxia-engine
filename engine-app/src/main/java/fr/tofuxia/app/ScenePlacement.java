package fr.tofuxia.app;

import fr.tofuxia.renderer.Material;
import org.joml.Matrix4f;

public record ScenePlacement(String label, String mesh, Material material, Matrix4f transform) {
}
