package com.swag.weather.web;

import com.SwagDev.SwagAPI.api.IWebService;
import com.swag.weather.SwagWeather;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Registers the SwagWeather admin web panel with SwagAPI's shared {@link IWebService}.
 *
 * <p>SwagWeather does not own an HttpServer — SwagAPI's shared server does. Login is
 * handled entirely by SwagAPI's own session-cookie system: the mount point at
 * {@code /swagapi/swagweather/} is already gated by SwagAPI's login before
 * {@link WeatherWebHttpHandler} ever runs, so the panel has no password/auth of its own.</p>
 *
 * <p>SwagAPI is a hard dependency for this plugin (declared in plugin.yml), so
 * {@link IWebService} should always be present — but the lookup is still defensively
 * null-checked in case the web server failed to start or the service registration
 * happens later than expected.</p>
 *
 * <p>Config key: {@code web.enabled} (default {@code true}) gates whether this module
 * registers at all.</p>
 */
public class WeatherWebModule {

    private final SwagWeather plugin;
    private IWebService webService;
    private boolean registered = false;

    public WeatherWebModule(SwagWeather plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        if (!plugin.getConfig().getBoolean("web.enabled", true)) {
            plugin.getLogger().info("Web panel disabled in config, skipping.");
            return;
        }

        RegisteredServiceProvider<IWebService> rsp =
                Bukkit.getServicesManager().getRegistration(IWebService.class);
        if (rsp == null) {
            plugin.getLogger().warning(
                    "SwagAPI IWebService not present — web panel unavailable. Is SwagAPI installed and enabled?");
            return;
        }

        webService = rsp.getProvider();
        webService.registerModule(plugin, new WeatherWebHttpHandler(plugin));
        registered = true;
        plugin.getLogger().info("Web panel registered at " + getUrl());
    }

    public void disable() {
        if (!registered || webService == null) {
            return;
        }
        webService.unregisterModule(plugin);
        registered = false;
    }

    public boolean isRegistered() {
        return registered;
    }

    public String getUrl() {
        if (webService == null || !registered) {
            return null;
        }
        return webService.getPluginUrl(plugin.getName().toLowerCase());
    }
}
