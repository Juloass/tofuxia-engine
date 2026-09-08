package fr.tofuxia.app;

import fr.tofuxia.particles.ParticleEffectDefinition;
import fr.tofuxia.particles.ParticleRenderPacket;
import fr.tofuxia.render.ParticleMeshData;
import fr.tofuxia.render.ParticleTextureAtlas;
import org.joml.Vector3f;
import io.github.juloass.resource.pack.ResourceSnapshot;

import java.util.List;

public interface ParticleSceneSupport {
    ParticleTextureAtlas atlas();
    default ParticleEffectDefinition definition() { return null; }
    default ParticleEffectDefinition definition(ResourceSnapshot resources) {
        return definition();
    }
    ParticleMeshData mesh(List<ParticleRenderPacket> packets, ParticleTextureAtlas atlas, Vector3f eye, Vector3f target);

    static ParticleSceneSupport none() {
        return new ParticleSceneSupport() {
            public ParticleTextureAtlas atlas() { return ParticleTextureAtlas.white(); }
            public ParticleEffectDefinition definition() { return null; }
            public ParticleMeshData mesh(List<ParticleRenderPacket> packets, ParticleTextureAtlas atlas, Vector3f eye, Vector3f target) { return null; }
        };
    }
}
