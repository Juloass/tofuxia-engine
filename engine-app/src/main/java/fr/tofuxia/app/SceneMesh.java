package fr.tofuxia.app;

import fr.tofuxia.renderer.CpuMesh;

public record SceneMesh(String id, int revision, CpuMesh mesh) {
}
