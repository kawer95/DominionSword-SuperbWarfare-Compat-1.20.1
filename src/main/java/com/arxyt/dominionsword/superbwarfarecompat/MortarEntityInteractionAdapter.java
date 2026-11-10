package com.arxyt.dominionsword.superbwarfarecompat;

import com.arxyt.dominionsword.api.DominionEntityInteractionAdapter;
import com.arxyt.dominionsword.api.DominionEntityInteractionContext;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.item.projectile.MortarShellItem;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

final class MortarEntityInteractionAdapter implements DominionEntityInteractionAdapter {
    @Override public int priority() { return 300; }

    @Override
    public boolean supports(DominionEntityInteractionContext context) {
        return (MortarCommands.FIRE_ACTION.equals(context.actionId())
                || MortarCommands.AIM_ACTION.equals(context.actionId())) && context.target() instanceof MortarEntity;
    }

    @Override
    public Vec3 approachPosition(DominionEntityInteractionContext context) {
        return new Vec3(context.target().getX(), context.target().getBoundingBox().minY, context.target().getZ());
    }

    @Override
    public boolean canInteract(DominionEntityInteractionContext context) {
        double dx = context.unit().getX() - context.target().getX();
        double dz = context.unit().getZ() - context.target().getZ();
        return dx * dx + dz * dz <= 12.25D
                && Math.abs(context.unit().getY() - context.target().getBoundingBox().minY) <= 3D;
    }

    @Override
    public InteractionResult interact(DominionEntityInteractionContext context) {
        if (!(context.target() instanceof MortarEntity mortar) || context.commander() == null) return InteractionResult.PASS;
        if (MortarCommands.AIM_ACTION.equals(context.actionId())) {
            return MortarCommands.completeAim(context.commander(), mortar,
                    MortarCommands.decodeAimTarget(context.payload())) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        if (mortar.isWreck() || !mortar.getPersistentData().getBoolean(MortarCommands.TARGET_VALID)) return InteractionResult.FAIL;
        if (!MortarCommands.canFireNative() || !MortarCommands.restoreTarget(mortar, context.commander())) return InteractionResult.FAIL;
        if (mortar.getEntityData().get(MortarEntity.FIRE_TIME) != 0 || !mortar.getItems().get(0).isEmpty()) {
            MortarCommands.message(context.commander(), "message.dominionsword_superbwarfare_compat.mortar.busy", ChatFormatting.YELLOW);
            return InteractionResult.FAIL;
        }
        ItemStack wanted = MortarCommands.decodeAmmo(context.payload());
        if (!(wanted.getItem() instanceof MortarShellItem)) return InteractionResult.FAIL;
        MortarCommands.ExtractedAmmo extracted = MortarCommands.extractAmmo(context.commander(), context.unit(), wanted);
        if (extracted == null) {
            MortarCommands.message(context.commander(), "message.dominionsword_superbwarfare_compat.mortar.ammo_missing", ChatFormatting.YELLOW);
            return InteractionResult.FAIL;
        }
        mortar.getItems().set(0, extracted.stack());
        if (MortarCommands.fireNative(mortar, context.unit())) return InteractionResult.SUCCESS;
        ItemStack rollback = mortar.getItems().get(0);
        mortar.getItems().set(0, ItemStack.EMPTY);
        MortarCommands.returnAmmo(context.commander(), context.unit(), rollback);
        MortarCommands.message(context.commander(), "message.dominionsword_superbwarfare_compat.mortar.fire_failed", ChatFormatting.RED);
        return InteractionResult.FAIL;
    }
}
