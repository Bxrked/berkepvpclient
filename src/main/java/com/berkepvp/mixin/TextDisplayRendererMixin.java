package com.berkepvp.mixin;

import com.berkepvp.BerkePvpClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides plugin-based "nametags" (TextDisplay entities riding a player) while
 * the ESP is on, without touching free-standing holograms.
 */
@Environment(EnvType.CLIENT)
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
public class TextDisplayRendererMixin {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Display$TextDisplay;Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;F)V",
        at = @At("TAIL")
    )
    private void berkepvp$hidePluginNametags(
            Display.TextDisplay textDisplay,
            TextDisplayEntityRenderState state,
            float f,
            CallbackInfo ci
    ) {
        if (!BerkePvpClient.espEnabled) return;

        Entity vehicle = textDisplay.getVehicle();
        while (vehicle != null) {
            if (vehicle instanceof Player) {
                state.textRenderState = null;
                return;
            }
            vehicle = vehicle.getVehicle();
        }
    }
}
