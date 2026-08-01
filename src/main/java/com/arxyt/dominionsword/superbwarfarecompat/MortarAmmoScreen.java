package com.arxyt.dominionsword.superbwarfarecompat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

final class MortarAmmoScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private final OpenMortarAmmoPacket data;
    private int left, top, panelWidth, visibleRows, scroll;

    private MortarAmmoScreen(OpenMortarAmmoPacket data) {
        super(Component.translatable("screen.dominionsword_superbwarfare_compat.mortar_ammo", data.mortarName()));
        this.data = data;
    }

    static void open(OpenMortarAmmoPacket data) { Minecraft.getInstance().setScreen(new MortarAmmoScreen(data)); }

    @Override
    protected void init() {
        panelWidth = Mth.clamp(220, 180, width - 20);
        visibleRows = Math.max(1, Math.min(data.entries().size(), (height - 76) / ROW_HEIGHT));
        left = (width - panelWidth) / 2;
        top = (height - (34 + visibleRows * ROW_HEIGHT + 28)) / 2;
        scroll = Mth.clamp(scroll, 0, Math.max(0, data.entries().size() - visibleRows));
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int bottom = top + 34 + visibleRows * ROW_HEIGHT + 28;
        graphics.fill(left, top, left + panelWidth, bottom, 0xF20A0D12);
        graphics.fill(left, top, left + panelWidth, top + 1, 0xFFFFC83D);
        graphics.drawCenteredString(font, title, width / 2, top + 11, 0xFFFFFFFF);
        for (int row = 0; row < visibleRows; row++) {
            int index = scroll + row;
            if (index >= data.entries().size()) break;
            int y = top + 30 + row * ROW_HEIGHT;
            boolean hovered = mouseX >= left + 4 && mouseX < left + panelWidth - 4 && mouseY >= y && mouseY < y + ROW_HEIGHT;
            graphics.fill(left + 4, y, left + panelWidth - 4, y + ROW_HEIGHT - 2, hovered ? 0xFF655226 : 0xFF171B22);
            OpenMortarAmmoPacket.AmmoEntry entry = data.entries().get(index);
            ItemStack stack = entry.sample();
            graphics.renderItem(stack, left + 8, y + 3);
            graphics.drawString(font, stack.getHoverName(), left + 31, y + 8, 0xFFFFFFFF, false);
            String count = "×" + entry.count();
            graphics.drawString(font, count, left + panelWidth - 10 - font.width(count), y + 8, 0xFFFFD75A, false);
            if (hovered) graphics.renderTooltip(font, stack, mouseX, mouseY);
        }
        int cancelY = top + 34 + visibleRows * ROW_HEIGHT;
        boolean cancelHovered = mouseX >= left + 4 && mouseX < left + panelWidth - 4 && mouseY >= cancelY && mouseY < cancelY + 22;
        graphics.fill(left + 4, cancelY, left + panelWidth - 4, cancelY + 22, cancelHovered ? 0xFF5A6572 : 0xFF252C35);
        graphics.drawCenteredString(font, Component.translatable("screen.dominionsword_superbwarfare_compat.cancel"), width / 2, cancelY + 7, 0xFFFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) { onClose(); return true; }
        int row = (int) ((mouseY - top - 30) / ROW_HEIGHT);
        if (mouseX >= left + 4 && mouseX < left + panelWidth - 4 && row >= 0 && row < visibleRows) {
            int index = scroll + row;
            if (index < data.entries().size()) {
                MortarNetwork.CHANNEL.sendToServer(new MortarAmmoChoicePacket(data.mortarId(), data.unitId(), data.entries().get(index).sample()));
                onClose();
                return true;
            }
        }
        onClose();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, Math.max(0, data.entries().size() - visibleRows));
        return true;
    }
}
