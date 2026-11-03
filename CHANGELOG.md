# Changelog

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
