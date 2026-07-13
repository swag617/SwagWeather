package com.swag.weather;

import com.swag.weather.manager.SeasonManager;
import com.swag.weather.manager.WeatherManager;
import com.swag.weather.model.ForecastEntry;
import com.swag.weather.model.Intensity;
import com.swag.weather.model.Season;
import org.bukkit.World;

import java.util.List;

/**
 * Public API surface for SwagWeather. Obtain via {@code SwagWeather.getInstance().getApi()}
 * — the same static-accessor idiom other Swag617 plugins use to expose their managers
 * (e.g. {@code SwagFishing.getInstance().getEnvironmentManager()}).
 *
 * <p>Every plugin-to-plugin interaction should prefer subscribing to the SwagAPI event
 * bus (channels {@code "weather"} / {@code "season"}) over calling this class directly,
 * since that avoids a compile-time dependency on SwagWeather's jar entirely. This class
 * exists for cases where a compile-time dependency is already acceptable (e.g. admin
 * tooling, or a plugin that already hard-depends on SwagWeather).</p>
 */
public class SwagWeatherAPI {

    private final WeatherManager weatherManager;
    private final SeasonManager seasonManager;

    public SwagWeatherAPI(WeatherManager weatherManager, SeasonManager seasonManager) {
        this.weatherManager = weatherManager;
        this.seasonManager = seasonManager;
    }

    /** Current weather intensity tier for the given world. Defaults to CLEAR if unmanaged. */
    public Intensity getIntensity(World world) {
        return weatherManager.getIntensity(world);
    }

    /** Current season for the given world. Defaults to the first configured season if unmanaged. */
    public Season getSeason(World world) {
        return seasonManager.getSeason(world);
    }

    /** The queued upcoming weather transitions for the given world, nearest first. */
    public List<ForecastEntry> getForecast(World world) {
        return weatherManager.getForecast(world);
    }

    /** In-game days remaining until the given world's season advances. */
    public long getDaysRemainingInSeason(World world) {
        return seasonManager.getDaysRemaining(world);
    }

    /**
     * Forces an immediate weather transition, applying real vanilla weather and
     * publishing the change on the event bus. The forecast queue resumes naturally
     * after {@code durationTicks}.
     */
    public void forceWeather(World world, Intensity intensity, int durationTicks) {
        weatherManager.forceWeather(world, intensity, durationTicks);
    }

    /** Forces an immediate season change and publishes it on the event bus. */
    public void forceSeason(World world, Season season) {
        seasonManager.forceSeason(world, season);
    }
}
