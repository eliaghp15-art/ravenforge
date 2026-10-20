package ravenforge.client;

import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.resources.model.IBakedModel;

public class ForgeHooksClient {
    public static IBakedModel handleCameraTransforms(IBakedModel model, ItemCameraTransforms.TransformType transform) {
        return model;
    }
}
