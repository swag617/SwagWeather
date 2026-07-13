package com.swag.weather.model;

/**
 * Weather intensity tiers tracked by SwagWeather on top of vanilla's binary
 * storm/thunder flags. Vanilla has no concept of "how hard" it's raining —
 * this enum is SwagWeather's own model, mapped onto real {@code World} state
 * (see {@code WeatherManager#applyToWorld}) so vanilla mechanics (crop
 * trampling, farms, fire spread) keep working normally.
 *
 * <p>This is a cross-plugin contract: the exact enum constant names are
 * published verbatim (via {@link String#name()}) as the {@code intensity}
 * payload value on the {@code "weather"} event-bus channel. Consumers
 * (SwagFishing's {@code WeatherIntegration}, SwagFarming's
 * {@code WeatherManager}) parse these names with {@code valueOf(...)}-style
 * lookups without a compile-time dependency on this class. Do not rename
 * these constants without coordinating across the ecosystem.</p>
 */
public enum Intensity {
    CLEAR,
    LIGHT_RAIN,
    RAIN,
    HEAVY_RAIN,
    THUNDERSTORM;

    /** Whether this tier should set {@code World#setStorm(true)}. */
    public boolean isStorm() {
        return this != CLEAR;
    }

    /** Whether this tier should set {@code World#setThundering(true)}. */
    public boolean isThundering() {
        return this == THUNDERSTORM;
    }
}
