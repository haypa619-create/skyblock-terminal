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
    public static Portfolio PORT;

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
        PORT = Portfolio.load(FabricLoader.getInstance().getConfigDir());
        Feed.start();
        Guide.init();
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String text = message.getString();
            long nowMs = System.currentTimeMillis();
            if (Market.strip(text).contains("reached the daily limit of coins you may create orders for")) onLimitHit(nowMs);
            int before = PORT.ledger.size();
            if (PORT.onChat(text, nowMs)) {
                if (PORT.ledger.size() > before) onCounted(nowMs);
                PORT.save();
                java.util.regex.Matcher m = Portfolio.FILLED.matcher(Market.strip(text).trim());
                if (CONFIG.alerts && m.matches()) alert("Order filled: " + m.group(2) + "x " + m.group(3) + ". Claim it in Manage Orders.", false);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Screen p = pending;
            if (p != null) { pending = null; mc.gui.setScreen(p); }
            boolean pressed = false;
            while (openKey.consumeClick()) pressed = true;
            if (pressed) mc.gui.setScreen(TerminalScreen.home());
            if (++ticks % 20 == 0) readPurse(mc);
            if (ticks % 40 == 0 && CONFIG.alerts) checkOutbid(mc);
            if (ticks % 40 == 20) checkPriceAlerts(mc);
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
                .executes(c -> { pending = TerminalScreen.home(); return 1; })
                .then(argument("item", StringArgumentType.greedyString()).executes(c -> {
                    String q = StringArgumentType.getString(c, "item");
                    List<String> m = Items.find(q, Feed.snapshot.products().keySet(), Feed.names, Feed::rankVolume, 1);
                    pending = TerminalScreen.forItem(m.isEmpty() ? null : m.get(0));
                    return 1;
                }))));
    }

    /** Active limit amount: set/learned, else the assumed default (shown, never enforced). */
    public static double limitAmount() { return CONFIG.limitCoins > 0 ? CONFIG.limitCoins : Limit.ASSUMED; }

    public static long windowStart(long now) { return Limit.windowStart(now, CONFIG.resetMinuteUtc); }

    /** Hypixel said no. The amount used then is at least where the cap is, and tells us the window is still open. */
    private static void onLimitHit(long now) {
        PORT.blockedAt = now;
        long start = windowStart(now);
        double used = PORT.usedSince(start);
        String note;
        if (PORT.trackingSince <= start && used >= 1e9) {
            if (!"manual".equals(CONFIG.limitSource) && used > CONFIG.limitCoins) {
                CONFIG.limitCoins = used;
                CONFIG.limitSource = "learned";
                CONFIG.save();
            }
            note = "Hypixel's daily limit reached. I counted " + Fmt.px(used) + " today, so your cap is at least that"
                    + ("manual".equals(CONFIG.limitSource) ? "." : " (saved as an estimate; LIMIT <amount> sets the exact number).");
        } else {
            note = "Hypixel's daily limit reached. I started tracking after this window began, so I did not learn the number. LIMIT <amount> sets it.";
        }
        PORT.save();
        alert(note + " Resets in " + Limit.countdown(Limit.nextReset(now, CONFIG.resetMinuteUtc) - now) + " (by my reset time).", true);
    }

    /** An order or instant trade was counted. If it went through after a block, the reset time was wrong or has passed. */
    private static void onCounted(long now) {
        long b = PORT.blockedAt;
        if (b <= 0) return;
        PORT.blockedAt = 0;
        if (now - b > 30 * 3_600_000L || now - b < 0) return;
        if (b >= windowStart(now) && !"manual".equals(CONFIG.resetSource)) {
            // Blocked and allowed again inside what we thought was one window: the reset happened in between.
            int mid = Limit.minuteOfDayUtc(b + (now - b) / 2);
            CONFIG.resetMinuteUtc = mid;
            CONFIG.resetSource = "learned";
            CONFIG.save();
            java.time.ZoneId z = java.time.ZoneId.systemDefault();
            alert("Limit reset seen between " + Limit.clock(Limit.minuteOfDayUtc(b), z, now) + " and " + Limit.clock(Limit.minuteOfDayUtc(now), z, now)
                    + ". Reset time set to " + Limit.clock(mid, z, now) + ". LIMIT RESET 7:00 PM sets it exactly.", false);
        }
    }

    private static final java.util.Set<String> outbid = new java.util.HashSet<>();

    /** Tells you once when one of your open orders drops off the top of the book. */
    private static void checkOutbid(Minecraft mc) {
        if (mc.player == null) return;
        java.util.Set<String> behind = new java.util.HashSet<>();
        for (Portfolio.Order o : PORT.orderList()) {
            String id = Feed.idForName(o.name);
            if (id == null || Portfolio.status(o, Feed.snapshot.get(id)) != Portfolio.Status.BEHIND) continue;
            String k = o.at + o.name;
            behind.add(k);
            if (outbid.add(k)) {
                Market.Product p = Feed.snapshot.get(id);
                alert((o.buy ? "Outbid on " : "Undercut on ") + o.name + ": yours " + Fmt.px(o.price) + ", best "
                        + Fmt.px(o.buy ? p.bid() : p.ask()), true);
            }
        }
        outbid.retainAll(behind);
    }

    /** Price alerts: each fires once, then waits to be re-armed in ALRT. */
    private static void checkPriceAlerts(Minecraft mc) {
        if (mc.player == null) return;
        boolean changed = false;
        for (Alerts.Rule r : CONFIG.priceAlerts) {
            if (r.fired) continue;
            Market.Product p = Feed.snapshot.get(r.id);
            if (r.f() == Alerts.Field.CHG1H) Feed.day(r.id);
            double x = Alerts.value(r.f(), p, Feed.change(r.id, 3_600_000L));
            if (!Alerts.test(r.op, x, r.value)) continue;
            r.fired = true;
            r.firedAt = System.currentTimeMillis();
            changed = true;
            String now = r.f() == Alerts.Field.SPR || r.f() == Alerts.Field.CHG1H ? String.format(java.util.Locale.US, "%.1f%%", x) : Fmt.px(x);
            alert("ALERT " + Feed.name(r.id) + ": " + r.describe() + " (now " + now + ")", false);
        }
        if (changed) CONFIG.save();
    }

    static void alert(String text, boolean bad) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[SBT] " + text)
                    .withStyle(bad ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.GREEN));
            try {
                mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        bad ? net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASS : net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, bad ? 0.7f : 1.4f));
            } catch (Exception ignored) {
            }
        });
    }

    /** Opens a screen on the next tick (safe from any event). */
    static void show(Screen s) { pending = s; }

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
