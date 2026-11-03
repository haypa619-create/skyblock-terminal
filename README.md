# SkyBlock Terminal

A Bloomberg-style Bazaar terminal for Hypixel SkyBlock, inside Minecraft. Fabric, **Minecraft 26.2** (works in the SkyBlock Enhanced modpack). Press **B**.

Read-only: it shows market data. It never clicks, buys or sells anything.

## Functions

Type into the command line at the top and press Enter.

| Command | What it shows |
|---|---|
| `radar` | Item page: live bid / ask / spread, 1h / 24h / 7d change, a price chart (1H, 1D, 1W), the 30-deep order book, and stats (weekly insta-buys and insta-sells, coin flow, orders on each side, buy/sell pressure). Any part of a name works, or initials (`ec` = Enchanted Coal). |
| `radar 1W` | Same, with the chart range set. Ranges: `1H` `6H` `1D` `3D` `1W`. |
| `MON` | Monitor: your watchlist, live. `ADD radar` / `DEL radar`, or F5 on an item page. |
| `MOV` | Movers: biggest gainers and losers over 24h or 1h (Left/Right), among items that actually trade. |
| `SCR VOL>50K SPR>3 PX<1M CHG<-5` | Screener over every Bazaar item. Fields: `VOL` weekly units, `FLOW` coins per day, `PX` price, `SPR` spread %, `CHG` 24h %, `CHG1H` 1h %, `ORD` orders. Left/Right changes the sort. |
| `HELP` | Commands and keys. |

## Look

Everything is drawn by the mod in a terminal style: black background, amber command line, a numbered dark-red toolbar on each screen (type the number and press Enter, or click), green/red price flashes, colored key caps. Text uses a bundled monospace font, IBM Plex Mono (SIL Open Font License, see `assets/sbterminal/font/OFL-IBM-Plex-Mono.txt`). `FONT MC` switches to Minecraft's font.

## Shortcuts

- **Search as you type:** matching items appear under the command line with live price and 24h change. Up/Down, then Tab or Enter.
- **Hover + B:** point at an item in any menu or your inventory and press B to open it in the terminal.
- **`/sbt radar`** from chat opens the terminal on an item.
- **Copy price:** on an item page, toolbar actions copy best bid + 0.1 or best ask - 0.1 for Hypixel's price sign.
- **Sort:** click a column header (again to reverse). Shift+Up/Down moves a Monitor row.

## Keys

| Key | Does |
|---|---|
| Up / Down, Enter | Pick a row, open it (or click it) |
| Left / Right | Chart range, movers window, screener sort |
| F1 F2 F3 F4 | HELP, MON, MOV, SCR |
| F5 | On an item page: add to / remove from your Monitor |
| Delete | On the Monitor: remove the selected row |
| Esc | Clear the command line, then go back, then close |

Rebind B in Options > Controls > SkyBlock Terminal.

## Data

- **Live:** Hypixel's public Bazaar API (no key), one request for every item, checked every 5 seconds while the terminal is open and every 20 seconds otherwise. The header shows how old Hypixel's data is.
- **History:** [Coflnet](https://sky.coflnet.com)'s free Bazaar history: about 5-minute points for the last day and 2-hour bars for the last week. Item pages load it when opened; the 200 most-traded items are filled in slowly in the background (one request every 3 seconds) so 24h movers work.
- **Recording:** the terminal records one point per item per minute from every live update (kept 6 hours), for the 1H chart and 1h changes.

## Install (Modrinth App)

Open your profile > **Content** > **Add content** > **Add from file**, pick the jar. Needs Fabric API (already in SkyBlock Enhanced).

## Build

Every push to `main` builds on GitHub Actions and publishes the jar under **Releases**. Locally: Java 25, then `./gradlew build`.

## Roadmap

1. Terminal shell, Monitor, item page, Movers, Screener (this release)
2. Portfolio (positions, cost, P&L from Bazaar chat and Manage Orders)
3. Alerts (price, volume, outbid)
4. Order ticket (hands off to the in-menu guide with numbers pre-filled)
5. News and calendar (mayor, elections, events)
