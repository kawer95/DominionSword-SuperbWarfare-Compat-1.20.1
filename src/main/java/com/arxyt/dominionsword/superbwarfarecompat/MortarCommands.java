package com.arxyt.dominionsword.superbwarfarecompat;

import com.arxyt.dominionsword.api.DominionControlApi;
import com.arxyt.dominionsword.api.DominionUnitInventories;
import com.arxyt.dominionsword.api.DominionUnitInventoryAdapter;
import com.mojang.logging.LogUtils;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils;
import com.atsuishio.superbwarfare.init.ModItems;
import com.atsuishio.superbwarfare.init.ModSounds;
import com.atsuishio.superbwarfare.item.misc.FiringParametersItem;
import com.atsuishio.superbwarfare.item.misc.FiringParametersItemKt;
import com.atsuishio.superbwarfare.item.projectile.MortarShellItem;
import com.atsuishio.superbwarfare.tools.SoundTool;
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Method;

final class MortarCommands {
    static final String FIRE_ACTION = "superb_mortar_fire";
    static final String AIM_ACTION = "superb_mortar_aim";
    static final String TARGET_VALID = "DominionSwordMortarTargetValid";
    private static final String TARGET_X = "DominionSwordMortarTargetX", TARGET_Y = "DominionSwordMortarTargetY",
            TARGET_Z = "DominionSwordMortarTargetZ";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Method NATIVE_FIRE = findNativeFire();

    private MortarCommands() {}

    static void beginAim(ServerPlayer player, MortarEntity mortar) {
        if (!hasSelectedUnit(player) || mortar.isWreck()) return;
        syncTarget(player, mortar);
        MortarNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new BeginMortarAimPacket(mortar.getUUID()));
    }

    static void aim(ServerPlayer player, UUID mortarId, BlockPos target) {
        Entity entity = player.serverLevel().getEntity(mortarId);
        if (!(entity instanceof MortarEntity mortar) || mortar.isWreck() || !hasSelectedUnit(player)) return;
        if (mortar.position().distanceToSqr(Vec3.atCenterOf(target)) > 512D * 512D) {
            message(player, "message.dominionsword_superbwarfare_compat.mortar.out_of_range", ChatFormatting.RED);
            return;
        }
        boolean valid = hasNativeTrajectory(mortar, target);
        if (!valid) {
            // Let Superb Warfare provide its own detailed ballistic failure message.
            ItemStack parameters = firingParameters(target);
            mortar.setTarget(parameters, player, "Main");
            mortar.getPersistentData().remove(TARGET_VALID);
            sendTargetState(player, mortar, false, BlockPos.ZERO);
            return;
        }
        Mob operator = nearestSelectedUnit(player, mortar);
        if (operator == null) return;
        if (!DominionControlApi.orderEntityInteraction(player, operator, mortar, AIM_ACTION,
                Long.toString(target.asLong()))) return;
        mortar.getPersistentData().remove(TARGET_VALID);
        sendTargetState(player, mortar, true, target);
        player.displayClientMessage(Component.translatable(
                "message.dominionsword_superbwarfare_compat.mortar.aim_ordered", operator.getDisplayName()), true);
    }

    static BlockPos decodeAimTarget(String payload) {
        try { return BlockPos.of(Long.parseLong(payload)); }
        catch (NumberFormatException ignored) { return null; }
    }

    static boolean completeAim(ServerPlayer player, MortarEntity mortar, BlockPos target) {
        if (target == null || mortar.isWreck()
                || mortar.position().distanceToSqr(Vec3.atCenterOf(target)) > 512D * 512D
                || !hasNativeTrajectory(mortar, target)) {
            mortar.getPersistentData().remove(TARGET_VALID);
            sendTargetState(player, mortar, false, BlockPos.ZERO);
            return false;
        }
        mortar.setTarget(firingParameters(target), player, "Main");
        mortar.getPersistentData().putBoolean(TARGET_VALID, true);
        mortar.getPersistentData().putInt(TARGET_X, target.getX());
        mortar.getPersistentData().putInt(TARGET_Y, target.getY());
        mortar.getPersistentData().putInt(TARGET_Z, target.getZ());
        sendTargetState(player, mortar, true, target);
        SoundTool.playLocalSound(player, ModSounds.CANNON_ZOOM_IN.get(), 2F, 1F);
        player.displayClientMessage(Component.translatable(
                "message.dominionsword_superbwarfare_compat.mortar.target_set", target.getX(), target.getY(), target.getZ())
                .withStyle(ChatFormatting.AQUA), true);
        return true;
    }

    private static ItemStack firingParameters(BlockPos target) {
        ItemStack parameters = new ItemStack(ModItems.FIRING_PARAMETERS.get());
        FiringParametersItemKt.setFiringParameters(parameters, new FiringParametersItem.Parameters(target, 0, false));
        return parameters;
    }

    static boolean hasNativeTrajectory(MortarEntity mortar, BlockPos target) {
        Vec3 impact = target.getCenter().add(0D, -1D, 0D);
        Vec3 preferred = TrajectoryCalculator.calculateLaunchVector(mortar.getEyePosition(), impact,
                mortar.getProjectileVelocity("Main"), mortar.getProjectileGravity("Main"), true);
        Vec3 alternate = TrajectoryCalculator.calculateLaunchVector(mortar.getEyePosition(), impact,
                mortar.getProjectileVelocity("Main"), mortar.getProjectileGravity("Main"), false);
        if (preferred == null || alternate == null) return false;
        float angle = (float) -VehicleVecUtils.getXRotFromVector(preferred);
        float alternateAngle = (float) -VehicleVecUtils.getXRotFromVector(alternate);
        if (angle < -mortar.getTurretMaxPitch() || angle > -mortar.getTurretMinPitch()) {
            if (alternateAngle > -mortar.getTurretMaxPitch() && alternateAngle < -mortar.getTurretMinPitch()) return false;
            return false;
        }
        return angle >= -mortar.getTurretMaxPitch();
    }

    static void requestFire(ServerPlayer player, MortarEntity mortar) {
        syncTarget(player, mortar);
        if (!mortar.getPersistentData().getBoolean(TARGET_VALID)) {
            message(player, "message.dominionsword_superbwarfare_compat.mortar.not_aimed", ChatFormatting.YELLOW);
            return;
        }
        Mob unit = nearestInventoryUnit(player, mortar);
        if (unit == null) {
            message(player, "message.dominionsword_superbwarfare_compat.mortar.no_ammo", ChatFormatting.YELLOW);
            return;
        }
        List<AmmoGroup> groups = ammoGroups(player, unit);
        if (groups.isEmpty()) {
            message(player, "message.dominionsword_superbwarfare_compat.mortar.no_ammo", ChatFormatting.YELLOW);
        } else if (groups.size() == 1) {
            schedule(player, unit, mortar, groups.get(0).sample());
        } else {
            List<OpenMortarAmmoPacket.AmmoEntry> entries = groups.stream()
                    .map(group -> new OpenMortarAmmoPacket.AmmoEntry(group.sample(), group.count())).toList();
            MortarNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new OpenMortarAmmoPacket(mortar.getUUID(), unit.getUUID(), mortar.getDisplayName().getString(), entries));
        }
    }

    static void chooseAmmo(ServerPlayer player, UUID mortarId, UUID unitId, ItemStack sample) {
        Entity mortarEntity = player.serverLevel().getEntity(mortarId);
        Entity unitEntity = player.serverLevel().getEntity(unitId);
        if (!(mortarEntity instanceof MortarEntity mortar) || !(unitEntity instanceof Mob unit)
                || !DominionControlApi.selectedMobs(player).contains(unit) || !(sample.getItem() instanceof MortarShellItem)
                || !containsAmmo(player, unit, sample)) return;
        schedule(player, unit, mortar, sample);
    }

    private static void schedule(ServerPlayer player, Mob unit, MortarEntity mortar, ItemStack sample) {
        CompoundTag serialized = sample.copyWithCount(1).save(new CompoundTag());
        if (DominionControlApi.orderEntityInteraction(player, unit, mortar, FIRE_ACTION, serialized.toString())) {
            player.displayClientMessage(Component.translatable(
                    "message.dominionsword_superbwarfare_compat.mortar.fire_ordered", sample.getHoverName()), true);
        }
    }

    static ItemStack decodeAmmo(String payload) {
        try { return ItemStack.of(TagParser.parseTag(payload)); }
        catch (Exception ignored) { return ItemStack.EMPTY; }
    }

    static boolean canFireNative() { return NATIVE_FIRE != null; }

    static boolean restoreTarget(MortarEntity mortar, ServerPlayer player) {
        CompoundTag data = mortar.getPersistentData();
        if (!data.getBoolean(TARGET_VALID) || !data.contains(TARGET_X) || !data.contains(TARGET_Y) || !data.contains(TARGET_Z)) return false;
        BlockPos target = new BlockPos(data.getInt(TARGET_X), data.getInt(TARGET_Y), data.getInt(TARGET_Z));
        if (!hasNativeTrajectory(mortar, target)) return false;
        ItemStack parameters = new ItemStack(ModItems.FIRING_PARAMETERS.get());
        FiringParametersItemKt.setFiringParameters(parameters, new FiringParametersItem.Parameters(target, 0, false));
        mortar.setTarget(parameters, player, "Main");
        return true;
    }

    static boolean fireNative(MortarEntity mortar, Mob shooter) {
        if (NATIVE_FIRE == null) {
            LOGGER.error("Mortar fire bridge is unavailable for {}", mortar.getUUID());
            return false;
        }
        try {
            if (NATIVE_FIRE.getParameterCount() == 2) NATIVE_FIRE.invoke(mortar, shooter, "Main");
            else NATIVE_FIRE.invoke(mortar, shooter, "Main", mortar.getTargetPos().getCenter());
            mortar.getEntityData().set(MortarEntity.NEED_RESET_TARGET, false);
            boolean started = mortar.getEntityData().get(MortarEntity.FIRE_TIME) > 0;
            if (!started) LOGGER.error("Mortar {} rejected native fire after loading shell {}",
                    mortar.getUUID(), mortar.getItems().get(0));
            return started;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.error("Mortar {} native fire invocation failed", mortar.getUUID(), exception);
            return false;
        }
    }

    private static Method findNativeFire() {
        try {
            Method method;
            try {
                // Released 0.8.9 builds own the target in ArtilleryEntity and expose this signature.
                method = MortarEntity.class.getDeclaredMethod("vehicleShoot",
                        net.minecraft.world.entity.LivingEntity.class, String.class);
            } catch (NoSuchMethodException releasedSignatureMissing) {
                // Newer source snapshots additionally pass the target vector.
                method = MortarEntity.class.getDeclaredMethod("vehicleShoot",
                        net.minecraft.world.entity.LivingEntity.class, String.class, Vec3.class);
            }
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.error("Unable to bind Superb Warfare mortar native fire method", exception);
            return null;
        }
    }

    static void returnAmmo(ServerPlayer player, Mob unit, ItemStack stack) {
        if (stack.isEmpty()) return;
        DominionUnitInventoryAdapter adapter = DominionUnitInventories.adapter(unit);
        IItemHandler inventory = adapter == null ? null : adapter.inventory(player, unit);
        ItemStack remainder = inventory == null ? stack : ItemHandlerHelper.insertItemStacked(inventory, stack, false);
        if (adapter != null && remainder.getCount() != stack.getCount()) adapter.onPut(player, unit, -1, stack.copyWithCount(stack.getCount() - remainder.getCount()));
        if (!remainder.isEmpty()) unit.spawnAtLocation(remainder);
    }

    static BlockPos storedTarget(MortarEntity mortar) {
        CompoundTag data = mortar.getPersistentData();
        return data.getBoolean(TARGET_VALID) && data.contains(TARGET_X) && data.contains(TARGET_Y) && data.contains(TARGET_Z)
                ? new BlockPos(data.getInt(TARGET_X), data.getInt(TARGET_Y), data.getInt(TARGET_Z)) : null;
    }

    private static void syncTarget(ServerPlayer player, MortarEntity mortar) {
        BlockPos target = storedTarget(mortar);
        sendTargetState(player, mortar, target != null, target == null ? BlockPos.ZERO : target);
    }

    private static void sendTargetState(ServerPlayer player, MortarEntity mortar, boolean valid, BlockPos target) {
        MortarNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new MortarTargetStatePacket(mortar.getUUID(), valid, target));
    }

    static boolean containsAmmo(ServerPlayer player, Mob unit, ItemStack wanted) {
        DominionUnitInventoryAdapter adapter = DominionUnitInventories.adapter(unit);
        if (adapter == null || !adapter.canOpen(player, unit)) return false;
        IItemHandler inventory = adapter.inventory(player, unit);
        if (inventory == null) return false;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.getItem() instanceof MortarShellItem && ItemStack.isSameItemSameTags(stack, wanted)
                    && !inventory.extractItem(slot, 1, true).isEmpty()) return true;
        }
        return false;
    }

    static ExtractedAmmo extractAmmo(ServerPlayer player, Mob unit, ItemStack wanted) {
        DominionUnitInventoryAdapter adapter = DominionUnitInventories.adapter(unit);
        if (adapter == null || !adapter.canOpen(player, unit)) return null;
        IItemHandler inventory = adapter.inventory(player, unit);
        if (inventory == null) return null;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!(stack.getItem() instanceof MortarShellItem) || !ItemStack.isSameItemSameTags(stack, wanted)) continue;
            ItemStack simulated = inventory.extractItem(slot, 1, true);
            if (simulated.isEmpty()) continue;
            ItemStack extracted = inventory.extractItem(slot, 1, false);
            if (extracted.isEmpty()) return null;
            adapter.onTake(player, unit, slot, extracted);
            return new ExtractedAmmo(extracted.copyWithCount(1));
        }
        return null;
    }

    private static List<AmmoGroup> ammoGroups(ServerPlayer player, Mob unit) {
        DominionUnitInventoryAdapter adapter = DominionUnitInventories.adapter(unit);
        if (adapter == null || !adapter.canOpen(player, unit)) return List.of();
        IItemHandler inventory = adapter.inventory(player, unit);
        if (inventory == null) return List.of();
        List<AmmoGroup> groups = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!(stack.getItem() instanceof MortarShellItem) || stack.isEmpty()) continue;
            AmmoGroup matching = groups.stream().filter(group -> ItemStack.isSameItemSameTags(group.sample(), stack)).findFirst().orElse(null);
            if (matching == null) groups.add(new AmmoGroup(stack.copyWithCount(1), stack.getCount()));
            else matching.add(stack.getCount());
        }
        return groups;
    }

    private static Mob nearestInventoryUnit(ServerPlayer player, MortarEntity mortar) {
        return DominionControlApi.selectedMobs(player).stream()
                .filter(unit -> {
                    DominionUnitInventoryAdapter adapter = DominionUnitInventories.adapter(unit);
                    return adapter != null && adapter.canOpen(player, unit);
                })
                .min(Comparator.comparingDouble(unit -> unit.distanceToSqr(mortar))).orElse(null);
    }

    private static Mob nearestSelectedUnit(ServerPlayer player, MortarEntity mortar) {
        return DominionControlApi.selectedMobs(player).stream()
                .filter(Mob::isAlive)
                .min(Comparator.comparingDouble(unit -> unit.distanceToSqr(mortar))).orElse(null);
    }

    private static boolean hasSelectedUnit(ServerPlayer player) { return !DominionControlApi.selectedMobs(player).isEmpty(); }

    static void message(ServerPlayer player, String key, ChatFormatting color, Object... args) {
        player.displayClientMessage(Component.translatable(key, args).withStyle(color), true);
    }

    private static final class AmmoGroup {
        private final ItemStack sample;
        private int count;
        private AmmoGroup(ItemStack sample, int count) { this.sample = sample; this.count = count; }
        ItemStack sample() { return sample; }
        int count() { return count; }
        void add(int amount) { count += amount; }
    }

    record ExtractedAmmo(ItemStack stack) {}
}
