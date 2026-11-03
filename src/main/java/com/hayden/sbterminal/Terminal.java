package com.hayden.sbterminal;

import com.mojang.blaze3d.platform.InputConstants;
import com.hayden.sbterminal.mixin.HoveredSlotAccessor;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyBlock Terminal: a Bloomberg-style Bazaar terminal inside Minecraft. Press B.
 * Read-only: it shows data and never clicks, buys or sells anything.
 */
public final class Terminal implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("sbterminal");
    public static Config CONFIG;
    public static volatile double purse = -1;

    private static KeyMapping openKey;
    private static int ticks = 0;
    /** Screens opened from a command wait one tick, or closing the chat would overwrite them. */
    private static volatile Screen pending;
    private static final Pattern PURSE = Pattern.compile("(?:Purse|Piggy):\\s*([\\d,]+(?:\\.\\d+)?)");

    @Override
    public void onInitializeClient() {
        CONFIG = Config.load(FabricLoader.getInstance().getConfigDir());
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sbterminal", "main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sbterminal.open", InputConstants.Type.KEYSYM, InputConstants.KEY_B, cat));
        Feed.start();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Screen p = pending;
            if (p != null) { pending = null; mc.gui.setScreen(p); }
            boolean pressed = false;
            while (openKey.consumeClick()) pressed = true;
            if (pressed) mc.gui.setScreen(new TerminalScreen());
            if (++ticks % 20 == 0) readPurse(mc);
        });

        // Hover any item in any menu or your inventory and press B: open it in the terminal.
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> cs)) return;
            ScreenKeyboardEvents.afterKeyPress(screen).register((s, e) -> {
                if (!openKey.matches(e) || s.getFocused() instanceof EditBox) return;
                Slot slot = ((HoveredSlotAccessor) cs).sbterminal$getHoveredSlot();
                if (slot == null || slot.getItem().isEmpty()) return;
                String id = bazaarId(slot.getItem());
                if (id == null) return;
                pending = TerminalScreen.forItem(id);
            });
        });

        // /sbt            open the terminal
        // /sbt <item>     open it on an item
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> d.register(literal("sbt")
                .executes(c -> { pending = new TerminalScreen(); return 1; })
                .then(argument("item", StringArgumentType.greedyString()).executes(c -> {
                    String q = StringArgumentType.getString(c, "item");
                    List<String> m = Items.find(q, Feed.snapshot.products().keySet(), Feed.names, Feed::rankVolume, 1);
                    pending = TerminalScreen.forItem(m.isEmpty() ? null : m.get(0));
                    return 1;
                }))));
    }

    /** The Bazaar product id for an item stack: its SkyBlock id, or a name match. null if it isn't on the Bazaar. */
    static String bazaarId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            var tag = data.copyTag();
            String id = tag.getStringOr("id", "");
            if (id.isEmpty()) id = tag.getCompoundOrEmpty("ExtraAttributes").getStringOr("id", "");
            if (!id.isEmpty() && Feed.snapshot.get(id) != null) return id;
        }
        String name = Items.norm(stack.getHoverName().getString());
        for (var e : Feed.names.entrySet()) {
            if (Items.norm(e.getValue()).equals(name) && Feed.snapshot.get(e.getKey()) != null) return e.getKey();
        }
        return null;
    }

    /** Purse from the SkyBlock sidebar, for the header. */
    private static void readPurse(Minecraft mc) {
        if (mc.level == null) return;
        try {
            Scoreboard sb = mc.level.getScoreboard();
            Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
            if (obj == null) return;
            List<String> lines = new ArrayList<>();
            for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
                PlayerTeam t = sb.getPlayersTeam(e.owner());
                lines.add(t == null ? e.owner() : t.getPlayerPrefix().getString() + e.owner() + t.getPlayerSuffix().getString());
            }
            for (String raw : lines) {
                Matcher m = PURSE.matcher(Market.strip(raw).replaceAll("[^\\x20-\\x7E]", ""));
                if (m.find()) { purse = Double.parseDouble(m.group(1).replace(",", "")); return; }
            }
        } catch (Exception ignored) {
        }
    }
}
