# 🔌 Cross-Plugin Event Bus

This is SwagWeather's most distinctive feature: every weather or season change is published live on [SwagAPI](https://github.com/swag617/SwagAPI)'s shared cross-plugin event bus, so other plugins in the Swag617 ecosystem can react to real-time weather and seasons **without ever compiling against SwagWeather's jar**. SwagFarming and SwagFishing both do exactly this today.

## The Bus Itself

SwagAPI exposes a small pub/sub service:

```java
public interface IEventBusService {
    void publish(SwagCrossPluginMessageEvent event);
    void subscribe(String channel, Consumer<SwagCrossPluginMessageEvent> handler, Plugin owner);
    void unsubscribeAll(Plugin owner);
}
```

`SwagCrossPluginMessageEvent` carries a channel name, the publishing plugin's name, a `Map<String, Object>` data payload, and an optional player UUID (SwagWeather always passes `null` for the player, since its events are per-world, not per-player):

```java
public class SwagCrossPluginMessageEvent extends Event {
    String getChannel();
    String getSourcePlugin();
    Map<String, Object> getData();
    UUID getPlayerUuid();
}
```

SwagWeather looks up `IEventBusService` via Bukkit's `ServicesManager` during `onEnable()` and treats it as **required**: if it isn't found (SwagAPI missing or failed to register), SwagWeather logs a severe error and disables itself entirely.

## What SwagWeather Publishes

Every time `WeatherManager` or `SeasonManager` applies a change, it publishes on one of two channels:

| Channel | Payload | Published when |
|---|---|---|
| `"weather"` | `world` (String), `intensity` (String: one of `CLEAR`/`LIGHT_RAIN`/`RAIN`/`HEAVY_RAIN`/`THUNDERSTORM`), `etaSeconds` (long: time until the next queued transition), `forecastNext` (String: that next transition's intensity name) | A forecasted transition becomes due, or `/sweather force` / the web panel forces one |
| `"season"` | `world` (String), `season` (String: one of `SPRING`/`SUMMER`/`FALL`/`WINTER`), `daysRemaining` (long: in-game days until the next advance) | A season advances naturally, or `/sweather season` / the web panel forces one |

> **This exact payload shape is a cross-plugin contract.** The `Intensity` and `Season` enum constant names are published verbatim by name; consumers parse them with their own `valueOf(...)`-style lookups. Changing these names without coordinating across SwagFarming and SwagFishing would silently break both integrations.

## Subscribing From Your Own Plugin

A consumer never needs SwagWeather's jar: only SwagAPI's, plus a runtime soft-dependency on SwagWeather actually being installed and ticking. The pattern used by both SwagFarming and SwagFishing:

```java
RegisteredServiceProvider<IEventBusService> rsp =
        Bukkit.getServicesManager().getRegistration(IEventBusService.class);
if (rsp == null) {
    // SwagAPI itself isn't present — fall back to a vanilla-only behavior.
    return;
}

IEventBusService bus = rsp.getProvider();
bus.subscribe("weather", event -> {
    Map<String, Object> data = event.getData();
    String world = (String) data.get("world");
    String intensity = (String) data.get("intensity"); // e.g. "HEAVY_RAIN"
    // cache it, react to it, etc.
}, myPlugin);

bus.subscribe("season", event -> {
    Map<String, Object> data = event.getData();
    String world = (String) data.get("world");
    String season = (String) data.get("season"); // e.g. "WINTER"
}, myPlugin);
```

Because subscribers cache the *last published value per world* rather than querying SwagWeather synchronously, this pattern degrades gracefully: if SwagWeather is never installed, or hasn't ticked for a given world yet, there's simply no cached value: callers are expected to treat that as "unknown" and fall back to a sane default (vanilla `world.isThundering()`/`hasStorm()` for weather; "no season restriction" for anything season-gated).

## How SwagFarming Uses It

SwagFarming's `WeatherManager` subscribes to both channels through SwagAPI. While it's genuinely raining or storming in a crop's world, outdoor crops get a real, stacking growth-speed multiplier (scaling up with intensity, peaking at `THUNDERSTORM`), plus a small per-tick chance of a permanent quality upgrade during a storm. Each crop can also declare which seasons it grows well in: planting during a listed season avoids a growth penalty that applies the rest of the year. If SwagWeather isn't installed, SwagFarming falls back to vanilla `World#isThundering()`/`hasStorm()` for the weather part; there's no vanilla equivalent for seasons, so season effects simply never apply without SwagWeather.

## How SwagFishing Uses It

SwagFishing's `WeatherIntegration` subscribes the same way and caches the latest intensity/season per world. Built on top of that cache, `WeatherEventManager` polls periodically (default every 10s) to detect *transitions*:

* **Feeding Frenzy**: the moment a world's weather crosses into `HEAVY_RAIN` or `THUNDERSTORM`, a temporary event starts: boosted bite rate and boosted rare-fish odds, broadcast to players in that world. It fires once per storm; a single long storm doesn't re-trigger or stack a second frenzy, and it ends early if the storm clears before its configured duration expires.
* **Seasonal migratory fish notifications**: when SwagWeather's season changes for a world, SwagFishing announces it to players there and names a few fish that just became newly catchable for that season, based on each fish's configured season list.

If `WeatherIntegration` never successfully subscribes (SwagAPI or SwagWeather absent), Feeding Frenzy simply never triggers and every multiplier it would apply returns a neutral `1.0`; the rest of SwagFishing keeps working normally.

## The Compile-Time Alternative: `SwagWeatherAPI`

For a plugin that's already willing to accept a hard or soft compile-time dependency on SwagWeather (admin tooling, for example), a direct API is available instead of the bus:

```java
SwagWeatherAPI api = SwagWeather.getInstance().getApi();

Intensity current   = api.getIntensity(world);
Season season        = api.getSeason(world);
List<ForecastEntry> forecast = api.getForecast(world);
long daysLeft         = api.getDaysRemainingInSeason(world);

api.forceWeather(world, Intensity.THUNDERSTORM, 20 * 60 * 10); // 10 minutes, in ticks
api.forceSeason(world, Season.WINTER);
```

`getIntensity`/`getSeason` default to `CLEAR`/the first configured season respectively for an unmanaged or never-ticked world; they never return `null`. `forceWeather`/`forceSeason` apply immediately and publish on the event bus exactly like the admin command or web panel would, so bus subscribers see the change either way.

SwagWeather's own documentation and both known integrations recommend the event bus over this API for anything that can tolerate the "no cached value yet" case: it's what keeps SwagFarming and SwagFishing free of a hard dependency on SwagWeather's jar.

## Related Pages

* [Weather System](weather-system.md)
* [Season Cycle](seasons.md)
* [Admin Web Panel](admin-web-panel.md)
