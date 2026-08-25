# 🍂 Season Cycle

`SeasonManager` tracks a slow-moving season per world (`SPRING` → `SUMMER` → `FALL` → `WINTER` by default), completely independent of `WeatherManager`'s forecast cadence. Vanilla Minecraft has no concept of a season at all; this is entirely a SwagWeather model, published for other plugins to react to.

## The Season Enum

```java
public enum Season {
    SPRING, SUMMER, FALL, WINTER
}
```

> Like `Intensity`, this is a **cross-plugin contract**: the constant names are published verbatim on the event bus and consumed by SwagFarming and SwagFishing. They will not be renamed without ecosystem-wide coordination.

## How Length Is Measured

Seasons advance on a cadence set by `season.length-mode`:

* **`real_seconds`** (default): `season.length-value` (default `1800`, i.e. 30 minutes) is real-world seconds. A world's season timer counts down in wall-clock time regardless of whether the server is busy or players are online.
* **`game_days`**: `season.length-value` is measured in in-game days, where one game day is 24000 ticks, tracked per-world via `World#getFullTime()`. This ties season length to actual server uptime/ticking rather than wall-clock time; a world that's rarely loaded advances its season more slowly in this mode.

The season check runs every 20 real seconds (a fixed internal cadence, not configurable); that's more than sufficient responsiveness for something this slow-moving.

## Order and Advancement

`season.order` (default `[SPRING, SUMMER, FALL, WINTER]`) defines the cycle. When a world's season comes due, `SeasonManager` advances to the *next* entry in this list, wrapping back to the first entry after the last. You can reorder it, shorten it, or even repeat entries: whatever list you configure is exactly what's cycled through.

Unrecognized entries in `season.order` are logged as a warning and skipped; if the resulting list ends up empty, SwagWeather falls back to the default Spring → Summer → Fall → Winter order rather than breaking.

## Checking Season State

```
/sweather status [world]
```

```
world:
  Intensity: RAIN
  Season: FALL (12 day(s) remaining)
```

`daysRemaining` is always expressed in **in-game days**, regardless of which `length-mode` is active: in `real_seconds` mode it's derived by converting the remaining wall-clock time using a fixed 20-real-minutes-per-game-day assumption (matching a vanilla day at 20 TPS), then rounded up.

## Forcing a Season Change

```
/sweather season <world> <season>
```

Immediately sets a world's season and restarts its length timer from that moment, publishing the change on the event bus right away. Unlike a forced weather transition, there's no queue to regenerate: the next natural advancement simply follows the configured `length-value`/`length-mode` from the forced season onward.

## Per-World Isolation

Like the weather tick, the season tick wraps each world's work in its own try/catch: a world that throws while being processed logs a warning naming it and is skipped for that pass, without affecting any other world's season progression.

## Related Pages

* [Weather System](weather-system.md)
* [Cross-Plugin Event Bus](event-bus.md)
* [Configuration](../getting-started/configuration.md)
* [Admin Commands](../admin-commands/commands.md)
