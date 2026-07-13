package com.swag.weather.model;

/**
 * A single queued forecast transition: the world will switch to
 * {@link #intensity()} in approximately {@link #etaSeconds()} seconds from
 * the moment this entry was read. {@code etaSeconds} is always computed
 * relative to "now" at query time (see {@code WeatherManager#getForecast})
 * rather than stored as an absolute value, since the entry may be read long
 * after it was generated.
 */
public record ForecastEntry(Intensity intensity, long etaSeconds) {
}
