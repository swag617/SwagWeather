# 📦 Installation

## Requirements

* **Minecraft:** Paper/Spigot 1.21 (built against `api-version: "1.21"`)
* **Java:** 17+ (the plugin is compiled for Java 17)
* **[SwagAPI](https://github.com/swag617/SwagAPI):** **Required.** Declared as a hard `depend: [SwagAPI]` in `plugin.yml`: SwagWeather looks up SwagAPI's `IEventBusService` in `onEnable()` and immediately disables itself if that service isn't found. There is no fallback mode; SwagWeather cannot run without SwagAPI.

SwagWeather has no other dependencies; it doesn't use Vault, PlaceholderAPI, or WorldGuard.

> Because SwagAPI is a hard dependency, the server's plugin loader guarantees it's fully enabled (and has registered its services) before SwagWeather's `onEnable()` ever runs. There's no enable-order race to worry about.

## Installing

1. **Install [SwagAPI](https://github.com/swag617/SwagAPI) first.** Place its jar in `plugins/` and make sure it starts successfully.
2. **Stop your server** (or make sure SwagAPI is already running if you're hot-adding).
3. Place `SwagWeather.jar` in your `plugins/` folder.
4. **Start your server.** On first boot the plugin generates `plugins/SwagWeather/config.yml` and copies its admin web panel to `plugins/SwagWeather/web/weather-panel.html`.

## Verify Installation

Check your console log for:

```
Hooked SwagAPI IEventBusService.
Hooked SwagAPI IWebService.
WeatherManager initialized.
SeasonManager initialized.
Web panel registered at ...
SwagWeather has been enabled successfully!
```

Then run in-game or from console:

```
/sweather status
```

You should see the current intensity and season for your world printed back. If the plugin failed to enable, the very first thing to check is whether SwagAPI is actually installed and enabled; see [Troubleshooting](../troubleshooting/troubleshooting.md).

## File Structure

After first launch:

```
plugins/SwagWeather/
├── config.yml                    # Main configuration
└── web/
    └── weather-panel.html        # Admin web panel (copied from the jar)
```

> **Note:** SwagWeather keeps no database and writes nothing else to disk. Every forecast queue and season timer lives in memory and regenerates fresh the next time the plugin starts; there's no save-on-shutdown step and nothing to lose on restart.

## First-Time Setup

### 1. Decide Which Worlds Are Managed

By default SwagWeather manages **every** world on the server. If you only want it active in specific worlds (say, your overworld but not a minigame arena), set `worlds.enabled-worlds` in `config.yml` to an explicit list; see [Configuration](configuration.md).

### 2. Try the Core Loop

1. Check the current state: `/sweather status`
2. See what's coming next: `/sweather forecast`
3. Force a change to see it take effect immediately: `/sweather force world THUNDERSTORM 120`
4. Reload after editing `config.yml`: `/sweather reload`

### 3. Install a Companion Plugin (Optional)

SwagWeather is most interesting when something else is listening. Install **SwagFarming** or **SwagFishing** alongside it (both already declare SwagWeather as a soft dependency) and their weather/season integrations activate automatically, with no extra configuration needed on either side. See [Cross-Plugin Event Bus](../core-features/event-bus.md).

## Updating

1. **Stop the server.**
2. Replace `SwagWeather.jar`.
3. **Start the server.**

There's no data to back up: `config.yml` is the only file SwagWeather persists, and forecast/season state simply regenerates on boot.

## Next Steps

* [Configuration](configuration.md)
* [Weather System](../core-features/weather-system.md)
* [Admin Commands](../admin-commands/commands.md)
