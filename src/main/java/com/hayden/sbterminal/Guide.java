package com.hayden.sbterminal;

import com.hayden.sbterminal.mixin.ContainerScreenAccessor;
import com.hayden.sbterminal.mixin.SignEditAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The hand-off from the terminal's ticket to Hypixel's Bazaar menus.
 *
 * You press BUY or SELL on an item page. The mod runs /bz for that item, points at the slot to
 * click at each step, types the amount and price into the signs, and you confirm. It never
 * clicks on its own: one key press (Enter) or one mouse click is exactly one click.
 */
public final class Guide {
    public static final Ticket TICKET = new Ticket();

    private static long lastEnter = 0;

    private Guide() {}

    public static void init() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!isGuideScreen(screen)) return;
            TICKET.inMenus();
            ScreenEvents.remove(screen).register(s -> TICKET.leftMenus(System.currentTimeMillis()));
            if (screen instanceof AbstractSignEditScreen) {
                fillSign(screen);
                // Enter on a sign the mod filled in = Done (vanilla Enter only moves to the next line)
                ScreenKeyboardEvents.allowKeyPress(screen).register((s, e) -> {
                    if (!isEnter(e.key()) || TICKET.current(System.currentTimeMillis()) == null) return true;
                    String[] lines = ((SignEditAccessor) s).sbterminal$getMessages();
                    if (lines.length == 0 || Market.strip(lines[0]).isBlank() || !debounce()) return true;
                    s.onClose();
                    return false;
                });
            }
            if (screen instanceof AbstractContainerScreen<?> cs) {
                if (Sync.isOrdersTitle(screen.getTitle().getString())) watchOrders(cs);
                else maybeStartSync(screen.getTitle().getString());
                ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> highlight(cs, g));
                ScreenKeyboardEvents.afterKeyPress(screen).register((s, e) -> {
                    if (s.getFocused() instanceof EditBox) return;
                    if (isEnter(e.key())) clickGreenSlot(cs);
                });
                addButtons(screen);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (TICKET.abandoned(System.currentTimeMillis())) TICKET.clear();
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String t = Market.strip(message.getString());
            Ticket.Active a = TICKET.current(System.currentTimeMillis());
            if (a == null) return;
            if (t.startsWith("[Bazaar]") && (t.contains(" Setup!") || t.contains("Bought ") || t.contains("Sold "))) {
                TICKET.clear();
            } else if (t.startsWith("[Bazaar] Your price isn't competitive enough")) {
                onRejected(a);
            } else if (a.mode() == Ticket.Mode.CANCEL && t.startsWith("[Bazaar] Cancelled!")) {
                // Reprice, step 2: the old order is down. Put it back up at the best price right now.
                Market.Product p = Feed.snapshot.get(a.id());
                double price = p == null ? a.price() : Ticket.joinPrice(a.side(), p.bid(), p.ask());
                TICKET.start(new Ticket.Active(a.id(), a.itemName(), a.side(), Ticket.Mode.ORDER, price, a.amount(), System.currentTimeMillis()));
                say("Cancelled. Press Enter to put it back up at " + Ticket.formatPrice(price) + ".");
            }
        });
    }

    /** Starts the guide and opens /bz for the item. One click on the terminal button = this one call. */
    public static void launch(Ticket.Active a) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        TICKET.start(a);
        mc.getConnection().sendCommand(a.mode() == Ticket.Mode.CANCEL ? "bz" : "bz " + a.itemName());
    }

    /** How many of an item you carry (for sell offers, which list everything you hold). */
    public static long carrying(String id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        long n = 0;
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && id.equals(Terminal.bazaarId(st))) n += st.getCount();
        }
        return n;
    }

    // ---------- order sync ----------

    private static final long STALE_MS = 90_000;

    /** Opening the Bazaar with no order in progress and a stale order list: point at Manage Orders. */
    private static void maybeStartSync(String title) {
        String t = Market.strip(title).toLowerCase(java.util.Locale.US);
        if (!t.contains("bazaar") || t.contains("➜")) return; // the main Bazaar page only
        long now = System.currentTimeMillis();
        if (TICKET.current(now) != null) return;
        if (now - Terminal.PORT.syncedAt < STALE_MS) return;
        TICKET.start(new Ticket.Active("", "", Ticket.Side.BUY, Ticket.Mode.SYNC, 0, 0, now));
    }

    /** Reads Manage Orders whenever it is open, however you got there. */
    private static void watchOrders(AbstractContainerScreen<?> screen) {
        final int[] ticks = {0};
        final String[] lastSig = {null};
        ScreenEvents.afterTick(screen).register(s -> {
            ticks[0]++;
            if (ticks[0] < 6 || ticks[0] % 10 != 0) return; // let Hypixel fill the menu first
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            List<String> names = new ArrayList<>();
            List<List<String>> lores = new ArrayList<>();
            for (Slot slot : screen.getMenu().slots) {
                if (slot.container == mc.player.getInventory()) continue;
                ItemStack st = slot.getItem();
                if (st.isEmpty()) continue;
                names.add(st.getHoverName().getString());
                List<String> lore = new ArrayList<>();
                ItemLore il = st.get(DataComponents.LORE);
                if (il != null) for (Component c : il.lines()) lore.add(c.getString());
                lores.add(lore);
            }
            if (names.isEmpty()) return; // menu not filled yet
            List<Sync.Order> orders = Sync.parseAll(names, lores);
            String sig = orders.toString();
            Ticket.Active a = TICKET.current(System.currentTimeMillis());
            if (a != null && a.mode() == Ticket.Mode.SYNC) TICKET.clear();
            if (sig.equals(lastSig[0])) return;
            lastSig[0] = sig;
            Terminal.PORT.syncFrom(orders, System.currentTimeMillis());
            Terminal.PORT.save();
        });
    }

    // ---------- signs ----------

    private static void fillSign(Screen screen) {
        String[] lines = ((SignEditAccessor) screen).sbterminal$getMessages();
        String text = TICKET.prefillFor(lines, System.currentTimeMillis());
        if (text != null) lines[0] = text;
    }

    // ---------- menus ----------

    private static boolean isGuideScreen(Screen screen) {
        return screen instanceof AbstractSignEditScreen
                || screen instanceof AbstractContainerScreen<?> && Ticket.isBazaarTitle(screen.getTitle().getString());
    }

    private static Slot greenSlot(AbstractContainerScreen<?> screen) {
        Ticket.Active a = TICKET.current(System.currentTimeMillis());
        Minecraft mc = Minecraft.getInstance();
        if (a == null || mc.player == null) return null;
        List<Slot> slots = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<List<String>> lores = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == mc.player.getInventory()) continue; // menu slots only
            slots.add(slot);
            ItemStack st = slot.getItem();
            names.add(st.isEmpty() ? null : st.getHoverName().getString());
            List<String> lore = new ArrayList<>();
            ItemLore il = st.isEmpty() ? null : st.get(DataComponents.LORE);
            if (il != null) for (Component c : il.lines()) lore.add(c.getString());
            lores.add(lore);
        }
        int i = Ticket.pickSlot(screen.getTitle().getString(), names, lores, a);
        return i < 0 ? null : slots.get(i);
    }

    private static boolean isEnter(int key) { return key == 257 || key == 335; }

    private static boolean debounce() {
        long now = System.currentTimeMillis();
        if (now - lastEnter < 250) return false;
        lastEnter = now;
        return true;
    }

    /** Enter in a Bazaar menu clicks the green slot once. Never more than one click per press. */
    private static void clickGreenSlot(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        Slot target = greenSlot(screen);
        Ticket.Active a = TICKET.current(System.currentTimeMillis());
        if (target == null && a != null && (a.mode() == Ticket.Mode.ORDER || a.mode() == Ticket.Mode.INSTA)
                && mc.getConnection() != null && debounce()) {
            // Nothing to click here (e.g. still in Manage Orders after a reprice cancel): one Enter = one /bz.
            mc.getConnection().sendCommand("bz " + a.itemName());
            return;
        }
        if (target == null || mc.gameMode == null || mc.player == null || !debounce()) return;
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, target.index, 0, ContainerInput.PICKUP, mc.player);
    }

    private static void highlight(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        Ticket.Active a = TICKET.current(System.currentTimeMillis());
        if (a == null) return;
        Minecraft mc = Minecraft.getInstance();
        Slot target = greenSlot(screen);
        if (a.mode() == Ticket.Mode.SYNC) {
            String msg = "Syncing your orders: click Manage Orders (or press Enter)";
            if (target != null) {
                ContainerScreenAccessor ac = (ContainerScreenAccessor) screen;
                int sx = ac.sbterminal$getLeftPos() + target.x, sy = ac.sbterminal$getTopPos() + target.y;
                double ph = (System.currentTimeMillis() % 1000) / 1000.0;
                int al = 0x60 + (int) (0x50 * (0.5 + 0.5 * Math.sin(ph * 2 * Math.PI)));
                g.fill(sx, sy, sx + 16, sy + 16, (al << 24) | 0x55FF55);
                g.outline(sx - 2, sy - 2, 20, 20, 0xFF55FF55);
                g.outline(sx - 1, sy - 1, 18, 18, 0xFF55FF55);
            } else if (Sync.isOrdersTitle(screen.getTitle().getString())) msg = "Reading your orders...";
            else msg = "Open the main Bazaar page to sync your orders";
            g.centeredText(mc.font, Component.literal(msg).withStyle(ChatFormatting.AQUA), screen.width / 2, 8, 0xFFFFFFFF);
            return;
        }
        if (a.mode() == Ticket.Mode.CANCEL) {
            String msg = target == null ? "Reprice " + a.itemName() + ": open Manage Orders"
                    : "Reprice " + a.itemName() + ": click the green slot to cancel the old order (or press Enter)";
            if (target != null) drawGreen(screen, g, target);
            g.centeredText(mc.font, Component.literal(msg).withStyle(ChatFormatting.GOLD), screen.width / 2, 8, 0xFFFFFFFF);
            return;
        }
        String what = (a.mode() == Ticket.Mode.INSTA ? (a.side() == Ticket.Side.BUY ? "Buy now " : "Sell now ")
                : a.side() == Ticket.Side.BUY ? "Buy order " : "Sell offer ") + a.itemName();
        if (target == null) {
            g.centeredText(mc.font, Component.literal(what + ": press Enter to open it in the Bazaar").withStyle(ChatFormatting.GRAY), screen.width / 2, 8, 0xFFFFFFFF);
            return;
        }
        ContainerScreenAccessor acc = (ContainerScreenAccessor) screen;
        int x = acc.sbterminal$getLeftPos() + target.x;
        int y = acc.sbterminal$getTopPos() + target.y;
        double t = (System.currentTimeMillis() % 1000) / 1000.0;
        int alpha = 0x60 + (int) (0x50 * (0.5 + 0.5 * Math.sin(t * 2 * Math.PI)));
        g.fill(x, y, x + 16, y + 16, (alpha << 24) | 0x55FF55);
        g.outline(x - 2, y - 2, 20, 20, 0xFF55FF55);
        g.outline(x - 1, y - 1, 18, 18, 0xFF55FF55);
        String price = a.mode() == Ticket.Mode.ORDER && a.price() > 0 ? " at " + Ticket.formatPrice(a.price()) : "";
        String qty = a.side() == Ticket.Side.BUY && a.amount() > 0 ? " x" + a.amount() : "";
        g.centeredText(mc.font, Component.literal(what + qty + price + ": click the green slot (or press Enter)").withStyle(ChatFormatting.GREEN),
                screen.width / 2, 8, 0xFFFFFFFF);
    }

    private static void drawGreen(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, Slot target) {
        ContainerScreenAccessor acc = (ContainerScreenAccessor) screen;
        int x = acc.sbterminal$getLeftPos() + target.x, y = acc.sbterminal$getTopPos() + target.y;
        double t = (System.currentTimeMillis() % 1000) / 1000.0;
        int alpha = 0x60 + (int) (0x50 * (0.5 + 0.5 * Math.sin(t * 2 * Math.PI)));
        g.fill(x, y, x + 16, y + 16, (alpha << 24) | 0x55FF55);
        g.outline(x - 2, y - 2, 20, 20, 0xFF55FF55);
        g.outline(x - 1, y - 1, 18, 18, 0xFF55FF55);
    }

    private static void addButtons(Screen screen) {
        Screens.getWidgets(screen).add(Button.builder(Component.literal("◀ Terminal").withStyle(ChatFormatting.GOLD), b -> {
                    Minecraft mc = Minecraft.getInstance();
                    TICKET.clear();
                    if (mc.player != null) mc.player.closeContainer();
                    Terminal.show(new TerminalScreen());
                })
                .bounds(4, 4, 72, 20)
                .tooltip(Tooltip.create(Component.literal("Back to the terminal (guide stops)")))
                .build());
        if (TICKET.current(System.currentTimeMillis()) != null) {
            Screens.getWidgets(screen).add(Button.builder(Component.literal("✕ Stop").withStyle(ChatFormatting.GRAY), b -> {
                        TICKET.clear();
                        b.visible = false;
                    })
                    .bounds(80, 4, 50, 20)
                    .tooltip(Tooltip.create(Component.literal("Stop the green-slot guide")))
                    .build());
        }
    }

    // ---------- rejected price ----------

    /** Hypixel refused the price. Fetch fresh prices and retry at best +/- 0.1 with no skew. */
    private static void onRejected(Ticket.Active a) {
        if (a.mode() != Ticket.Mode.ORDER) return;
        CompletableFuture.runAsync(() -> {
            try { Feed.refreshNow(); } catch (Exception ignored) {}
            Minecraft.getInstance().execute(() -> {
                Market.Product p = Feed.snapshot.get(a.id());
                if (p == null) return;
                double plain = Ticket.joinPrice(a.side(), p.bid(), p.ask());
                if (plain > 0 && Math.abs(plain - a.price()) >= 0.1) {
                    TICKET.reprice(plain, System.currentTimeMillis());
                    say("Hypixel rejected that price. Click Custom Price again: " + Ticket.formatPrice(plain) + " gets typed in.");
                } else {
                    TICKET.clear();
                    say("Hypixel rejected the price and the book has not moved. Skipping it.");
                }
            });
        });
    }

    static void say(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[SBT] " + s).withStyle(ChatFormatting.YELLOW));
    }
}
