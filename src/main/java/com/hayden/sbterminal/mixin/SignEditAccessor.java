package com.hayden.sbterminal.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the mod read and fill the text lines of an open sign editor. Nothing is sent until you press Done. */
@Mixin(AbstractSignEditScreen.class)
public interface SignEditAccessor {
    @Accessor("messages")
    String[] sbterminal$getMessages();
}
