# ⚙️ Configuration

SwagWeather's entire configuration lives in a single file: `plugins/SwagWeather/config.yml`. Every key below is read directly by `WeatherManager`, `SeasonManager`, or `WeatherWebModule`; nothing here is aspirational or unused.

## Full Default File

```yaml
# SwagWeather configuration

# Worlds to manage. If enabled-worlds is non-empty, ONLY those worlds are managed.
# Otherwise every world is managed except those listed in disabled-worlds.
worlds:
  enabled-worlds: []
  disabled-worlds: []

weather:
  enabled: true

  # How often (in seconds) the manager checks whether the next forecasted
  # transition is due. Lower = more responsive, higher = less overhead.
  check-interval-seconds: 30

  # Number of upcoming transitions kept queued per world.
  forecast-size: 5

  # Random bounds (in minutes) for how long a given weather intensity lasts
  # before the next forecasted transition takes effect.
  min-transition-minutes: 10
  max-transition-minutes: 30

  # Relative weights used when randomly picking the next forecasted intensity.
  # Higher weight = more likely. Does not need to sum to 100.
  weights:
    CLEAR: 40
    LIGHT_RAIN: 25
    RAIN: 20
    HEAVY_RAIN: 10
    THUNDERSTORM: 5

season:
  enabled: true

  # How a season's length is measured:
  #   real_seconds - length-value is real-world seconds
  #   game_days    - length-value is in-game days (24000 ticks each)
  length-mode: real_seconds

  # Length of one season in the unit chosen above.
  length-value: 1800

  # Season order used when advancing (wraps back to the first entry).
  order: [SPRING, SUMMER, FALL, WINTER]

web:
  enabled: true
```

## Key Reference

### Worlds

| Key | Default | Description |
|---|---|---|
| `worlds.enabled-worlds` | `[]` | If non-empty, **only** these worlds are managed by weather and season ticks. |
| `worlds.disabled-worlds` | `[]` | If `enabled-worlds` is empty, every world is managed *except* the ones listed here. |

> `enabled-worlds` always wins if it's non-empty: `disabled-worlds` is only consulted when `enabled-worlds` is empty. You can't combine an allow-list with a deny-list.

### Weather

| Key | Default | Description |
|---|---|---|
| `weather.enabled` | `true` | Master switch. When `false`, the weather tick loop never starts and no forecast is generated. |
| `weather.check-interval-seconds` | `30` | How often (in seconds) the manager checks whether the head of the forecast queue is due. This value is only read once, at `WeatherManager.start()`. Changing it via `/sweather reload` or the web panel updates every other weather key live, but this one still needs a plugin/server restart to take effect. |
| `weather.forecast-size` | `5` | Number of upcoming transitions kept queued per world. Clamped to at least 1. |
| `weather.min-transition-minutes` | `10` | Lower bound (in minutes) for how long a forecasted intensity lasts before the next transition. |
| `weather.max-transition-minutes` | `30` | Upper bound. Automatically clamped to be at least equal to the minimum. |
| `weather.weights.<INTENSITY>` | see above | Relative weight for randomly picking the next intensity (`CLEAR`, `LIGHT_RAIN`, `RAIN`, `HEAVY_RAIN`, `THUNDERSTORM`). Weights don't need to sum to 100; they're compared against each other. If every weight is set to `0` or less, SwagWeather automatically falls back to uniform weights rather than breaking. |

### Season

| Key | Default | Description |
|---|---|---|
| `season.enabled` | `true` | Master switch. When `false`, seasons never advance and stay on whatever the first entry in `order` is. |
| `season.length-mode` | `real_seconds` | `real_seconds`: `length-value` is measured in real-world seconds. `game_days`: `length-value` is measured in in-game days (24000 ticks each, tracked per-world via `World#getFullTime()`). |
| `season.length-value` | `1800` | Length of one season, in whichever unit `length-mode` selects. Clamped to at least 1. |
| `season.order` | `[SPRING, SUMMER, FALL, WINTER]` | The cycle order used when advancing; wraps back to the first entry after the last. Unrecognized entries are logged as a warning and skipped. If the resulting list is empty (e.g. every entry was invalid), SwagWeather falls back to the default Spring → Summer → Fall → Winter order. |

### Web Panel

| Key | Default | Description |
|---|---|---|
| `web.enabled` | `true` | Whether the admin web panel registers with SwagAPI's shared `IWebService` on startup. See [Admin Web Panel](../core-features/admin-web-panel.md). |

## Reloading

```
/sweather reload
```

Reloads `config.yml` and re-applies every value above **except** `weather.check-interval-seconds`, which is only consumed once at plugin startup. Everything else (world lists, the weather master switch, forecast size, transition bounds, weights, the season system, and season order) takes effect immediately, with no server restart required. In-flight forecasts already queued are **not** reset by a reload; they simply keep running against whatever the new settings are for future regeneration.

The admin web panel's **Save** action calls this exact same reload path under the hood, so editing settings there behaves identically to editing `config.yml` by hand and running `/sweather reload`.

## Next Steps

* [Weather System](../core-features/weather-system.md)
* [Season Cycle](../core-features/seasons.md)
* [Admin Web Panel](../core-features/admin-web-panel.md)
