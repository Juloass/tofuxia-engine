package fr.tofuxia.render;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Orbit camera with anchored panning (the ground point grabbed at drag start
 * stays under the cursor), smoothed exponential zoom toward the cursor, and
 * pitch-clamped orbiting. Input decisions live in the application layer; this
 * class only exposes camera operations.
 */
public final class Camera {
    private static final float MIN_PITCH = (float) Math.toRadians(20.0);
    private static final float MAX_PITCH = (float) Math.toRadians(80.0);
    private static final float MIN_DISTANCE = 8.0f;
    private static final float MAX_DISTANCE = 36.0f;
    private static final float EXPLORATION_FOV_RADIANS = (float)Math.toRadians(30.0);

    private final Vector3f target = new Vector3f(0.0f, 0.35f, 0.0f);
    private float yawRadians;
    private float pitchRadians;
    private float distance;
    private float targetDistance;
    private final boolean perspectiveProjection;

    private final Vector3f panAnchor = new Vector3f();
    private boolean panning;

    private Camera(float yawRadians, float pitchRadians, float distance,boolean perspectiveProjection) {
        this.yawRadians = yawRadians;
        this.pitchRadians = pitchRadians;
        this.distance = distance;
        this.targetDistance = distance;
        this.perspectiveProjection=perspectiveProjection;
    }

    public static Camera isometricRpg() {
        Camera camera = new Camera((float) Math.toRadians(45.0), (float) Math.toRadians(45.0), 18.0f,true);
        camera.target.set(0.0f, 0.8f, 0.0f);
        return camera;
    }

    public static Camera cornellLightSanity() {
        Camera camera = new Camera((float) Math.toRadians(180.0), (float) Math.toRadians(66.0), 5.2f,false);
        camera.target.set(0.0f, 0.95f, 0.0f);
        return camera;
    }

    public void reset() {
        Camera preset = isometricRpg();
        target.set(preset.target);
        yawRadians = preset.yawRadians;
        pitchRadians = preset.pitchRadians;
        targetDistance = preset.distance;
    }

    public void lockIsometricExploration(float x, float y, float z, float dt) {
        yawRadians = (float) Math.toRadians(45.0);
        pitchRadians = (float) Math.toRadians(45.0);
        float screenLead = 1.15f;
        follow(x - (float)Math.sin(yawRadians) * screenLead, y,
                z - (float)Math.cos(yawRadians) * screenLead, dt);
    }

    /** Immediately initialize an exploration view without flying in from the camera preset. */
    public void snapIsometricExploration(float x, float y, float z) {
        yawRadians = (float) Math.toRadians(45.0);
        pitchRadians = (float) Math.toRadians(45.0);
        float screenLead = 1.15f;
        target.set(x - (float) Math.sin(yawRadians) * screenLead, y,
                z - (float) Math.cos(yawRadians) * screenLead);
    }

    /** Smoothly approach the zoom target. Call once per frame. */
    public void update(float dt) {
        float t = 1.0f - (float) Math.pow(0.0001, dt);
        distance += (targetDistance - distance) * Math.min(1.0f, t * 1.6f);
    }

    /** Grab the ground point under the cursor; subsequent panUpdate calls keep it there. */
    public void panBegin(float cursorX, float cursorY, int width, int height) {
        Vector3f hit = groundHit(cursorX, cursorY, width, height, target.y);
        if (hit != null) {
            panAnchor.set(hit);
            panning = true;
        }
    }

    public void panUpdate(float cursorX, float cursorY, int width, int height) {
        if (!panning) {
            panBegin(cursorX, cursorY, width, height);
            return;
        }
        Vector3f hit = groundHit(cursorX, cursorY, width, height, panAnchor.y);
        if (hit != null) {
            target.add(panAnchor.x - hit.x, 0.0f, panAnchor.z - hit.z);
        }
    }

    public void panEnd() {
        panning = false;
    }

    public void orbitByMouse(float dx, float dy) {
        yawRadians -= dx * 0.006f;
        pitchRadians = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitchRadians + dy * 0.005f));
    }

    /** Exponential zoom that pulls the look-at target toward the cursor as it zooms in. */
    public void zoomByWheel(float wheelDelta, float cursorX, float cursorY, int width, int height) {
        float previous = targetDistance;
        targetDistance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, targetDistance * (float) Math.pow(0.87, wheelDelta)));
        if (wheelDelta > 0.0f && targetDistance < previous) {
            Vector3f hit = groundHit(cursorX, cursorY, width, height, target.y);
            if (hit != null) {
                float pull = 1.0f - targetDistance / previous;
                target.lerp(new Vector3f(hit.x, target.y, hit.z), pull * 0.6f);
            }
        }
    }

    /** Soft-follow a world point (play mode). */
    public void follow(float x, float y, float z, float dt) {
        target.lerp(new Vector3f(x, y, z), Math.min(1.0f, dt * 3.0f));
    }

    /** Center the camera on a world point (frame-selected). */
    public void frame(float x, float y, float z) {
        target.set(x, y, z);
        targetDistance = Math.max(MIN_DISTANCE, Math.min(targetDistance, 7.0f));
    }

    public Vector3f target() {
        return new Vector3f(target);
    }

    public float yaw() {
        return yawRadians;
    }

    public Matrix4f viewProjection(int width, int height) {
        return projection(width, height).mul(view());
    }

    public Matrix4f view() {
        return new Matrix4f().lookAt(position(), target, new Vector3f(0.0f, 1.0f, 0.0f));
    }

    public Matrix4f projection(int width, int height) {
        float aspect = width / (float) Math.max(height, 1);
        Matrix4f projection=perspectiveProjection
                ?new Matrix4f().perspective(EXPLORATION_FOV_RADIANS,aspect,.25f,80.0f,true)
                :new Matrix4f().ortho(-4*aspect,4*aspect,-4,4,.05f,45.0f,true);
        projection.m11(projection.m11() * -1.0f);
        return projection;
    }

    public Ray screenRay(float cursorX, float cursorY, int width, int height) {
        Matrix4f inverse = viewProjection(width, height).invert(new Matrix4f());
        float ndcX = (cursorX / Math.max(width, 1)) * 2.0f - 1.0f;
        float ndcY = (cursorY / Math.max(height, 1)) * 2.0f - 1.0f;
        Vector4f near = inverse.transform(new Vector4f(ndcX, ndcY, 0.0f, 1.0f));
        Vector4f far = inverse.transform(new Vector4f(ndcX, ndcY, 1.0f, 1.0f));
        near.div(near.w);
        far.div(far.w);
        Vector3f origin = new Vector3f(near.x, near.y, near.z);
        Vector3f direction = new Vector3f(far.x - near.x, far.y - near.y, far.z - near.z).normalize();
        return new Ray(origin, direction);
    }

    public Vector2f intersectGround(float cursorX, float cursorY, int width, int height) {
        Vector3f hit = groundHit(cursorX, cursorY, width, height, 0.0f);
        if (hit == null) {
            return new Vector2f(target.x, target.z);
        }
        return new Vector2f(hit.x, hit.z);
    }

    private Vector3f groundHit(float cursorX, float cursorY, int width, int height, float planeY) {
        Ray ray = screenRay(cursorX, cursorY, width, height);
        if (Math.abs(ray.direction.y) < 0.0001f) {
            return null;
        }
        float t = (planeY - ray.origin.y) / ray.direction.y;
        if (t <= 0.0f) {
            return null;
        }
        return ray.origin.fma(t, ray.direction, new Vector3f());
    }

    public String debugPosition() {
        Vector3f eye = position();
        return "(%.1f, %.1f, %.1f)".formatted(eye.x, eye.y, eye.z);
    }

    public Vector3f position() {
        float horizontal = (float) (Math.sin(pitchRadians) * distance);
        return new Vector3f(
                target.x + (float) Math.sin(yawRadians) * horizontal,
                target.y + (float) Math.cos(pitchRadians) * distance,
                target.z + (float) Math.cos(yawRadians) * horizontal
        );
    }

    public record Ray(Vector3f origin, Vector3f direction) {
    }
}
