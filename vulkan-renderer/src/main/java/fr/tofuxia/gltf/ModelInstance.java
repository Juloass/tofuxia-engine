package fr.tofuxia.gltf;

import fr.tofuxia.renderer.JointPalette;
import fr.tofuxia.renderer.Renderer;
import fr.tofuxia.renderer.ShaderLibrary;
import org.joml.Matrix4f;

import java.util.List;

/**
 * One placed copy of a {@link ModelAsset}: its own world transform,
 * animation player, skeleton pose and GPU joint palettes. Instances of the
 * same asset animate independently.
 */
public final class ModelInstance {
    private final ModelAsset asset;
    private final Matrix4f worldTransform = new Matrix4f();
    private final SkeletonPose pose;
    private final AnimationPlayer player;
    private final JointPalette[] skinPalettes;
    private final Matrix4f[][] jointScratch;
    private final Matrix4f modelScratch = new Matrix4f();

    public ModelInstance(ModelAsset asset, Renderer renderer) {
        this.asset = asset;
        this.pose = new SkeletonPose(asset.nodes().size());
        this.player = new AnimationPlayer(asset.animations());
        this.skinPalettes = new JointPalette[asset.skins().size()];
        this.jointScratch = new Matrix4f[asset.skins().size()][];
        for (int i = 0; i < skinPalettes.length; i++) {
            skinPalettes[i] = renderer.createJointPalette();
            int joints = Math.min(asset.skins().get(i).jointCount(), ShaderLibrary.MAX_JOINTS);
            jointScratch[i] = new Matrix4f[joints];
            for (int j = 0; j < joints; j++) {
                jointScratch[i][j] = new Matrix4f();
            }
        }
        pose.reset(asset.nodes());
        pose.computeWorldMatrices(asset.nodes(), asset.rootNodes());
    }

    public ModelAsset asset() {
        return asset;
    }

    public AnimationPlayer player() {
        return player;
    }

    public Matrix4f worldTransform() {
        return worldTransform;
    }

    public ModelInstance transform(Matrix4f value) {
        worldTransform.set(value);
        return this;
    }

    /** Advances animation and recomputes node world matrices. */
    public void update(float dt) {
        player.update(dt);
        pose.reset(asset.nodes());
        player.apply(pose);
        pose.computeWorldMatrices(asset.nodes(), asset.rootNodes());
    }

    /**
     * Submits every visible mesh primitive through the normal renderer path.
     * Skinned primitives upload their joint palette for the current frame
     * first; static primitives just submit with node world transforms.
     */
    public void submit(Renderer renderer) {
        List<ModelNode> nodes = asset.nodes();
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            ModelNode node = nodes.get(nodeIndex);
            if (!node.hasMesh() || node.meshIndex() >= asset.meshes().size()) continue;
            ModelAsset.Mesh mesh = asset.meshes().get(node.meshIndex());
            worldTransform.mul(pose.worldMatrix(nodeIndex), modelScratch);
            if (node.hasSkin() && node.skinIndex() < skinPalettes.length) {
                ModelSkin skin = asset.skins().get(node.skinIndex());
                Matrix4f[] joints = jointScratch[node.skinIndex()];
                pose.jointMatrices(skin, nodeIndex, joints);
                JointPalette palette = skinPalettes[node.skinIndex()];
                palette.update(renderer.frameIndex(), joints);
                for (ModelAsset.Primitive primitive : mesh.primitives()) {
                    renderer.submitSkinned(primitive.mesh(), modelScratch, primitive.material(), palette);
                }
            } else {
                for (ModelAsset.Primitive primitive : mesh.primitives()) {
                    renderer.submit(primitive.mesh(), modelScratch, primitive.material());
                }
            }
        }
    }
}
