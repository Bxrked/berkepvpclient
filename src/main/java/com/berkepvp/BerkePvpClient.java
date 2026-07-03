package com.berkepvp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class BerkePvpClient implements ClientModInitializer {

    public static final String MOD_ID = "berkepvpclient";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ── Toggle state ────────────────────────────────────────────────────────────
    public static boolean espEnabled = false;  // master     (V)
    public static boolean showHealth = true;   // health num (N)
    public static boolean seeThrough = true;   // walls      (G)

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    // Screen-space sizing
    private static final float BASE_TEXT_SCALE = 1.0f;   // multiplier on vanilla font size
    private static final int   ICON_SIZE       = 16;     // vanilla item sprite px
    private static final int   LINE_H          = 10;     // px per text line at scale 1

    // ── Captured each frame from the world-render event (real matrices) ─────────
    private volatile boolean matricesReady = false;

    @Override
    public void onInitializeClient() {
        KeyMapping toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.berkepvpclient.toggle", GLFW.GLFW_KEY_V, KeyMapping.Category.MISC));
        KeyMapping healthKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.berkepvpclient.health", GLFW.GLFW_KEY_N, KeyMapping.Category.MISC));
        KeyMapping seeThroughKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.berkepvpclient.seethrough", GLFW.GLFW_KEY_G, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick())     { espEnabled = !espEnabled; LOGGER.info("[BerkePvP] esp -> {}", espEnabled); }
            while (healthKey.consumeClick())     { showHealth = !showHealth; LOGGER.info("[BerkePvP] health -> {}", showHealth); }
            while (seeThroughKey.consumeClick()) { seeThrough = !seeThrough; LOGGER.info("[BerkePvP] seeThrough -> {}", seeThrough); }
        });

        // We no longer capture matrices here — the view matrix is rebuilt from
        // the camera in the HUD phase. This event just confirms a world is active.
        WorldRenderEvents.END_MAIN.register(context -> matricesReady = true);

        // Phase 2: draw in the HUD event, where we have a real GuiGraphics for
        // both text (drawString) and item icons (renderItem).
        HudRenderCallback.EVENT.register((graphics, tick) -> renderHud(graphics));

        LOGGER.info("[BerkePvP] Client initialized.");
    }

    /** Durability ramp: green > yellow > orange > red > dark red > purple. */
    private int durabilityColor(float pct) {
        if      (pct > 0.80f) return 0xFF19FC19;
        else if (pct > 0.60f) return 0xFFFFFF33;
        else if (pct > 0.40f) return 0xFFFF9919;
        else if (pct > 0.20f) return 0xFFFF3333;
        else if (pct > 0.08f) return 0xFF990000;
        else                  return 0xFFAA33FF;
    }

    private void renderHud(GuiGraphics graphics) {
        if (!espEnabled || !matricesReady) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        var camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.position();
        float pt = camera.getPartialTickTime();
        Font font = mc.font;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // proj * VIEW, reused for every player this frame.
        // The captured world PoseStack lacked the camera's view rotation — that's
        // why tags ignored head-turning. Build the real view matrix from the
        // camera rotation quaternion (Camera#rotation(), confirmed). The view
        // matrix is the conjugate (inverse) of the camera's orientation.
        float fov = (float) (Integer) mc.options.fov().get();
        Matrix4f proj = mc.gameRenderer.getProjectionMatrix(fov);
        Matrix4f view = new Matrix4f().rotation(camera.rotation().conjugate(new org.joml.Quaternionf()));
        Matrix4f pv = proj.mul(view);

        for (Player player : mc.level.players()) {
            if (player == mc.player && mc.options.getCameraType().isFirstPerson()) continue;

            double px = Mth.lerp(pt, player.xo, player.getX());
            double py = Mth.lerp(pt, player.yo, player.getY());
            double pz = Mth.lerp(pt, player.zo, player.getZ());
            double headY = py + player.getBbHeight() + 0.3;

            // See-through OFF → skip players with a block between them and the camera.
            if (!seeThrough && isBlocked(mc, camPos, new Vec3(px, py + player.getEyeHeight(), pz), player)) {
                continue;
            }

            // ── Project head world-pos → screen (Meteor's to2D math) ─────────────
            float wx = (float) (px - camPos.x);
            float wy = (float) (headY - camPos.y);
            float wz = (float) (pz - camPos.z);
            Vector4f clip = new Vector4f(wx, wy, wz, 1.0f).mul(pv);
            if (clip.w <= 0.0f) continue; // behind camera

            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;
            float sx = (ndcX * 0.5f + 0.5f) * screenW;
            float sy = (1.0f - (ndcY * 0.5f + 0.5f)) * screenH;

            // Distance scale — constant screen size w/ Meteor falloff.
            // Lowered from 10.0/floor 0.5 so tags shrink a bit at 3-4 blocks.
            float dist = (float) camPos.distanceTo(new Vec3(px, headY, pz));
            // falloff floor raised to 0.9 so far-away tags stay readable
            float falloff = Math.max(1.0f - dist * 0.01f, 0.9f);
            float scale = Math.max(BASE_TEXT_SCALE * (5.5f / Math.max(dist, 0.5f)) * falloff, 0.38f);
            scale = Math.min(scale, 3.0f); // cap point-blank size

            drawTag(graphics, font, player, sx, sy, scale);
        }
    }

    /** True if a block sits between camera and target (target is hidden). */
    private boolean isBlocked(Minecraft mc, Vec3 from, Vec3 to, Player player) {
        BlockHitResult hit = mc.level.clip(new ClipContext(
                from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
        return hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private void drawTag(GuiGraphics graphics, Font font, Player player, float sx, float sy, float scale) {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(sx, sy);
        pose.scale(scale, scale);

        // Collect worn, damageable armor for the icon row
        java.util.List<ItemStack> icons = new java.util.ArrayList<>();
        java.util.List<String>    durText = new java.util.ArrayList<>();
        java.util.List<Integer>   durColor = new java.util.ArrayList<>();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack st = player.getItemBySlot(slot);
            if (st.isEmpty() || !st.isDamageableItem()) continue;
            int maxDur = st.getMaxDamage();
            int cur    = maxDur - st.getDamageValue();
            icons.add(st);
            durText.add(String.valueOf(cur));
            durColor.add(durabilityColor(maxDur > 0 ? (float) cur / maxDur : 1f));
        }

        // Name + health (bottom line)
        String name = player.getName().getString();
        String hp = "";
        int hpColor = 0xFFFFFFFF;
        if (showHealth) {
            float absorption = player.getAbsorptionAmount();
            int health = Math.round(player.getHealth() + absorption);
            double pct = health / (double) (player.getMaxHealth() + absorption);
            hpColor = pct <= 0.333 ? 0xFFFF1919 : pct <= 0.666 ? 0xFFFF6919 : 0xFF19FC19;
            hp = "  " + health;
        }

        // Per-column width: widest of icon/number + gap, so 3-digit durability
        // numbers don't mash together
        int[] colW = new int[icons.size()];
        int iconRowW = 0;
        for (int i = 0; i < icons.size(); i++) {
            colW[i] = Math.max(ICON_SIZE, font.width(durText.get(i))) + 6;
            iconRowW += colW[i];
        }
        int nameRowW = font.width(name) + (hp.isEmpty() ? 0 : font.width(hp));
        int totalW = Math.max(iconRowW, nameRowW);

        int iconRowH = icons.isEmpty() ? 0 : (ICON_SIZE + LINE_H);
        int totalH = iconRowH + LINE_H;

        // Background box
        int bgX = -totalW / 2 - 2;
        int bgY = -totalH - 2;
        graphics.fill(bgX, bgY, bgX + totalW + 4, 2, 0x4B000000);

        // Icon row (icon + colored durability number, both centered per column)
        if (!icons.isEmpty()) {
            int x = -iconRowW / 2;
            int iconY = -totalH;
            for (int i = 0; i < icons.size(); i++) {
                int iconX = x + (colW[i] - ICON_SIZE) / 2;
                graphics.renderItem(icons.get(i), iconX, iconY);
                String d = durText.get(i);
                int dx = x + (colW[i] - font.width(d)) / 2;
                graphics.drawString(font, d, dx, iconY + ICON_SIZE + 1, durColor.get(i));
                x += colW[i];
            }
        }

        // Name + health row
        int nameY = -LINE_H;
        int nx = -nameRowW / 2;
        graphics.drawString(font, name, nx, nameY, 0xFFFFFFFF);
        if (!hp.isEmpty()) {
            graphics.drawString(font, hp, nx + font.width(name), nameY, hpColor);
        }

        pose.popMatrix();
    }
}