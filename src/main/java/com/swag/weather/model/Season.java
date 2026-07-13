package com.swag.weather.model;

/**
 * Slow-moving in-game season concept tracked independently of weather
 * intensity/cadence. Cross-plugin contract: published verbatim by name on
 * the {@code "season"} event-bus channel — do not rename these constants.
 */
public enum Season {
    SPRING,
    SUMMER,
    FALL,
    WINTER
}
