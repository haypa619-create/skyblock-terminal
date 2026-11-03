package com.hayden.sbterminal;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
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
    private static final Pattern PURSE = Pattern.compile("(?:Purse|Piggy):\\s*([\\d,]+(?:\\.\\d+)?)");

    @Override
    public void onInitializeClient() {
        CONFIG = Config.load(FabricLoader.getInstance().getConfigDir());
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sbterminal", "main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sbterminal.open", InputConstants.Type.KEYSYM, InputConstants.KEY_B, cat));
        Feed.start();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            boolean pressed = false;
            while (openKey.consumeClick()) pressed = true;
            if (pressed) mc.gui.setScreen(new TerminalScreen());
            if (++ticks % 20 == 0) readPurse(mc);
        });
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
