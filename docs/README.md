# 🌦️ Welcome to SwagWeather

> **SwagWeather** is a forecast-driven weather and slow-moving season simulation for Minecraft servers running Paper/Spigot 1.21. It drives real vanilla weather so existing mechanics keep working, while publishing a richer intensity/season model on a shared cross-plugin event bus for the rest of the Swag617 ecosystem to react to.

## What Makes SwagWeather Special?

* **5-Tier Weather Intensity**: `CLEAR`, `LIGHT_RAIN`, `RAIN`, `HEAVY_RAIN`, `THUNDERSTORM`, mapped onto real `World#setStorm`/`setThundering` state so crop trampling, farms, and fire spread behave exactly as vanilla players expect. See [Weather System](core-features/weather-system.md).
* **A Real Forecast, Not Just a Coin Flip**: each managed world keeps a configurable queue of upcoming transitions with randomized ETAs, so `/sweather forecast` can actually tell you what's coming and when.
* **Independent Season Cycle**: Spring → Summer → Fall → Winter (configurable order), advancing on either real-world seconds or in-game days, tracked separately per world and on its own cadence from weather. See [Season Cycle](core-features/seasons.md).
* **The Cross-Plugin Event Bus**: SwagWeather's most distinctive feature. Every weather or season change is published on [SwagAPI](https://github.com/swag617/SwagAPI)'s shared event bus, so companion plugins can react without ever compiling against SwagWeather's jar. **SwagFarming** turns real rain into genuine crop growth-speed bonuses and season-match modifiers; **SwagFishing** turns a `HEAVY_RAIN`/`THUNDERSTORM` transition into a temporary Feeding Frenzy (boosted bite rate and rarity odds) and announces seasonal migratory fish. See [Cross-Plugin Event Bus](core-features/event-bus.md).
* **A Public API Too**: for plugins willing to accept a compile-time dependency, `SwagWeatherAPI` exposes the same state directly (`getIntensity`, `getSeason`, `getForecast`, `forceWeather`, `forceSeason`) without going through the bus.
* **Admin Web Panel**: a live dashboard covering every managed world's intensity, season, and forecast, served through SwagAPI's shared web server, with force-weather/force-season controls and a full settings editor. See [Admin Web Panel](core-features/admin-web-panel.md).
* **Per-World Control**: manage every world by default, or opt into an explicit allow-list via `worlds.enabled-worlds`.
* **Hardened Tick Loops**: a single misbehaving world (a custom dimension, a weather-incapable world type) can't silently stall weather or season processing for every other world; each per-world iteration is isolated and logs a warning instead of breaking the loop.

## Core Philosophy

### Vanilla Weather Stays Real
SwagWeather never fakes weather client-side or invents a parallel reality: it calls the same `World` API vanilla uses (`setStorm`, `setThundering`, `setWeatherDuration`, `setThunderDuration`). Anything that already reacts to vanilla rain or thunder keeps working with zero changes.

### A Model Vanilla Doesn't Have
Vanilla only knows "raining or not, thundering or not." SwagWeather layers a 5-tier intensity model and a forecast queue on top so other systems, and other plugins, can react to *how hard* it's storming and *what's coming next*, not just a boolean.

### No Compile-Time Coupling Required
The event bus is the intended integration path. A plugin that wants to react to weather or seasons subscribes to the `"weather"`/`"season"` channels by name and parses string payloads; it never needs SwagWeather's jar on its classpath. This is exactly how SwagFarming and SwagFishing integrate today.

## Quick Links

| Topic | Description | Link |
|-------|-------------|------|
| **Installation** | Get the plugin running in a few minutes | [Installation Guide](getting-started/installation.md) |
| **Weather System** | Intensity tiers, forecast queue, and transition tuning | [Weather System](core-features/weather-system.md) |
| **Cross-Plugin Event Bus** | How SwagFarming, SwagFishing, and your own plugin can hook in | [Cross-Plugin Event Bus](core-features/event-bus.md) |
| **Admin Commands** | Full `/sweather` reference | [Admin Commands](admin-commands/commands.md) |

## Community

Questions, bug reports, and feedback are welcome via GitHub Issues.

## Credits

**Developer:** Swag
**Built With:** Java 17+, Paper API, SwagAPI

## License

SwagWeather is proprietary software developed for Swag. All rights reserved © 2026.

---

> **Need Help?** Check the [Troubleshooting](troubleshooting/troubleshooting.md) page or open an issue on [GitHub](https://github.com/swag617/SwagWeather).
