package com.hayden.sbterminal.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads which slot the mouse is over, so B on a hovered item opens it in the terminal. Read-only. */
@Mixin(AbstractContainerScreen.class)
public interface HoveredSlotAccessor {
    @Accessor("hoveredSlot")
    Slot sbterminal$getHoveredSlot();
}
