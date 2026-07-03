package com.berkepvp.mixin;

import com.berkepvp.BerkePvpClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels ALL vanilla nametag rendering while the ESP is toggled on, so the
 * client's own tags don't overlap with vanilla ones. Target confirmed from
 * decompiled 1.21.11 source: NameTagFeatureRenderer.Storage#add is the single
 * entry point every vanilla nametag submission passes through.
 */
@Environment(EnvType.CLIENT)
@Mixin(NameTagFeatureRenderer.Storage.class)
public class NameTagStorageMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void berkepvp$cancelVanillaNametag(
            PoseStack poseStack,
            Vec3 vec3,
            int i,
            Component component,
            boolean bl,
            int j,
            double d,
            CameraRenderState cameraRenderState,
            CallbackInfo ci
    ) {
        if (BerkePvpClient.espEnabled) {
            ci.cancel();
        }
    }
}