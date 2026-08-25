# 🛠️ Admin Commands

All commands are under `/sweather` (aliases `/weather`, `/sw`) and require `swagweather.admin` (default: op). This permission is enforced at the `plugin.yml` command level: there's a single check gating the whole command tree, not per-subcommand permissions.

## Command Reference

| Command | Description |
|---|---|
| `/sweather status [world]` | Shows the current intensity, season, and days remaining in the season for a world. Defaults to the sender's current world if omitted (console must specify one). |
| `/sweather forecast [world]` | Lists the queued upcoming weather transitions and their ETAs for a world. Defaults to the sender's current world if omitted. |
| `/sweather force <world> <intensity> [durationSeconds]` | Immediately forces a weather intensity on a world. Duration defaults to 600 seconds if omitted. |
| `/sweather season <world> <season>` | Immediately forces a season change on a world. |
| `/sweather reload` | Reloads `config.yml` and re-applies weather/season settings without a server restart. |

Running `/sweather` with no arguments (or an unrecognized subcommand) prints this same usage list.

## Examples

```
/sweather status
/sweather status world_nether
/sweather forecast world
/sweather force world THUNDERSTORM 300
/sweather season world WINTER
/sweather reload
```

## Argument Details

### `status` / `forecast`

Both accept an optional `[world]` argument. If omitted:
* A player defaults to their **current world**.
* Console (or any non-player sender) with no world specified gets an error: there's no "current world" to fall back to.

### `force`

```
/sweather force <world> <intensity> [durationSeconds]
```

* `<world>` must be an exact, currently-loaded world name (`Bukkit.getServer().getWorld(name)`), not a fuzzy match.
* `<intensity>` must be one of `CLEAR`, `LIGHT_RAIN`, `RAIN`, `HEAVY_RAIN`, `THUNDERSTORM` (case-insensitive). An unrecognized value prints the valid list back to you.
* `[durationSeconds]` is the length of the forced weather in real seconds (converted to ticks internally); defaults to `600` (10 minutes) if omitted.

Forcing clears that world's forecast queue and applies the change immediately, then regenerates a fresh forecast starting after the forced duration ends; natural transitions resume automatically once it expires.

### `season`

```
/sweather season <world> <season>
```

* `<season>` must be one of `SPRING`, `SUMMER`, `FALL`, `WINTER` (case-insensitive).
* Takes effect immediately and restarts that world's season length timer from the moment of the change.

### `reload`

Reloads `config.yml` and re-applies every setting **except** `weather.check-interval-seconds`, which is only read once at plugin startup. See [Configuration](../getting-started/configuration.md#reloading) for the full list of what does and doesn't apply live.

## Tab Completion

Every subcommand, world name, intensity, and season name is tab-completable. World names are pulled live from `Bukkit.getServer().getWorlds()`, so newly loaded worlds show up without a restart.

## Related Pages

* [Permissions](../permissions/permissions.md)
* [Weather System](../core-features/weather-system.md)
* [Season Cycle](../core-features/seasons.md)
