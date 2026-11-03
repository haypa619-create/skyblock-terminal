# Changelog

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
