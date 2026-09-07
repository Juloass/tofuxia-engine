package fr.tofuxia.gltf;

import java.util.List;
import java.util.Locale;

/**
 * Playback state for one model instance: which clip, where in time, how
 * fast, looping or not. Applies sampled channel values onto a
 * {@link SkeletonPose}.
 */
public final class AnimationPlayer {
    private final List<AnimationClip> clips;
    private int clipIndex = -1;
    private float time;
    private float speed = 1.0f;
    private boolean playing;
    private boolean loop = true;

    public AnimationPlayer(List<AnimationClip> clips) {
        this.clips = clips;
    }

    public boolean play(String clipName) {
        for (int i = 0; i < clips.size(); i++) {
            if (clips.get(i).name().equals(clipName)) {
                return play(i);
            }
        }
        System.out.println("[anim] no clip named '" + clipName + "' (available: "
                + clips.stream().map(AnimationClip::name).toList() + ")");
        return false;
    }

    public boolean play(int index) {
        if (index < 0 || index >= clips.size()) return false;
        clipIndex = index;
        time = 0.0f;
        playing = true;
        return true;
    }

    public void pause() {
        playing = false;
    }

    public void resume() {
        if (clipIndex >= 0) playing = true;
    }

    public void togglePause() {
        if (playing) pause();
        else resume();
    }

    public void setSpeed(float value) {
        speed = value;
    }

    public float speed() {
        return speed;
    }

    public void setLoop(boolean value) {
        loop = value;
    }

    public void setTime(float value) {
        time = value;
    }

    public float time() {
        return time;
    }

    public boolean playing() {
        return playing;
    }

    public AnimationClip clip() {
        return clipIndex >= 0 ? clips.get(clipIndex) : null;
    }

    public void update(float dt) {
        AnimationClip clip = clip();
        if (!playing || clip == null) return;
        time += dt * speed;
        float duration = Math.max(clip.duration(), 1e-4f);
        if (loop) {
            time = ((time % duration) + duration) % duration;
        } else if (time >= duration) {
            time = duration;
            playing = false;
        } else if (time < 0) {
            time = 0;
            playing = false;
        }
    }

    /** Samples the current clip time onto the pose (over the default pose). */
    public void apply(SkeletonPose pose) {
        AnimationClip clip = clip();
        if (clip == null) return;
        for (AnimationClip.Channel channel : clip.channels()) {
            switch (channel.path()) {
                case TRANSLATION -> channel.sampleVec3(time, pose.translation(channel.nodeIndex()));
                case ROTATION -> channel.sampleQuat(time, pose.rotation(channel.nodeIndex()));
                case SCALE -> channel.sampleVec3(time, pose.scale(channel.nodeIndex()));
            }
        }
    }

    /** One-line playback description for debug UI. */
    public String status() {
        AnimationClip clip = clip();
        if (clip == null) return "no clip";
        return String.format(Locale.ROOT, "%s %.2fs/%.2fs %s x%.2f%s",
                clip.name(), time, clip.duration(), playing ? "playing" : "paused", speed, loop ? " loop" : "");
    }
}
