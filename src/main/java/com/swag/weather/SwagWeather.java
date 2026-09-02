package com.swag.weather;

import com.SwagDev.SwagAPI.api.IEventBusService;
import com.SwagDev.SwagAPI.api.IPrefixService;
import com.SwagDev.SwagAPI.api.IWebService;
import com.swag.weather.command.SwagWeatherCommand;
import com.swag.weather.manager.ClaimWeatherManager;
import com.swag.weather.manager.SeasonManager;
import com.swag.weather.manager.WeatherManager;
import com.swag.weather.web.WeatherWebModule;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * SwagWeather — forecast + intensity-tier weather control, plus a slow-moving season
 * concept, for the Swag617 plugin ecosystem.
 *
 * <p>Drives real vanilla weather so existing mechanics (crop trampling, farms, fire)
 * keep working, while publishing a richer {@code Intensity}/{@code Season} model on
 * the SwagAPI event bus (channels {@code "weather"} and {@code "season"}) for other
 * plugins (SwagFishing, SwagFarming) to consume without a compile-time dependency.</p>
 *
 * <h3>Manager initialisation order (onEnable)</h3>
 * <ol>
 *   <li>Hook SwagAPI's {@link IEventBusService} / {@link IWebService} — hard dep, must
 *       succeed for the plugin to keep running.</li>
 *   <li>{@link WeatherManager} — forecast queues + vanilla weather driver.</li>
 *   <li>{@link SeasonManager} — slow-moving season progression.</li>
 *   <li>{@link ClaimWeatherManager} — cosmetic per-player GriefPrevention claim
 *       weather override (soft-dep on GriefPrevention); layered on top of, and
 *       fully isolated from, {@link WeatherManager}'s real per-world weather.</li>
 *   <li>{@link WeatherWebModule} — admin web panel (soft-dep on {@link IWebService},
 *       though SwagAPI is a hard plugin dependency so it's normally always present).</li>
 *   <li>{@code /sweather} command.</li>
 * </ol>
 */
public class SwagWeather extends JavaPlugin {

    private static SwagWeather instance;

    private IEventBusService busService;
    private IWebService webService;
    private IPrefixService prefixService;

    private WeatherManager weatherManager;
    private SeasonManager seasonManager;
    private ClaimWeatherManager claimWeatherManager;
    private WeatherWebModule webModule;
    private SwagWeatherAPI api;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!hookSwagAPI()) {
            getLogger().severe("SwagAPI services not found! Is SwagAPI loaded? Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        weatherManager = new WeatherManager(this);
        seasonManager = new SeasonManager(this);
        claimWeatherManager = new ClaimWeatherManager(this);
        api = new SwagWeatherAPI(weatherManager, seasonManager);

        weatherManager.start();
        seasonManager.start();
        getServer().getPluginManager().registerEvents(claimWeatherManager, this);
        claimWeatherManager.start();

        saveWebPanel();
        webModule = new WeatherWebModule(this);
        webModule.enable();

        registerCommand();

        getLogger().info("SwagWeather has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (webModule != null) {
            webModule.disable();
        }
        if (weatherManager != null) {
            weatherManager.shutdown();
        }
        if (seasonManager != null) {
            seasonManager.shutdown();
        }
        if (claimWeatherManager != null) {
            claimWeatherManager.shutdown();
        }
        getLogger().info("SwagWeather has been disabled.");
    }

    /**
     * Hooks the SwagAPI services this plugin needs. {@link IEventBusService} is treated
     * as required (the whole point of this plugin is publishing on it); {@link IWebService}
     * is looked up defensively but its absence only disables the admin web panel.
     */
    private boolean hookSwagAPI() {
        ServicesManager sm = getServer().getServicesManager();

        RegisteredServiceProvider<IEventBusService> busProv = sm.getRegistration(IEventBusService.class);
        if (busProv == null) {
            getLogger().severe("SwagAPI IEventBusService not found!");
            return false;
        }
        busService = busProv.getProvider();
        getLogger().info("Hooked SwagAPI IEventBusService.");

        RegisteredServiceProvider<IWebService> webProv = sm.getRegistration(IWebService.class);
        if (webProv != null) {
            webService = webProv.getProvider();
            getLogger().info("Hooked SwagAPI IWebService.");
        } else {
            getLogger().warning("SwagAPI IWebService not present — admin web panel will be unavailable.");
        }

        RegisteredServiceProvider<IPrefixService> prefixProv = sm.getRegistration(IPrefixService.class);
        prefixService = (prefixProv != null) ? prefixProv.getProvider() : null;

        return true;
    }

    private void registerCommand() {
        PluginCommand cmd = getCommand("sweather");
        if (cmd == null) {
            getLogger().severe("Could not find 'sweather' command in plugin.yml! Commands will not work.");
            return;
        }
        SwagWeatherCommand handler = new SwagWeatherCommand(this);
        cmd.setExecutor(handler);
        cmd.setTabCompleter(handler);
    }

    /** Copies {@code web/weather-panel.html} from the plugin jar to the data folder if absent. */
    private void saveWebPanel() {
        File webDir = new File(getDataFolder(), "web");
        File panelFile = new File(webDir, "weather-panel.html");
        if (!panelFile.exists()) {
            try {
                saveResource("web/weather-panel.html", false);
                getLogger().info("Web panel copied to: " + panelFile.getAbsolutePath());
            } catch (Exception e) {
                getLogger().warning("Could not copy web/weather-panel.html: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public static SwagWeather getInstance() {
        return instance;
    }

    public SwagWeatherAPI getApi() {
        return api;
    }

    public WeatherManager getWeatherManager() {
        return weatherManager;
    }

    public SeasonManager getSeasonManager() {
        return seasonManager;
    }

    public ClaimWeatherManager getClaimWeatherManager() {
        return claimWeatherManager;
    }

    public WeatherWebModule getWebModule() {
        return webModule;
    }

    public IEventBusService getBusService() {
        return busService;
    }

    public IWebService getWebService() {
        return webService;
    }

    public IPrefixService getPrefixService() {
        return prefixService;
    }

    /**
     * Resolves this plugin's self-identifying chat prefix, honoring an admin's
     * per-plugin or global override set via SwagAPI's web panel (see {@link IPrefixService}).
     * Falls back to the literal {@code "[SwagWeather] "} when no override is configured
     * or {@link IPrefixService} isn't available.
     */
    private static final java.util.regex.Pattern LEGACY_HEX =
            java.util.regex.Pattern.compile("(?i)[&§]#([0-9a-f]{6})");
    private static final java.util.regex.Pattern LEGACY_CODE =
            java.util.regex.Pattern.compile("(?i)[&§]([0-9a-fk-or])");
    private static final java.util.Map<Character, String> LEGACY_TAGS = java.util.Map.ofEntries(
            java.util.Map.entry('0', "<black>"), java.util.Map.entry('1', "<dark_blue>"), java.util.Map.entry('2', "<dark_green>"),
            java.util.Map.entry('3', "<dark_aqua>"), java.util.Map.entry('4', "<dark_red>"), java.util.Map.entry('5', "<dark_purple>"),
            java.util.Map.entry('6', "<gold>"), java.util.Map.entry('7', "<gray>"), java.util.Map.entry('8', "<dark_gray>"),
            java.util.Map.entry('9', "<blue>"), java.util.Map.entry('a', "<green>"), java.util.Map.entry('b', "<aqua>"),
            java.util.Map.entry('c', "<red>"), java.util.Map.entry('d', "<light_purple>"), java.util.Map.entry('e', "<yellow>"),
            java.util.Map.entry('f', "<white>"), java.util.Map.entry('k', "<obfuscated>"), java.util.Map.entry('l', "<bold>"),
            java.util.Map.entry('m', "<strikethrough>"), java.util.Map.entry('n', "<underlined>"), java.util.Map.entry('o', "<italic>"),
            java.util.Map.entry('r', "<reset>"));

    /**
     * Renders a stored prefix value from SwagAPI's IPrefixService — which may be MiniMessage
     * tags (admin-typed via the web panel), legacy {@code &}/{@code §} codes (this plugin's own
     * fallback constants), or a mix — into a legacy §-coded string safe for this plugin's
     * ChatColor/sendMessage(String) pipeline. Without this, a MiniMessage-tag override (e.g.
     * {@code <gold>[FleaMC] </gold>}) would show its literal tag text instead of rendering.
     */
    private static String toLegacyPrefix(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        var hex = LEGACY_HEX.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (hex.find()) hex.appendReplacement(sb, "<#" + hex.group(1) + ">");
        hex.appendTail(sb);
        raw = sb.toString();

        var code = LEGACY_CODE.matcher(raw);
        StringBuilder sb2 = new StringBuilder();
        while (code.find()) {
            String tag = LEGACY_TAGS.get(Character.toLowerCase(code.group(1).charAt(0)));
            code.appendReplacement(sb2, tag != null ? java.util.regex.Matcher.quoteReplacement(tag) : "");
        }
        code.appendTail(sb2);

        var component = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(sb2.toString());
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(component);
    }

    public String getPrefix() {
        return toLegacyPrefix((prefixService != null) ? prefixService.getPrefix("SwagWeather", "[SwagWeather] ") : "[SwagWeather] ");
    }
}
