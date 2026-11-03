package com.hayden.sbterminal.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a menu is drawn, so the slot to click can be outlined. Read-only. */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor("leftPos")
    int sbterminal$getLeftPos();

    @Accessor("topPos")
    int sbterminal$getTopPos();
}
