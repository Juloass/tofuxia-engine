package fr.tofuxia.app;

import fr.tofuxia.particles.ParticleEventSink;
import io.github.juloass.audio.AudioEngine;
import io.github.juloass.audio.MixerGraph;
import io.github.juloass.resource.pack.ResourceManager;

import java.util.Objects;

/** Application-owned audio policy and particle event adaptation. */
public record AudioSupport(
        MixerGraph mixerGraph,
        ParticleSinkFactory particleSinkFactory
) {
    public AudioSupport {
        Objects.requireNonNull(mixerGraph, "mixerGraph");
        Objects.requireNonNull(particleSinkFactory, "particleSinkFactory");
    }

    @FunctionalInterface
    public interface ParticleSinkFactory {
        ParticleEventSink create(ResourceManager resources, AudioEngine engine);
    }
}
