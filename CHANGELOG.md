# Changelog

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
