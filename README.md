# SwagWeather

Forecast-driven weather and slow-moving season control for Paper/Spigot 1.21, built for the Swag617 plugin ecosystem.

SwagWeather drives **real vanilla weather** (`World#setStorm`, `setThundering`, `setWeatherDuration`, `setThunderDuration`) so existing mechanics — crop trampling, farms, fire spread — keep working exactly as players expect, while layering a richer forecast and 5-tier intensity model on top that vanilla Minecraft has no concept of. A slower, independent season cycle (Spring/Summer/Fall/Winter) runs alongside it. Both systems publish live state on [SwagAPI](https://github.com/swag617/SwagAPI)'s shared event bus so other plugins — SwagFishing, SwagFarming — can react to weather and season changes without a compile-time dependency on this plugin's jar.

---

## Features

- **5-tier weather intensity** — `CLEAR`, `LIGHT_RAIN`, `RAIN`, `HEAVY_RAIN`, `THUNDERSTORM` — mapped onto real vanilla storm/thunder state per world
- **Forecast queue** — each managed world keeps a configurable number of upcoming weather transitions queued with randomized ETAs, so `/sweather forecast` can show what's coming
- **Configurable transition weights** — tune how likely each intensity tier is to be rolled next, independently per tier
- **Independent season cycle** — Spring → Summer → Fall → Winter (configurable order), advancing on either real-world seconds or in-game days, tracked separately per world
- **Per-world enable/disable lists** — manage every world by default, or opt into an explicit allow-list
- **Cross-plugin event bus integration** — publishes on SwagAPI's `"weather"` and `"season"` channels every time either changes, so other plugins can subscribe without depending on SwagWeather directly
- **Public API** — `SwagWeatherAPI` for plugins that *do* want a direct, compile-time integration
- **Admin web panel** — a live dashboard (per-world intensity, season, and forecast) served through SwagAPI's shared web server, with force-weather and force-season controls
- **Admin command** — `/sweather status|forecast|force|season|reload`
- **Hardened tick loops** — a single misbehaving world (custom dimension, weather-incapable world type) can't silently stall weather/season processing for every other world; see [Hardening](#hardening) below

---

## Requirements

| Dependency | Required |
|---|---|
| Paper/Spigot 1.21 (`api-version: 1.21`) | Yes |
| Java 17+ | Yes |
| [SwagAPI](https://github.com/swag617/SwagAPI) (hard `depend`) | Yes — SwagWeather will not enable without it |

SwagWeather has no other dependencies. Vault, PlaceholderAPI, and WorldGuard are not used.

## Installation

1. Install [SwagAPI](https://github.com/swag617/SwagAPI) first — SwagWeather declares it as a hard dependency in `plugin.yml` and will disable itself on enable if SwagAPI's services aren't found.
2. Drop `SwagWeather.jar` into your server's `plugins/` folder.
3. Start/restart the server. `plugins/SwagWeather/config.yml` is generated on first run.
4. Edit `config.yml` to taste and reload with `/sweather reload`, or restart the server.

## Commands

| Command | Description |
|---|---|
| `/sweather status [world]` | Show current intensity, season, and days remaining in the season for a world (defaults to the sender's current world). |
| `/sweather forecast [world]` | List the queued upcoming weather transitions and their ETAs for a world. |
| `/sweather force <world> <intensity> [durationSeconds]` | Immediately force a weather intensity on a world (default duration 600s). |
| `/sweather season <world> <season>` | Immediately force a season change on a world. |
| `/sweather reload` | Reload `config.yml` and re-apply weather/season settings without restarting. |

Aliases: `/weather`, `/sw`. All subcommands require the `swagweather.admin` permission (default: `op`).

## Configuration

`config.yml`:

```yaml
worlds:
  enabled-worlds: []
  disabled-worlds: []

weather:
  enabled: true
  check-interval-seconds: 30
  forecast-size: 5
  min-transition-minutes: 10
  max-transition-minutes: 30
  weights:
    CLEAR: 40
    LIGHT_RAIN: 25
    RAIN: 20
    HEAVY_RAIN: 10
    THUNDERSTORM: 5

season:
  enabled: true
  length-mode: real_seconds
  length-value: 1800
  order: [SPRING, SUMMER, FALL, WINTER]

web:
  enabled: true
```

| Key | Default | Description |
|---|---|---|
| `worlds.enabled-worlds` | `[]` | If non-empty, **only** these worlds are managed. |
| `worlds.disabled-worlds` | `[]` | If `enabled-worlds` is empty, every world is managed *except* these. |
| `weather.enabled` | `true` | Master switch for the weather system. |
| `weather.check-interval-seconds` | `30` | How often the manager checks whether the next forecasted transition is due. |
| `weather.forecast-size` | `5` | Number of upcoming transitions kept queued per world. |
| `weather.min-transition-minutes` / `max-transition-minutes` | `10` / `30` | Random bounds for how long a forecasted intensity lasts before the next transition. |
| `weather.weights.<INTENSITY>` | see above | Relative weight for randomly picking the next intensity. Doesn't need to sum to 100; a fully-zeroed config falls back to uniform weights automatically. |
| `season.enabled` | `true` | Master switch for the season system. |
| `season.length-mode` | `real_seconds` | `real_seconds` — `length-value` is real-world seconds. `game_days` — `length-value` is in-game days (24000 ticks each). |
| `season.length-value` | `1800` | Length of one season in the unit chosen above. |
| `season.order` | `[SPRING, SUMMER, FALL, WINTER]` | Season order used when advancing; wraps back to the first entry. Unknown entries are logged and skipped; an empty/all-invalid list falls back to the default order. |
| `web.enabled` | `true` | Whether the admin web panel registers with SwagAPI's web service. |

## Cross-plugin integration

Every time weather or season changes, SwagWeather publishes a `SwagCrossPluginMessageEvent` on SwagAPI's event bus:

| Channel | Payload keys |
|---|---|
| `"weather"` | `world`, `intensity` (enum name), `etaSeconds` (until the next queued transition), `forecastNext` (enum name of that next transition) |
| `"season"` | `world`, `season` (enum name), `daysRemaining` |

Consumers subscribe by channel name and parse the enum names with their own `valueOf(...)`-style lookup — no compile-time dependency on SwagWeather's jar is needed. **The `Intensity` and `Season` enum constant names are a cross-plugin contract** — do not rename them without coordinating across the ecosystem (SwagFishing and SwagFarming both consume these names).

For plugins that already accept a compile-time dependency on SwagWeather (e.g. admin tooling), a direct API is available:

```java
SwagWeatherAPI api = SwagWeather.getInstance().getApi();
Intensity current = api.getIntensity(world);
Season season = api.getSeason(world);
List<ForecastEntry> forecast = api.getForecast(world);
long daysLeft = api.getDaysRemainingInSeason(world);

api.forceWeather(world, Intensity.THUNDERSTORM, 20 * 60 * 10); // 10 minutes, in ticks
api.forceSeason(world, Season.WINTER);
```

## Admin web panel

If `web.enabled` is `true` and SwagAPI's `IWebService` is available, SwagWeather registers an admin dashboard through SwagAPI's shared HTTP server — SwagWeather does not run its own server or handle its own authentication; login is gated entirely by SwagAPI's session-cookie system before any SwagWeather route runs.

| Route | Method | Description |
|---|---|---|
| `/` | GET | Serves the panel's HTML/CSS/JS dashboard. |
| `/api/state` | GET | Current intensity, season, days remaining, and forecast for every managed world. |
| `/api/force` | POST | Body `{"world":"...","intensity":"...","durationSeconds":N}` — forces a weather transition. |
| `/api/season` | POST | Body `{"world":"...","season":"..."}` — forces a season change. |

All Bukkit-API reads/writes triggered by a web request are hopped onto the main server thread before touching any `World` object, since SwagAPI's web server dispatches handlers on a background thread pool.

## Hardening

SwagWeather has been through a dedicated hardening audit (see `AUDIT_REPORT.md`) covering known plugin-lifecycle bug patterns from elsewhere in the Swag617 ecosystem:

- **No disk persistence, so no data-loss-on-shutdown risk** — all forecast and season state is in-memory and simply regenerates on the next boot.
- **SwagAPI service lookups at enable time are safe** — SwagAPI is a hard `depend`, so the server guarantees it's fully enabled (and has registered its services) before SwagWeather's `onEnable()` runs.
- **Per-world isolation in both tick loops and the web panel's state builder** — the weather tick, season tick, and `/api/state` JSON builder each wrap their per-world work in a try/catch. If one world throws (a custom dimension, a weather-incapable world type, or a future Paper API change), that world is skipped with a single warn-level log line naming it, and every other world keeps being processed normally on that same pass — a single bad world can no longer silently stall weather/season updates or 500 the web panel for the whole server.

## Building

```bash
mvn clean package
```

Produces `target/SwagWeather.jar`. The Maven build also copies the jar straight to a configured local test-server `plugins/` folder via the `maven-antrun-plugin` — update the `server.path` property in `pom.xml` if you're building this yourself.

> SwagAPI is referenced as a `system`-scoped dependency (`libs/SwagAPI-1.0.0.jar`) for compilation only — it is **not** bundled into SwagWeather's jar, so the real SwagAPI plugin must be installed on the server at runtime.
