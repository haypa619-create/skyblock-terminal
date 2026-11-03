# Changelog

## 0.7.3
- Clickable tab bar above the bottom strip with every screen (MON MOV SCR FLIP PORT PNL ALRT SECT LIM HELP), current one highlighted, hotkeys shown. The footer was too long on big GUI scales and could push buttons off screen, so it now only holds Enter / Esc / Back and the hints for the current screen (the arrow hint is clickable too).

## 0.7.2
- BIND: set your own hotkeys. BIND FLIP then press a key, or BIND FLIP F6 / CTRL+G. BIND alone lists them, BIND RESET restores defaults. Footer keys show your bindings. Actions: HELP MON MOV SCR BUY SELL PORT FLIP LIST.

## 0.7.1
- Flip finder respects one inventory: units per hour in EST/H are capped at 36 slots x 64 (FLIP SLOTS n changes it), and items under 1k are skipped (FLIP MIN 5k changes it, or the Min price button).

## 0.7.0
- FLIP (F8): flip finder. Ranks items by estimated profit per hour (buy at bid+0.1, sell at ask-0.1, minus tax, times a share of the hourly volume). Risky rows (huge ROI or thin books) are hidden unless you show them.
- Order health strip on every screen: orders, outbid, to claim, daily limit %, and how fresh the order sync is. Click it to open PORT.
- Charts: 1M and 3M ranges from a 2-hourly record of every item saved to config/sbterminal-history.bin (fills in as you play), and Bollinger bands (BB button or command). Volume under the chart is not possible: no source publishes per-bar Bazaar volume.
- Monitor lists: LIST name creates or switches, F9 cycles, tabs appear on the Monitor, LIST DEL name removes one.
- Alerts: ROI (flip margin %) and NET (coins per unit) fields, SOUND per alert, NOTIFY command for filled/outbid on/off, daily-limit warning at a chosen %, and sound choices for good and bad alerts.

## 0.6.0
- Fix: claiming a filled buy order now always lands in Portfolio positions. A looser claim-message match, plus a fallback: if a fully filled buy order disappears from Manage Orders, the units are credited.

## 0.5.9

- The terminal always opens on the Monitor when you press B or type /sbt. Hover + B on an item and /sbt <item> still open that item. To go back to reopening where you left off, set "reopenLast": true in config/sbterminal.json.

## 0.5.8

- The "n/a" in the 24h column is fixed. History used to load only for the 200 busiest items, so everything else showed n/a. Now any item that is on screen (Monitor, sector and screener rows, search dropdown) loads its own 24h history, showing "..." while it loads. The search dropdown waits until you stop typing for a moment so it doesn't fire a request per keystroke.
- A price alert on CHG1H loads the history it needs.

## 0.5.7

- Claim from the portfolio: filled orders get a green CLAIM button, and clicking the row (or "claim it") now starts a guide instead of opening the item. It runs /bz, lights up Manage Orders, then the filled order. Clicking that order in Hypixel's menu claims it. "Claim all" in the toolbar does every filled order in turn and stops when none are left.

## 0.5.6

- Esc closes the terminal in one press from any screen. It used to step back through every screen and click you had made. If you have typed something, the first Esc clears it.
- Going back one screen is now Backspace on an empty command line, or the BKSP key cap in the footer (clickable).

## 0.5.5

- Every key cap in the footer is now a button: GO, MENU, F1 to F4, F7, F5 and F6 can all be clicked, so navigation never depends on the keyboard.
- KEYS command: echoes each key code the terminal receives, plus the frame rate, on the command line. Use it to see whether a key press arrives when one seems to be ignored.

## 0.5.4

- Quantity chips scale with the item: 5%, 10%, 25% and 50% of what your purse can buy at the ticket price, rounded to clean numbers, so a 14M item gets 1, 2, 5, 10 and a 5-coin item gets thousands.
- MAX is the real maximum: the most your purse covers, capped at Hypixel's 71,680 per order and by a daily limit you set yourself. The buy quantity used is never above it.
- CUSTOM QUANTITY button under the chips opens the command line on QTY, so you can type 250, 5k or MAX.

## 0.5.3

- The search dropdown now works after CMP, ALERT and POS, not only for opening, ADD and DEL. Pick a match with Tab, Enter or a click. For ALERT and POS it fills in the item name and keeps the rest of what you typed.
- Typing CMP, ALERT, POS, BUY, SELL, QTY or PX from the code list now leaves the cursor ready for the argument instead of running with nothing.

## 0.5.2

- Fixed the F7 PORT footer key overlapping the view hints.
- Status messages clear when you switch screens.
- Empty P&L figures show 0 instead of +-.
- The ticket total turns red when it is more than your purse.

## 0.5.1

- Daily limit rebuilt, nothing hardcoded. The cap comes from LIMIT <amount>, or is learned from Hypixel's "reached the daily limit" message (saved as an estimate, shown with ~). Until then 15B is only displayed, never enforced.
- The reset time is a setting (LIMIT RESET 7:00 PM, your time or UTC). If an order goes through after Hypixel blocked you inside what the mod thought was one window, it works out the reset happened in between and moves the reset time there.
- LIMIT now opens a ledger: every order, offer and instant trade with the time you did it, a running total, the bar, time until reset, and whether Hypixel blocked you. Cancelled orders still count.
- The header shows used/limit and the reset countdown. After Hypixel says you hit the limit, the ticket stops you until the reset instead of sending you through the menus.
- The ticket only blocks on a cap you set yourself; learned and assumed caps show a ~ estimate.

## 0.5.0

- Daily limit: every order and insta-trade you place is added up per day. The header shows LIMIT used/15B, the ticket shows what is left and blocks an order that would pass it. LIMIT 15B changes it.
- Portfolio shows coins LOCKED in open buy orders.
- Reprice: outbid or undercut orders get a REPRICE button (and a toolbar action). The guide walks you through cancelling the old order in Manage Orders, then one Enter opens the item and the rest is re-placed at the best price. You click every step.
- Price alerts: ALERT coal < 120, also BID, ASK, SPR (spread %) and CHG1H. Fires once in chat with a sound; ALRT lists, deletes and re-arms them.
- Fill times: how long your orders took to fill, median per item and side, on the item page.
- P&L history (PNL): cumulative realized P&L chart, P&L by day, by item with trade count and win rate.
- Ticket: break-even sell price after tax, P&L if you flipped at the current ask, your cost and P&L for sell offers, limit left.
- Compare (CMP item): overlay a second item on the chart as % change from the start of the range.
- Sectors (SECT): Farming, Mining, Gemstones, Combat, Woods & Fishes, Enchantments, Essence, Oddities with flow-weighted 1h and 24h moves; Enter drills into a sector.

## 0.4.1

- Orders now sync from the real Manage Orders menu. It is read whenever you open it, and the list is replaced with exactly what Hypixel shows (partial fills and claimable amounts included).
- Opening the main Bazaar page with an order list older than 90 seconds lights up Manage Orders in green. One click or Enter and it syncs. Nothing is clicked for you.
- The portfolio shows when orders were last synced.

## 0.4.0

- Portfolio (PORT, F7): positions with average cost, live value after Bazaar tax, unrealized P&L, plus realized P&L for today and all time. Built from your Bazaar chat lines (claims and insta-trades), nothing to enter by hand. POS sets a position you held before tracking.
- Open orders list with ON TOP / OUTBID / FILLED status against the live book.
- Chat and sound alerts when an order is outbid, undercut or filled (config: alerts).

## 0.3.0

- Order ticket on every item page: BUY / SELL tabs, quantity chips (64, 640, 1K, 10K, MAX from your purse), price that joins the best bid or ask (override with PX), total and share of purse.
- BUY ORDER <GO> opens /bz for the item and points at the slot to click at each step, typing the amount and price into Hypixel's signs. Enter does one click per press. You confirm every step.
- BUY NOW / SELL NOW for instant trades. Commands: BUY 5k 120.5, SELL 130.5, BUY 5k NOW, QTY, PX.
- If Hypixel rejects a price, the guide re-fetches the book and retypes a fresh one.
- F5 / F6 pick the side, Enter on an empty line places the ticket.

## 0.2.0
- Terminal look: monospace font (IBM Plex Mono, bundled, OFL), panel bar, amber command line with block cursor, item line with large bid x ask, dark-red numbered toolbar, colored key caps. FONT MC switches back to Minecraft's font
- Search as you type: matches with live price and 24h change under the command line; Up/Down, Tab or Enter
- Numbered toolbar actions: type the number and press Enter, or click
- Hover any item in any menu or your inventory and press B to open it in the terminal
- /sbt [item] opens the terminal from chat
- Copy best bid + 0.1 or best ask - 0.1 to the clipboard for Hypixel's price sign; Open in Bazaar
- Prices flash green / red when they change
- Click a column header to sort (again to reverse); Shift+Up/Down reorders the Monitor
- Chart ranges 1H 6H 1D 3D 1W
- Ctrl+V pastes into the command line; Home / End / Page keys in lists
- Remembers the screen, item and range between sessions

## 0.1.0
- Terminal screen drawn by hand: amber function bar, command line, dense tables, footer of keys
- Live Bazaar data for every item from Hypixel's public API, every 5s while open
- Item page: chart (1H / 1D / 1W) with bid and ask lines, high/low wicks and a crosshair readout; 30-deep order book; stats
- Monitor (watchlist), Movers (24h / 1h), Screener with filters and sort
- History from Coflnet plus the terminal's own one-minute recording
