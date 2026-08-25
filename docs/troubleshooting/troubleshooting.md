# 🩹 Troubleshooting

## Plugin Won't Enable

**Check console for:**

```
SwagAPI services not found! Is SwagAPI loaded? Disabling.
```

SwagWeather has exactly one hard dependency, [SwagAPI](https://github.com/swag617/SwagAPI), and will disable itself immediately if SwagAPI's `IEventBusService` isn't registered. This almost always means:

1. SwagAPI isn't installed at all.
2. SwagAPI is installed but failed to enable itself (check for its own startup errors above SwagWeather's in the log).
3. SwagAPI's jar is an incompatible/older version that doesn't register `IEventBusService`.

If SwagAPI is confirmed installed and healthy but SwagWeather still won't enable, also check:

* **Java version**: SwagWeather is compiled for Java 17+.
* **Server type/version**: built against Paper's `api-version: "1.21"`; use Paper (or a compatible fork) on 1.21.

## Weather Isn't Changing

1. Confirm `weather.enabled: true` in `config.yml`: the tick loop never starts otherwise.
2. Confirm the world you're checking is actually managed: if `worlds.enabled-worlds` is non-empty, only worlds in that list are managed at all, regardless of `disabled-worlds`.
3. Run `/sweather forecast <world>`: if it shows queued entries with sane ETAs, weather *is* being tracked and will change once the head of the queue is due. Transitions are timed randomly between `weather.min-transition-minutes` and `weather.max-transition-minutes` (default 10–30 minutes), so "nothing happened in the last 2 minutes" isn't unusual.
4. If you need to see it happen right now rather than wait, use `/sweather force <world> <intensity> [durationSeconds]`.
5. Remember `weather.check-interval-seconds` (how often the manager *checks* whether a transition is due) only takes effect after a restart; changing it via `/sweather reload` or the web panel won't speed up an already-running server until you restart.

## Season Isn't Advancing

1. Confirm `season.enabled: true` in `config.yml`.
2. Check `/sweather status <world>` for `daysRemaining`: in `real_seconds` mode (the default), that number is derived from wall-clock time regardless of whether the world is loaded or players are online, so it should always be counting down.
3. In `game_days` mode, remember the season timer is driven by `World#getFullTime()`: an unloaded or rarely-ticked world's season effectively pauses along with it. This is expected behavior in that mode, not a bug.
4. To confirm the system is alive at all without waiting, force a change: `/sweather season <world> <season>`.

## Weather/Season Changes Aren't Reaching SwagFarming or SwagFishing

1. Confirm both plugins are actually installed and enabled: they're **soft** dependencies of each other, so neither one will fail to start without SwagWeather, but their weather/season integration will simply stay inactive.
2. Check each plugin's own startup log for a line confirming it subscribed to SwagAPI's event bus (e.g. SwagFishing logs `WeatherIntegration subscribed to SwagWeather's 'weather'/'season' channels.`).
3. Confirm SwagAPI itself is healthy on all three plugins: if `IEventBusService` isn't available to a consumer, that consumer's integration silently falls back to a neutral default (vanilla weather checks, no season restriction) rather than erroring.
4. See [Cross-Plugin Event Bus](../core-features/event-bus.md) for the exact channel names and payload shape both consumers expect.

## Admin Web Panel Won't Load / Shows "Panel file not found"

1. Confirm `web.enabled: true` in `config.yml`.
2. Check console for `SwagAPI IWebService not present — admin web panel will be unavailable.` This means SwagAPI's web server component itself isn't running; the panel depends on it entirely and has no server of its own.
3. If the panel registered but serves a 404 "Panel file not found," the file `plugins/SwagWeather/web/weather-panel.html` is missing. It's copied from the jar automatically on first boot: restart the plugin/server to regenerate it, or verify the `plugins/SwagWeather/web/` folder wasn't deleted.
4. Login is handled entirely by SwagAPI's own session system: if you can't log in at all, that's a SwagAPI issue, not a SwagWeather one.

## `/sweather` Says "Could not resolve a world"

This happens when `status`/`forecast` are run with no `[world]` argument from a **non-player sender** (console, a command block, etc.): there's no "current world" to default to for a sender that isn't standing in one. Specify the world explicitly: `/sweather status world`.

## Still Stuck?

Open an issue on [GitHub](https://github.com/swag617/SwagWeather) with your server log and `config.yml`.

## Related Pages

* [Installation](../getting-started/installation.md)
* [Configuration](../getting-started/configuration.md)
* [Cross-Plugin Event Bus](../core-features/event-bus.md)
