# 🔐 Permissions

SwagWeather ships with exactly **one** permission node, verbatim from `plugin.yml`.

## Permission Reference

| Permission | Default | Description |
|---|---|---|
| `swagweather.admin` | `op` | Grants access to the entire `/sweather` command tree — `status`, `forecast`, `force`, `season`, and `reload`. |

## Notes

* There is no separate node per subcommand. `swagweather.admin` is declared directly on the `sweather` command in `plugin.yml`, so Bukkit enforces it before `SwagWeatherCommand#onCommand` is ever invoked — there's no additional in-code permission check to configure or bypass.
* The admin web panel has **no permission node of its own**. Access is controlled entirely by SwagAPI's own session-cookie login system, gating the whole panel before any SwagWeather route runs — see [Admin Web Panel](../core-features/admin-web-panel.md).
* There is no player-facing permission at all — SwagWeather has no player-run commands or GUIs. Every feature it exposes to players happens passively, through real vanilla weather and (indirectly) through whatever companion plugin reacts to it over the [Cross-Plugin Event Bus](../core-features/event-bus.md).

## Configuring via a Permission Plugin

```
# Give a moderator group access to admin weather controls
/lp group moderator permission set swagweather.admin true
```

## Related Pages

* [Admin Commands](../admin-commands/commands.md)
