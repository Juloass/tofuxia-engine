package fr.tofuxia.gltf;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * One glTF animation: named channels writing translation/rotation/scale of
 * nodes over time. Sampling is stateless; playback state lives in
 * {@link AnimationPlayer}.
 */
public record AnimationClip(String name, float duration, List<Channel> channels) {

    public enum Path { TRANSLATION, ROTATION, SCALE }

    public enum Interpolation { LINEAR, STEP }

    /**
     * One sampler+target pair. {@code values} is tightly packed with 3 floats
     * per key (translation/scale) or 4 (rotation quaternion xyzw).
     */
    public record Channel(int nodeIndex, Path path, Interpolation interpolation, float[] times, float[] values) {
        public int keyCount() {
            return times.length;
        }

        /** Writes the sampled vec3 at {@code time} into {@code out}. */
        public void sampleVec3(float time, Vector3f out) {
            int next = upperBound(time);
            if (next <= 0) {
                out.set(values[0], values[1], values[2]);
                return;
            }
            if (next >= times.length) {
                int base = (times.length - 1) * 3;
                out.set(values[base], values[base + 1], values[base + 2]);
                return;
            }
            int prev = next - 1;
            if (interpolation == Interpolation.STEP) {
                out.set(values[prev * 3], values[prev * 3 + 1], values[prev * 3 + 2]);
                return;
            }
            float t = (time - times[prev]) / (times[next] - times[prev]);
            out.set(
                    lerp(values[prev * 3], values[next * 3], t),
                    lerp(values[prev * 3 + 1], values[next * 3 + 1], t),
                    lerp(values[prev * 3 + 2], values[next * 3 + 2], t));
        }

        /** Writes the sampled quaternion at {@code time} into {@code out}. */
        public void sampleQuat(float time, Quaternionf out) {
            int next = upperBound(time);
            if (next <= 0) {
                out.set(values[0], values[1], values[2], values[3]);
                return;
            }
            if (next >= times.length) {
                int base = (times.length - 1) * 4;
                out.set(values[base], values[base + 1], values[base + 2], values[base + 3]);
                return;
            }
            int prev = next - 1;
            if (interpolation == Interpolation.STEP) {
                out.set(values[prev * 4], values[prev * 4 + 1], values[prev * 4 + 2], values[prev * 4 + 3]);
                return;
            }
            float t = (time - times[prev]) / (times[next] - times[prev]);
            Quaternionf a = new Quaternionf(values[prev * 4], values[prev * 4 + 1], values[prev * 4 + 2], values[prev * 4 + 3]);
            Quaternionf b = new Quaternionf(values[next * 4], values[next * 4 + 1], values[next * 4 + 2], values[next * 4 + 3]);
            a.slerp(b, t, out);
        }

        /** Index of the first keyframe with time > {@code time} (binary search). */
        private int upperBound(float time) {
            int low = 0;
            int high = times.length;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (times[mid] <= time) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }
    }
}
