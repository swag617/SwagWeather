package com.swag.weather.manager;

import com.SwagDev.SwagAPI.api.IEventBusService;
import com.SwagDev.SwagAPI.events.SwagCrossPluginMessageEvent;
import com.swag.weather.SwagWeather;
import com.swag.weather.model.ForecastEntry;
import com.swag.weather.model.Intensity;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives real vanilla weather ({@link World#setStorm}, {@link World#setThundering},
 * {@link World#setWeatherDuration}, {@link World#setThunderDuration}) so vanilla
 * mechanics keep working, while layering a forecast + {@link Intensity} tier model
 * on top that vanilla has no concept of.
 *
 * <p>Each managed world gets a small forecast queue of upcoming transitions with
 * randomly generated ETAs (cadence configurable under {@code weather.*} in
 * config.yml). A repeating task checks whether the head of the queue is due;
 * when it is, that transition becomes the new "current" state, real vanilla
 * weather is applied, the queue is refilled to its configured size, and the
 * change is published on the SwagAPI event bus (channel {@code "weather"}).</p>
 *
 * <p>Thread safety: the check task and all world/forecast mutation happens on
 * the main thread (via {@code runTaskTimer}) since it touches the Bukkit
 * {@link World} API. {@link #getIntensity(World)} and {@link #getForecast(World)}
 * are safe to call from the main thread only, matching every other Bukkit API
 * read in this codebase.</p>
 */
public class WeatherManager {

    private final SwagWeather plugin;
    private final Random random = new Random();

    private final Map<String, WorldState> states = new ConcurrentHashMap<>();
    private BukkitTask task;

    private boolean enabled;
    private int forecastSize;
    private int minTransitionMinutes;
    private int maxTransitionMinutes;
    private Map<Intensity, Integer> weights;
    private Set<String> enabledWorlds;
    private Set<String> disabledWorlds;

    private static final class WorldState {
        volatile Intensity current = Intensity.CLEAR;
        final Deque<PendingTransition> forecast = new ArrayDeque<>();
    }

    private record PendingTransition(Intensity intensity, long scheduledAtMillis) {
    }

    public WeatherManager(SwagWeather plugin) {
        this.plugin = plugin;
        reload();
        plugin.getLogger().info("WeatherManager initialized.");
    }

    /** Reloads all config-driven values. Does not reset in-flight forecasts. */
    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("weather.enabled", true);
        this.forecastSize = Math.max(1, plugin.getConfig().getInt("weather.forecast-size", 5));
        this.minTransitionMinutes = Math.max(1, plugin.getConfig().getInt("weather.min-transition-minutes", 10));
        this.maxTransitionMinutes = Math.max(minTransitionMinutes, plugin.getConfig().getInt("weather.max-transition-minutes", 30));

        this.weights = new HashMap<>();
        for (Intensity intensity : Intensity.values()) {
            int w = plugin.getConfig().getInt("weather.weights." + intensity.name(), 1);
            weights.put(intensity, Math.max(0, w));
        }
        if (weights.values().stream().allMatch(w -> w <= 0)) {
            // Guard against a fully-zeroed config — fall back to uniform weights.
            for (Intensity intensity : Intensity.values()) {
                weights.put(intensity, 1);
            }
        }

        this.enabledWorlds = new LinkedHashSet<>(plugin.getConfig().getStringList("worlds.enabled-worlds"));
        this.disabledWorlds = new LinkedHashSet<>(plugin.getConfig().getStringList("worlds.disabled-worlds"));
    }

    /** Starts the repeating check task. Safe to call once from onEnable(). */
    public void start() {
        if (!enabled) {
            plugin.getLogger().info("Weather system disabled in config — not starting.");
            return;
        }
        int intervalSeconds = Math.max(1, plugin.getConfig().getInt("weather.check-interval-seconds", 30));
        long intervalTicks = intervalSeconds * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, intervalTicks);
    }

    /** Cancels the repeating check task. Safe to call from onDisable(). */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Whether the given world is managed based on the enabled/disabled world lists. */
    public boolean isManaged(World world) {
        if (world == null) return false;
        String name = world.getName();
        if (!enabledWorlds.isEmpty()) {
            return enabledWorlds.contains(name);
        }
        return !disabledWorlds.contains(name);
    }

    // ----------------------------------------------------------------
    // Tick loop
    // ----------------------------------------------------------------

    private void tick() {
        if (!enabled) return;
        for (World world : Bukkit.getWorlds()) {
            if (!isManaged(world)) continue;
            WorldState state = states.computeIfAbsent(world.getName(), n -> newWorldState());
            ensureForecastFilled(world, state);

            PendingTransition head = state.forecast.peekFirst();
            if (head != null && System.currentTimeMillis() >= head.scheduledAtMillis()) {
                state.forecast.pollFirst();
                applyTransition(world, state, head.intensity());
                ensureForecastFilled(world, state);
                publish(world, state);
            }
        }
    }

    private WorldState newWorldState() {
        return new WorldState();
    }

    private void ensureForecastFilled(World world, WorldState state) {
        while (state.forecast.size() < forecastSize) {
            long lastScheduled = state.forecast.isEmpty()
                    ? System.currentTimeMillis()
                    : state.forecast.peekLast().scheduledAtMillis();
            long delayMillis = randomTransitionMillis();
            state.forecast.addLast(new PendingTransition(randomIntensity(), lastScheduled + delayMillis));
        }
    }

    private long randomTransitionMillis() {
        int minutes = minTransitionMinutes + random.nextInt(Math.max(1, maxTransitionMinutes - minTransitionMinutes + 1));
        return minutes * 60_000L;
    }

    private Intensity randomIntensity() {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return Intensity.CLEAR;
        int roll = random.nextInt(total);
        int cumulative = 0;
        for (Intensity intensity : Intensity.values()) {
            cumulative += weights.getOrDefault(intensity, 0);
            if (roll < cumulative) return intensity;
        }
        return Intensity.CLEAR;
    }

    /** Applies an intensity to a world's actual vanilla weather state and internal tracking. */
    private void applyTransition(World world, WorldState state, Intensity intensity) {
        state.current = intensity;

        // Duration until the *next* transition drives how long vanilla weather persists.
        PendingTransition next = state.forecast.peekFirst();
        long durationMillis = next != null
                ? Math.max(0, next.scheduledAtMillis() - System.currentTimeMillis())
                : randomTransitionMillis();
        int durationTicks = (int) Math.min(Integer.MAX_VALUE, durationMillis / 50L);

        applyToWorld(world, intensity, durationTicks);
    }

    /** Sets the real vanilla weather fields on a world to match an intensity. */
    private void applyToWorld(World world, Intensity intensity, int durationTicks) {
        world.setStorm(intensity.isStorm());
        world.setThundering(intensity.isThundering());
        if (durationTicks > 0) {
            world.setWeatherDuration(durationTicks);
            world.setThunderDuration(durationTicks);
        }
    }

    private void publish(World world, WorldState state) {
        IEventBusService bus = plugin.getBusService();
        if (bus == null) return;

        PendingTransition next = state.forecast.peekFirst();
        long etaSeconds = next != null
                ? Math.max(0, (next.scheduledAtMillis() - System.currentTimeMillis()) / 1000L)
                : 0L;
        Intensity forecastNext = next != null ? next.intensity() : state.current;

        Map<String, Object> data = new HashMap<>();
        data.put("world", world.getName());
        data.put("intensity", state.current.name());
        data.put("etaSeconds", etaSeconds);
        data.put("forecastNext", forecastNext.name());

        bus.publish(new SwagCrossPluginMessageEvent("weather", plugin.getName(), data, null));
    }

    // ----------------------------------------------------------------
    // Public API (backing SwagWeatherAPI)
    // ----------------------------------------------------------------

    public Intensity getIntensity(World world) {
        if (world == null) return Intensity.CLEAR;
        WorldState state = states.get(world.getName());
        return state != null ? state.current : Intensity.CLEAR;
    }

    public List<ForecastEntry> getForecast(World world) {
        List<ForecastEntry> result = new ArrayList<>();
        if (world == null) return result;
        WorldState state = states.get(world.getName());
        if (state == null) return result;
        long now = System.currentTimeMillis();
        for (PendingTransition pending : state.forecast) {
            long etaSeconds = Math.max(0, (pending.scheduledAtMillis() - now) / 1000L);
            result.add(new ForecastEntry(pending.intensity(), etaSeconds));
        }
        return result;
    }

    /**
     * Forces an immediate weather transition on the given world, bypassing the
     * random forecast for this one change. The forecast queue is regenerated to
     * start after {@code durationTicks} so future natural transitions still occur.
     */
    public void forceWeather(World world, Intensity intensity, int durationTicks) {
        if (world == null || intensity == null) return;
        WorldState state = states.computeIfAbsent(world.getName(), n -> newWorldState());

        state.forecast.clear();
        long resumeAt = System.currentTimeMillis() + Math.max(0, durationTicks) * 50L;
        state.current = intensity;
        applyToWorld(world, intensity, durationTicks);

        // Regenerate the forecast queue starting after the forced duration.
        long lastScheduled = resumeAt;
        for (int i = 0; i < forecastSize; i++) {
            long delayMillis = randomTransitionMillis();
            lastScheduled += delayMillis;
            state.forecast.addLast(new PendingTransition(randomIntensity(), lastScheduled));
        }

        publish(world, state);
    }

    /** Returns an unmodifiable-in-spirit snapshot of every world currently tracked. */
    public Set<String> getTrackedWorldNames() {
        return new LinkedHashSet<>(states.keySet());
    }
}
