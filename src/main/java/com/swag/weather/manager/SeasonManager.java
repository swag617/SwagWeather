package com.swag.weather.manager;

import com.SwagDev.SwagAPI.api.IEventBusService;
import com.SwagDev.SwagAPI.events.SwagCrossPluginMessageEvent;
import com.swag.weather.SwagWeather;
import com.swag.weather.model.Season;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks a slow-moving in-game season per world (SPRING/SUMMER/FALL/WINTER),
 * advancing on a cadence independent of {@link WeatherManager}'s forecast
 * cadence. Length is configurable either in real seconds or in-game days
 * ({@code season.length-mode}).
 *
 * <p>Publishes on the SwagAPI event bus (channel {@code "season"}) every time
 * a world's season advances, mirroring {@link WeatherManager#publish}.</p>
 */
public class SeasonManager {

    private static final int TICKS_PER_GAME_DAY = 24000;
    private static final long MILLIS_PER_GAME_DAY = 1_200_000L; // 20 real minutes, matches a vanilla day at 20 TPS

    private final SwagWeather plugin;
    private final Map<String, WorldSeasonState> states = new ConcurrentHashMap<>();
    private BukkitTask task;

    private boolean enabled;
    private String lengthMode; // "real_seconds" or "game_days"
    private long lengthValue;
    private List<Season> order;

    private static final class WorldSeasonState {
        volatile Season current;
        volatile long nextChangeAtMillis;   // used in real_seconds mode
        volatile long seasonStartGameDay;   // used in game_days mode
    }

    public SeasonManager(SwagWeather plugin) {
        this.plugin = plugin;
        reload();
        plugin.getLogger().info("SeasonManager initialized.");
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("season.enabled", true);
        this.lengthMode = plugin.getConfig().getString("season.length-mode", "real_seconds");
        this.lengthValue = Math.max(1, plugin.getConfig().getLong("season.length-value", 1800));

        this.order = new ArrayList<>();
        for (String name : plugin.getConfig().getStringList("season.order")) {
            try {
                order.add(Season.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Unknown season in season.order: " + name);
            }
        }
        if (order.isEmpty()) {
            order.add(Season.SPRING);
            order.add(Season.SUMMER);
            order.add(Season.FALL);
            order.add(Season.WINTER);
        }
    }

    public void start() {
        if (!enabled) {
            plugin.getLogger().info("Season system disabled in config — not starting.");
            return;
        }
        // Checking every 20 seconds is more than sufficient for a slow-moving concept.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 400L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (!enabled) return;
        for (World world : Bukkit.getWorlds()) {
            WorldSeasonState state = states.computeIfAbsent(world.getName(), n -> newState(world));
            boolean due = "game_days".equalsIgnoreCase(lengthMode)
                    ? currentGameDay(world) - state.seasonStartGameDay >= lengthValue
                    : System.currentTimeMillis() >= state.nextChangeAtMillis;

            if (due) {
                advance(world, state);
                publish(world, state);
            }
        }
    }

    private WorldSeasonState newState(World world) {
        WorldSeasonState state = new WorldSeasonState();
        state.current = order.get(0);
        state.nextChangeAtMillis = System.currentTimeMillis() + lengthValue * 1000L;
        state.seasonStartGameDay = currentGameDay(world);
        return state;
    }

    private long currentGameDay(World world) {
        return world.getFullTime() / TICKS_PER_GAME_DAY;
    }

    private void advance(World world, WorldSeasonState state) {
        int idx = order.indexOf(state.current);
        Season next = order.get((idx + 1) % order.size());
        state.current = next;
        state.nextChangeAtMillis = System.currentTimeMillis() + lengthValue * 1000L;
        state.seasonStartGameDay = currentGameDay(world);
    }

    private void publish(World world, WorldSeasonState state) {
        IEventBusService bus = plugin.getBusService();
        if (bus == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("world", world.getName());
        data.put("season", state.current.name());
        data.put("daysRemaining", daysRemaining(world, state));

        bus.publish(new SwagCrossPluginMessageEvent("season", plugin.getName(), data, null));
    }

    private long daysRemaining(World world, WorldSeasonState state) {
        if ("game_days".equalsIgnoreCase(lengthMode)) {
            long elapsed = currentGameDay(world) - state.seasonStartGameDay;
            return Math.max(0, lengthValue - elapsed);
        }
        long remainingMillis = Math.max(0, state.nextChangeAtMillis - System.currentTimeMillis());
        return Math.max(0, (long) Math.ceil(remainingMillis / (double) MILLIS_PER_GAME_DAY));
    }

    // ----------------------------------------------------------------
    // Public API (backing SwagWeatherAPI)
    // ----------------------------------------------------------------

    public Season getSeason(World world) {
        if (world == null) return order.get(0);
        WorldSeasonState state = states.get(world.getName());
        return state != null ? state.current : order.get(0);
    }

    public long getDaysRemaining(World world) {
        if (world == null) return 0;
        WorldSeasonState state = states.get(world.getName());
        return state != null ? daysRemaining(world, state) : 0;
    }

    /** Forces an immediate season change on the given world and publishes it. */
    public void forceSeason(World world, Season season) {
        if (world == null || season == null) return;
        WorldSeasonState state = states.computeIfAbsent(world.getName(), n -> newState(world));
        state.current = season;
        state.nextChangeAtMillis = System.currentTimeMillis() + lengthValue * 1000L;
        state.seasonStartGameDay = currentGameDay(world);
        publish(world, state);
    }
}
