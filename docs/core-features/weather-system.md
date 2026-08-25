# 🌦️ Weather System

`WeatherManager` drives **real vanilla weather** — `World#setStorm`, `World#setThundering`, `World#setWeatherDuration`, `World#setThunderDuration` — while layering a 5-tier intensity model and a forecast queue on top that vanilla has no concept of.

## Why Real Vanilla Weather?

Vanilla mechanics that already react to rain or thunder — crop trampling, farms, fire spread, wet-mob spawn conditions, lightning strikes — keep working exactly as players expect, because SwagWeather isn't faking anything client-side or maintaining a parallel reality. It's setting the same `World` fields vanilla itself would set; it just decides *when* and *how intensely* using its own model instead of vanilla's built-in randomness.

## Intensity Tiers

```java
public enum Intensity {
    CLEAR, LIGHT_RAIN, RAIN, HEAVY_RAIN, THUNDERSTORM;
}
```

| Intensity | `setStorm()` | `setThundering()` |
|---|---|---|
| `CLEAR` | `false` | `false` |
| `LIGHT_RAIN` | `true` | `false` |
| `RAIN` | `true` | `false` |
| `HEAVY_RAIN` | `true` | `false` |
| `THUNDERSTORM` | `true` | `true` |

Only `CLEAR` maps to no storm at all; only `THUNDERSTORM` sets thunder. `LIGHT_RAIN`, `RAIN`, and `HEAVY_RAIN` are all real vanilla rain (`isStorm() == true`) — the distinction between them exists purely in SwagWeather's own model, for consumers on the event bus to react to differently (see [Cross-Plugin Event Bus](event-bus.md)).

> **This is a cross-plugin contract.** These five enum constant names are published verbatim on the event bus. SwagFishing and SwagFarming both parse them by name. They will not be renamed without coordinating across the whole Swag617 ecosystem.

## The Forecast Queue

Each managed world keeps a queue of upcoming transitions (`weather.forecast-size` entries, default 5), each with a randomly generated ETA. A repeating task (period: `weather.check-interval-seconds`, default 30s) checks whether the head of the queue is due:

1. If it is, that entry becomes the new "current" intensity.
2. Real vanilla weather is applied for a duration matching the time until the *next* queued transition.
3. The queue is refilled back up to its configured size.
4. The change is published on the event bus.

Transition ETAs are picked uniformly between `weather.min-transition-minutes` and `weather.max-transition-minutes` (defaults 10–30 minutes). Which intensity comes next is a weighted random draw from `weather.weights.*` — see [Configuration](../getting-started/configuration.md#weather).

Query the queue at any time:

```
/sweather forecast [world]
```

```
Forecast for world:
  RAIN in ~412s
  CLEAR in ~1847s
  LIGHT_RAIN in ~2930s
  HEAVY_RAIN in ~4102s
  THUNDERSTORM in ~5588s
```

ETAs are always computed relative to "now" — a forecast entry doesn't go stale between when it was generated and when you read it.

## Forcing a Transition

```
/sweather force <world> <intensity> [durationSeconds]
```

Forcing bypasses the random forecast for one immediate change: the queue is cleared and applied instantly, then a *fresh* forecast is regenerated to start after the forced duration ends (default 600s if omitted) — so natural transitions resume automatically once the forced weather expires, rather than needing another manual command.

## Per-World Isolation

Every world is ticked independently inside a try/catch. If a single world throws while applying weather (a custom dimension, a weather-incapable world type, or a future Paper API change), that one world is skipped for the pass with a warning logged naming it — every other world keeps ticking normally on the same cycle. A single bad world can't silently stall weather processing for the whole server.

## Related Pages

* [Season Cycle](seasons.md)
* [Cross-Plugin Event Bus](event-bus.md)
* [Configuration](../getting-started/configuration.md)
* [Admin Commands](../admin-commands/commands.md)
