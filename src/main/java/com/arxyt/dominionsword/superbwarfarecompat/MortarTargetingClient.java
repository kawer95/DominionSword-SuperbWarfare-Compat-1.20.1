package com.arxyt.dominionsword.superbwarfarecompat;

import com.arxyt.dominionsword.client.ClientCursor;
import com.arxyt.dominionsword.client.ClientSpirit;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = DominionSwordSuperbWarfareCompatMod.MODID, value = Dist.CLIENT)
final class MortarTargetingClient {
    private static UUID mortarId;
    private static boolean consumeLeftRelease;
    private static boolean consumeRightRelease;
    private static final Map<UUID, BlockPos> MARKED_TARGETS = new HashMap<>();
    private static final Map<UUID, Double> MINIMUM_RANGES = new HashMap<>();

    private MortarTargetingClient() {}

    static void begin(UUID id) {
        mortarId = id;
        consumeLeftRelease = false;
        consumeRightRelease = false;
        Minecraft.getInstance().setScreen(null);
    }

    static void setMarkedTarget(UUID id, boolean valid, BlockPos target) {
        if (valid) MARKED_TARGETS.put(id, target);
        else MARKED_TARGETS.remove(id);
    }

    static boolean active() { return mortarId != null; }

    private static void cancel() { mortarId = null; }

    private static BlockHitResult target() {
        Minecraft minecraft = Minecraft.getInstance();
        return ClientCursor.groundHitAt(minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos(), 512D);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void mouse(InputEvent.MouseButton.Pre event) {
        if (!active()) {
            if (consumeLeftRelease && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT && event.getAction() == GLFW.GLFW_RELEASE) {
                consumeLeftRelease = false; event.setCanceled(true);
            } else if (consumeRightRelease && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && event.getAction() == GLFW.GLFW_RELEASE) {
                consumeRightRelease = false; event.setCanceled(true);
            }
            return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            event.setCanceled(true);
            if (event.getAction() == GLFW.GLFW_PRESS) {
                consumeLeftRelease = true;
                BlockHitResult hit = target();
                if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                    MortarEntity mortar = mortar(mortarId);
                    if (mortar != null && !MortarCommands.hasNativeTrajectory(mortar, hit.getBlockPos())) return;
                    UUID id = mortarId;
                    cancel();
                    MortarNetwork.CHANNEL.sendToServer(new MortarAimTargetPacket(id, hit.getBlockPos()));
                }
            }
        } else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            event.setCanceled(true);
            if (event.getAction() == GLFW.GLFW_PRESS) { consumeRightRelease = true; cancel(); }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void key(InputEvent.Key event) {
        if (active() && event.getKey() == GLFW.GLFW_KEY_ESCAPE && event.getAction() == GLFW.GLFW_PRESS) cancel();
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) { cancel(); MARKED_TARGETS.clear(); MINIMUM_RANGES.clear(); return; }
        drawMarkedTargets(event);
        if (!active()) return;
        if (!ClientSpirit.active()) { cancel(); return; }
        MortarEntity mortar = mortar(mortarId);
        if (mortar == null || !mortar.isAlive()) { cancel(); return; }
        double minimum = MINIMUM_RANGES.computeIfAbsent(mortarId, ignored -> minimumRange(mortar));
        drawForbiddenArea(event, mortar, minimum);
        BlockHitResult hit = target();
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;
        drawRing(event, hit.getLocation(), MortarCommands.hasNativeTrajectory(mortar, hit.getBlockPos()));
    }

    private static void drawRing(RenderLevelStageEvent event, Vec3 center, boolean valid) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        float pulse = (float) (0.08D * Math.sin(System.nanoTime() / 180_000_000D));
        double outer = 1.05D + pulse, inner = outer - 0.16D;
        double y = center.y - cameraPos.y + 0.075D;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < 48; i++) {
            double a0 = Math.PI * 2D * i / 48D, a1 = Math.PI * 2D * (i + 1) / 48D;
            int green = valid ? 220 : 45, blue = valid ? 40 : 35;
            vertex(buffer, matrix, center.x - cameraPos.x + Math.cos(a0) * inner, y, center.z - cameraPos.z + Math.sin(a0) * inner, 255, green, blue, 180);
            vertex(buffer, matrix, center.x - cameraPos.x + Math.cos(a0) * outer, y, center.z - cameraPos.z + Math.sin(a0) * outer, 255, green, blue, 235);
            vertex(buffer, matrix, center.x - cameraPos.x + Math.cos(a1) * outer, y, center.z - cameraPos.z + Math.sin(a1) * outer, 255, green, blue, 235);
            vertex(buffer, matrix, center.x - cameraPos.x + Math.cos(a1) * inner, y, center.z - cameraPos.z + Math.sin(a1) * inner, 255, green, blue, 180);
        }
        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void drawForbiddenArea(RenderLevelStageEvent event, MortarEntity mortar, double radius) {
        if (radius <= 0.5D) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        double cx = mortar.getX() - cameraPos.x, cz = mortar.getZ() - cameraPos.z;
        double y = mortar.getBoundingBox().minY - cameraPos.y + 0.08D;
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableCull(); RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        double inv = 1D / Math.sqrt(2D), halfWidth = 0.075D;
        for (double offset = -radius; offset <= radius; offset += 1.15D) {
            double along = Math.sqrt(Math.max(0D, radius * radius - offset * offset));
            double px = offset * inv, pz = offset * inv, dx = inv, dz = -inv;
            quadStrip(buffer, matrix, cx + px - dx * along, y, cz + pz - dz * along,
                    cx + px + dx * along, y, cz + pz + dz * along, halfWidth, 255, 35, 28, 155);
        }
        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.enableDepthTest(); RenderSystem.enableCull(); RenderSystem.disableBlend();
    }

    private static void drawMarkedTargets(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Matrix4f matrix = event.getPoseStack().last().pose();
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableCull(); RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        double rotation = (minecraft.level.getGameTime() + minecraft.getFrameTime()) * 0.075D;
        for (Map.Entry<UUID, BlockPos> entry : MARKED_TARGETS.entrySet()) {
            if (mortar(entry.getKey()) == null) continue;
            Vec3 center = Vec3.atCenterOf(entry.getValue()).add(0D, 0.58D, 0D).subtract(cameraPos);
            for (int i = 0; i < 2; i++) {
                double angle = rotation + Math.PI * 0.25D + i * Math.PI * 0.5D;
                double dx = Math.cos(angle) * 1.55D, dz = Math.sin(angle) * 1.55D;
                quadStrip(buffer, matrix, center.x - dx, center.y, center.z - dz,
                        center.x + dx, center.y, center.z + dz, 0.13D, 255, 30, 25, 235);
            }
        }
        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.enableDepthTest(); RenderSystem.enableCull(); RenderSystem.disableBlend();
    }

    private static void quadStrip(BufferBuilder buffer, Matrix4f matrix, double x0, double y, double z0,
                                  double x1, double y1, double z1, double halfWidth,
                                  int red, int green, int blue, int alpha) {
        double length = Math.max(1.0E-6D, Math.hypot(x1 - x0, z1 - z0));
        double px = -(z1 - z0) / length * halfWidth, pz = (x1 - x0) / length * halfWidth;
        vertex(buffer, matrix, x0 + px, y, z0 + pz, red, green, blue, alpha);
        vertex(buffer, matrix, x1 + px, y1, z1 + pz, red, green, blue, alpha);
        vertex(buffer, matrix, x1 - px, y1, z1 - pz, red, green, blue, alpha);
        vertex(buffer, matrix, x0 - px, y, z0 - pz, red, green, blue, alpha);
    }

    private static double minimumRange(MortarEntity mortar) {
        BlockPos origin = mortar.blockPosition();
        int targetY = (int) Math.floor(mortar.getBoundingBox().minY) - 1;
        for (int radius = 1; radius <= 160; radius++) {
            if (MortarCommands.hasNativeTrajectory(mortar, new BlockPos(origin.getX() + radius, targetY, origin.getZ())))
                return Math.max(0D, radius - 0.5D);
        }
        return 0D;
    }

    private static MortarEntity mortar(UUID id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (id == null || minecraft.level == null) return null;
        for (Entity entity : minecraft.level.entitiesForRendering())
            if (id.equals(entity.getUUID()) && entity instanceof MortarEntity mortar) return mortar;
        return null;
    }

    private static void vertex(BufferBuilder buffer, Matrix4f matrix, double x, double y, double z,
                               int red, int green, int blue, int alpha) {
        buffer.vertex(matrix, (float) x, (float) y, (float) z).color(red, green, blue, alpha).endVertex();
    }
}
