# 🖥️ Admin Web Panel

SwagWeather registers a live admin dashboard through SwagAPI's shared web server — it does not run its own `HttpServer` and has no login/authentication logic of its own. SwagAPI's session-cookie system gates access before any SwagWeather route ever runs.

## Enabling / Disabling

Controlled by `web.enabled` in `config.yml` (default `true`). If it's `false`, or SwagAPI's `IWebService` isn't registered for any reason, `WeatherWebModule` logs a warning and simply skips registration — every other part of the plugin (weather, seasons, the `/sweather` command, the event bus) keeps working normally.

Because SwagAPI is a hard dependency, `IWebService` is normally always present — the defensive null-check exists for the edge case where the web server itself failed to start.

## Accessing the Panel

Once registered, the panel is mounted at SwagAPI's shared address under `/swagapi/swagweather/`. The exact base URL depends on your SwagAPI web server configuration; check your server console at startup for the line:

```
Web panel registered at ...
```

## What It Shows

The dashboard has two areas:

* **Live State** — per managed world: current intensity, current season, days remaining in that season, and the queued forecast (same data `/sweather status` and `/sweather forecast` expose).
* **Settings** — world allow/deny lists, the weather and season master switches, forecast tuning (check interval, forecast size, min/max transition minutes), the per-intensity weight sliders, and season length mode/value/order. Saving here writes straight into `config.yml` and reloads live — the same effect as running `/sweather reload` after hand-editing the file.

## API Routes

The panel's frontend talks to a small JSON API, also usable directly if you're scripting against it:

| Route | Method | Description |
|---|---|---|
| `/` | `GET` | Serves the panel's HTML/CSS/JS dashboard. |
| `/api/state` | `GET` | Current intensity, season, days remaining, and forecast for every managed world. |
| `/api/force` | `POST` | Body `{"world":"...","intensity":"...","durationSeconds":N}` — forces an immediate weather transition. |
| `/api/season` | `POST` | Body `{"world":"...","season":"..."}` — forces an immediate season change. |
| `/api/config` | `GET` | The current persistent `config.yml` settings — world lists, weather/season toggles, forecast tuning, weights, season length/order. |
| `/api/config` | `POST` | Persists a subset of `config.yml` settings (only the keys included in the request body are changed) and applies them live via the same reload path `/sweather reload` uses. |

> Like `/sweather reload`, a `POST /api/config` change to `weather.check-interval-seconds` is saved to disk but doesn't take effect until the next plugin/server restart — that one value is only read once, when `WeatherManager` starts.

## Threading

SwagAPI's shared web server dispatches every request handler on a background thread pool — never the main Bukkit thread. Anything that touches a live `World` object (reading current state, forcing a transition, rebuilding the state JSON) is hopped onto the main thread via `Bukkit.getScheduler().runTask(...)` before it runs, and the HTTP response completes once that scheduled work finishes. You'll never get a request that touches Bukkit API off-thread through this panel.

## Per-World Isolation

Building the `/api/state` response loops every managed world individually inside a try/catch — a single world that throws while its state is being read logs a warning naming it and is simply omitted from that response, rather than failing the whole panel with a 500 for every world.

## Related Pages

* [Weather System](weather-system.md)
* [Season Cycle](seasons.md)
* [Configuration](../getting-started/configuration.md)
