package com.swag.weather;

import com.SwagDev.SwagAPI.api.IEventBusService;
import com.SwagDev.SwagAPI.api.IWebService;
import com.swag.weather.command.SwagWeatherCommand;
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
 *   <li>{@link WeatherWebModule} — admin web panel (soft-dep on {@link IWebService},
 *       though SwagAPI is a hard plugin dependency so it's normally always present).</li>
 *   <li>{@code /sweather} command.</li>
 * </ol>
 */
public class SwagWeather extends JavaPlugin {

    private static SwagWeather instance;

    private IEventBusService busService;
    private IWebService webService;

    private WeatherManager weatherManager;
    private SeasonManager seasonManager;
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
        api = new SwagWeatherAPI(weatherManager, seasonManager);

        weatherManager.start();
        seasonManager.start();

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

    public WeatherWebModule getWebModule() {
        return webModule;
    }

    public IEventBusService getBusService() {
        return busService;
    }

    public IWebService getWebService() {
        return webService;
    }
}
