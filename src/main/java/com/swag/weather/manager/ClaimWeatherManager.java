package com.swag.weather.manager;

import com.swag.weather.SwagWeather;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Purely cosmetic, per-player weather override for a player standing inside a
 * GriefPrevention claim — layered <b>on top of</b> {@link WeatherManager}, never
 * inside it.
 *
 * <h3>Why this is a separate manager, not a mode of WeatherManager</h3>
 * Minecraft's real weather ({@code World#setStorm}/{@code setThundering}) is
 * server-authoritative and per-world: one world cannot have two different "real"
 * weather states in two different claims at once. This class never calls those
 * methods and never touches {@link WeatherManager}'s state or its
 * {@code publish(...)} call (the one that puts real weather on the SwagAPI
 * {@code "weather"} event-bus channel that SwagFishing/SwagFarming read for
 * fishing/farming bonuses). It only calls {@link Player#setPlayerWeather} /
 * {@link Player#resetPlayerWeather()} — Bukkit/Paper API that changes what a
 * single connected client <i>renders</i>, without altering the world's actual
 * weather. That is exactly what makes a per-claim override safe to layer on top
 * of a per-world bonus system that has no per-player/per-claim concept: the real
 * world weather (and therefore the published bonus-relevant payload) is
 * completely unaffected by anything in this class.
 *
 * <h3>Soft-dependency isolation</h3>
 * GriefPrevention's own types ({@link Claim}, {@link GriefPrevention}) are
 * referenced ONLY inside {@link GriefPreventionClaimBridge} below — never in this
 * class's own field/method signatures. Every call into the bridge is guarded by
 * {@link #griefPreventionEnabled} (checked once, reflectively, in the
 * constructor — mirrors {@code SwagFishing#isGriefPreventionEnabled}/
 * {@code #isInClaim}), so if GriefPrevention isn't installed the bridge class is
 * never loaded and no {@code NoClassDefFoundError} is possible.
 */
public class ClaimWeatherManager implements Listener {

    /**
     * Client-visual override for a claim. Maps 1:1 onto {@link WeatherType} —
     * Bukkit's per-player weather API has no separate "thunder" visual (only
     * CLEAR/DOWNFALL exist), so both the {@code /sweather claim rain} and
     * {@code /sweather claim storm} subcommands resolve to {@link #DOWNFALL}.
     */
    public enum ClaimOverride {
        CLEAR(WeatherType.CLEAR),
        DOWNFALL(WeatherType.DOWNFALL);

        private final WeatherType weatherType;

        ClaimOverride(WeatherType weatherType) {
            this.weatherType = weatherType;
        }

        public WeatherType weatherType() {
            return weatherType;
        }
    }

    public enum Result {
        SUCCESS, DISABLED, NOT_IN_CLAIM, NO_PERMISSION
    }

    private final SwagWeather plugin;
    private final boolean griefPreventionEnabled;
    private boolean configEnabled;
    private int pollIntervalSeconds;

    private final Map<Long, ClaimOverride> overridesByClaimId = new ConcurrentHashMap<>();
    private final Map<UUID, ClaimOverride> lastAppliedByPlayer = new ConcurrentHashMap<>();

    private File storageFile;
    private BukkitTask pollTask;

    public ClaimWeatherManager(SwagWeather plugin) {
        this.plugin = plugin;
        this.griefPreventionEnabled = Bukkit.getPluginManager().getPlugin("GriefPrevention") != null;
        if (griefPreventionEnabled) {
            plugin.getLogger().info("GriefPrevention found — /sweather claim overrides available.");
        } else {
            plugin.getLogger().info("GriefPrevention not found — /sweather claim overrides unavailable.");
        }
        reload();
        loadFromDisk();
    }

    /**
     * Reloads config-driven values. Does not clear the stored per-claim overrides
     * (those live in {@code claim-weather.yml}, independent of config.yml) — but if
     * this reload flips the feature from enabled to disabled, any visual override
     * already applied to a currently-online player is reset immediately. Without
     * this, {@link #applyForPlayer} short-circuits on {@link #isEnabled()} and a
     * player who already had an override applied would keep seeing it indefinitely
     * (until they change worlds or relog), even though an admin just turned the
     * feature off.
     */
    public void reload() {
        boolean wasEnabled = isEnabled();
        this.configEnabled = plugin.getConfig().getBoolean("claim-weather.enabled", true);
        this.pollIntervalSeconds = Math.max(1, plugin.getConfig().getInt("claim-weather.poll-interval-seconds", 5));
        if (wasEnabled && !isEnabled()) {
            resetAllAppliedOverrides();
        }
    }

    /** Whether claim overrides are usable at all (GriefPrevention present AND not disabled in config). */
    public boolean isEnabled() {
        return griefPreventionEnabled && configEnabled;
    }

    /** Starts the periodic per-online-player claim check. Safe to call once from onEnable(). */
    public void start() {
        if (!isEnabled()) return;
        long intervalTicks = pollIntervalSeconds * 20L;
        pollTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pollAllPlayers, 20L, intervalTicks);
    }

    /** Cancels the poll task and resets every online player's visual override. Safe from onDisable(). */
    public void shutdown() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
        resetAllAppliedOverrides();
    }

    /** Resets every currently-online player's applied visual override, best-effort. */
    private void resetAllAppliedOverrides() {
        for (UUID uuid : lastAppliedByPlayer.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                try {
                    player.resetPlayerWeather();
                } catch (Exception ignored) {
                    // Best-effort — don't block plugin disable/reload on it.
                }
            }
        }
        lastAppliedByPlayer.clear();
    }

    // ----------------------------------------------------------------
    // Poll loop — throttled to once every claim-weather.poll-interval-seconds
    // (default 5s) across ALL online players in one pass, rather than an
    // expensive claim lookup on every player-move tick.
    // ----------------------------------------------------------------

    private void pollAllPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                applyForPlayer(player);
            } catch (Exception e) {
                plugin.getLogger().warning("Claim weather poll failed for " + player.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Recomputes the correct visual weather override for one player and applies
     * it ONLY if it changed since the last time this ran for them — avoids
     * spamming setPlayerWeather/resetPlayerWeather packets every poll cycle.
     */
    public void applyForPlayer(Player player) {
        if (!isEnabled()) return;

        Long claimId = claimIdAt(player.getLocation());
        ClaimOverride desired = (claimId != null) ? overridesByClaimId.get(claimId) : null;
        ClaimOverride last = lastAppliedByPlayer.get(player.getUniqueId());

        if (desired == last) return; // enum constants — reference equality is correct, handles both-null too

        if (desired == null) {
            player.resetPlayerWeather();
            lastAppliedByPlayer.remove(player.getUniqueId());
        } else {
            player.setPlayerWeather(desired.weatherType());
            lastAppliedByPlayer.put(player.getUniqueId(), desired);
        }
    }

    // ----------------------------------------------------------------
    // Claim override mutation — used by /sweather claim
    // ----------------------------------------------------------------

    /**
     * Attempts to set a claim-wide visual override for the claim the given
     * player is currently standing in. Requires GriefPrevention
     * {@code Claim#allowEdit} permission (owner + trusted builders) — the same
     * check GriefPrevention itself uses for "can this player change settings
     * here."
     */
    public Result trySetOverride(Player player, ClaimOverride override) {
        Result gate = checkClaimGate(player);
        if (gate != Result.SUCCESS) return gate;

        Long claimId = claimIdAt(player.getLocation());
        overridesByClaimId.put(claimId, override);
        saveToDiskAsync();
        applyForPlayer(player);
        return Result.SUCCESS;
    }

    /** Removes any override on the claim the given player is standing in. */
    public Result tryClearOverride(Player player) {
        Result gate = checkClaimGate(player);
        if (gate != Result.SUCCESS) return gate;

        Long claimId = claimIdAt(player.getLocation());
        overridesByClaimId.remove(claimId);
        saveToDiskAsync();
        applyForPlayer(player);
        return Result.SUCCESS;
    }

    private Result checkClaimGate(Player player) {
        if (!isEnabled()) return Result.DISABLED;
        Long claimId = claimIdAt(player.getLocation());
        if (claimId == null) return Result.NOT_IN_CLAIM;
        if (!canEditClaimAt(player.getLocation(), player)) return Result.NO_PERMISSION;
        return Result.SUCCESS;
    }

    // ----------------------------------------------------------------
    // GriefPrevention lookups — thin wrappers that only ever call into
    // GriefPreventionClaimBridge when griefPreventionEnabled is true, and
    // never expose GriefPrevention's own types in a signature here.
    // ----------------------------------------------------------------

    private Long claimIdAt(Location location) {
        if (!griefPreventionEnabled) return null;
        try {
            return GriefPreventionClaimBridge.claimIdAt(location);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean canEditClaimAt(Location location, Player player) {
        if (!griefPreventionEnabled) return false;
        try {
            return GriefPreventionClaimBridge.canEdit(location, player);
        } catch (Exception e) {
            return false;
        }
    }

    // ----------------------------------------------------------------
    // Persistence — lightweight YAML file in the data folder. SwagWeather has
    // no SQLite/DatabaseManager of its own (WeatherManager's forecast state is
    // intentionally in-memory-only), so a small YAML file matches this
    // plugin's existing config/YAML-only footprint rather than introducing a
    // new persistence layer for a handful of claim entries.
    // ----------------------------------------------------------------

    private File storageFile() {
        if (storageFile == null) {
            storageFile = new File(plugin.getDataFolder(), "claim-weather.yml");
        }
        return storageFile;
    }

    private void loadFromDisk() {
        File file = storageFile();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("overrides");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                long claimId = Long.parseLong(key);
                ClaimOverride override = ClaimOverride.valueOf(section.getString(key, ""));
                overridesByClaimId.put(claimId, override);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping invalid claim-weather.yml entry '" + key + "': " + e.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + overridesByClaimId.size() + " claim weather override(s).");
    }

    private void saveToDiskAsync() {
        Map<Long, ClaimOverride> snapshot = new ConcurrentHashMap<>(overridesByClaimId);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<Long, ClaimOverride> entry : snapshot.entrySet()) {
                yaml.set("overrides." + entry.getKey(), entry.getValue().name());
            }
            try {
                yaml.save(storageFile());
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to save claim-weather.yml: " + e.getMessage());
            }
        });
    }

    // ----------------------------------------------------------------
    // Cleanup — prevents an override from leaking into a new world/session.
    // ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        applyForPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastAppliedByPlayer.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        try {
            player.resetPlayerWeather();
        } catch (Exception ignored) {
        }
        lastAppliedByPlayer.remove(player.getUniqueId());
        applyForPlayer(player); // recompute fresh for the new world/location
    }

    /**
     * The ONLY class in SwagWeather that references GriefPrevention's own
     * types, in both method bodies AND signatures. Every call site above is
     * guarded by {@code griefPreventionEnabled} before this class is ever
     * referenced, so per the JVM's lazy ("active use") class-loading rule this
     * class is never loaded — and GriefPrevention's jar never needs to be on
     * the classpath — unless GriefPrevention is actually installed. Matches
     * the standard Bukkit soft-dependency isolation pattern.
     */
    private static final class GriefPreventionClaimBridge {

        private GriefPreventionClaimBridge() {
        }

        static Long claimIdAt(Location location) {
            Claim claim = GriefPrevention.instance.dataStore.getClaimAt(location, false, null);
            return claim != null ? claim.getID() : null;
        }

        static boolean canEdit(Location location, Player player) {
            Claim claim = GriefPrevention.instance.dataStore.getClaimAt(location, false, null);
            return claim != null && claim.allowEdit(player) == null;
        }
    }
}
