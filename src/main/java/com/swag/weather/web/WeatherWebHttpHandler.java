package com.swag.weather.web;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.swag.weather.SwagWeather;
import com.swag.weather.model.ForecastEntry;
import com.swag.weather.model.Intensity;
import com.swag.weather.model.Season;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * HTTP handler for the SwagWeather admin web panel, mounted under SwagAPI's shared web
 * server at {@code /swagapi/swagweather/} via {@link WeatherWebModule}.
 *
 * <p>Authentication is handled entirely by SwagAPI's session-cookie system before this
 * handler ever runs — this handler has no password/login logic of its own.</p>
 *
 * <h3>Routes</h3>
 * <ul>
 *   <li>{@code GET /} — serves {@code plugins/SwagWeather/web/weather-panel.html}</li>
 *   <li>{@code GET /api/state} — current intensity/season/forecast per managed world</li>
 *   <li>{@code POST /api/force} — body {@code {"world":"...","intensity":"...","durationSeconds":N}},
 *       forces an immediate weather transition</li>
 *   <li>{@code POST /api/season} — body {@code {"world":"...","season":"..."}},
 *       forces an immediate season change</li>
 *   <li>{@code GET /api/config} — current persistent {@code config.yml} settings (world lists,
 *       weather/season toggles, forecast tuning, weights, season length/order)</li>
 *   <li>{@code POST /api/config} — persists a subset of {@code config.yml} settings and applies
 *       them live via {@code WeatherManager.reload()} / {@code SeasonManager.reload()} (same
 *       reload path as {@code /sweather reload}). Note: {@code weather.check-interval-seconds}
 *       is only read once at {@code WeatherManager.start()} time, so a change to that one key
 *       still requires a plugin/server restart to take effect — this matches the existing
 *       {@code /sweather reload} command's behavior, not a new limitation.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>SwagAPI's web server dispatches every handler on a background thread pool — never
 * the main Bukkit thread. Reading world/weather/season state and forcing transitions
 * touches live Bukkit API objects, so all such work is hopped onto the main thread via
 * {@link Bukkit#getScheduler()}{@code .runTask(...)}, mirroring the pattern used by
 * SwagRestartScheduler's {@code WebEditorHttpHandler}.</p>
 */
public class WeatherWebHttpHandler implements HttpHandler {

    private final SwagWeather plugin;
    private final Gson gson = new Gson();

    public WeatherWebHttpHandler(SwagWeather plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            if (path.equals("/") || path.isEmpty()) {
                if (!"GET".equals(method)) {
                    sendPlain(exchange, 405, "Method Not Allowed");
                    return;
                }
                servePanelHtml(exchange);
                return;
            }

            if (path.equals("/api/state")) {
                if ("GET".equals(method)) {
                    handleGetState(exchange);
                } else {
                    sendPlain(exchange, 405, "Method Not Allowed");
                }
                return;
            }

            if (path.equals("/api/force")) {
                if ("POST".equals(method)) {
                    handlePostForce(exchange);
                } else {
                    sendPlain(exchange, 405, "Method Not Allowed");
                }
                return;
            }

            if (path.equals("/api/season")) {
                if ("POST".equals(method)) {
                    handlePostSeason(exchange);
                } else {
                    sendPlain(exchange, 405, "Method Not Allowed");
                }
                return;
            }

            if (path.equals("/api/config")) {
                if ("GET".equals(method)) {
                    handleGetConfig(exchange);
                } else if ("POST".equals(method)) {
                    handlePostConfig(exchange);
                } else {
                    sendPlain(exchange, 405, "Method Not Allowed");
                }
                return;
            }

            sendPlain(exchange, 404, "Unknown route: " + path);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Web panel request error on " + method + " " + path, e);
            try {
                sendJson(exchange, 500, "{\"error\":\"" + escapeJsonString(e.getMessage()) + "\"}");
            } catch (Exception ignored) {
                // Response likely already partially sent — nothing more we can do.
            }
        }
    }

    // -------------------------------------------------------------------------
    // GET / — static HTML
    // -------------------------------------------------------------------------

    private void servePanelHtml(HttpExchange exchange) throws IOException {
        File htmlFile = new File(plugin.getDataFolder(), "web/weather-panel.html");
        if (!htmlFile.exists()) {
            sendPlain(exchange, 404, "Panel file not found. Restart the plugin to regenerate it.");
            return;
        }

        byte[] body;
        try {
            body = Files.readAllBytes(htmlFile.toPath());
        } catch (IOException e) {
            sendPlain(exchange, 500, "Failed to read panel file: " + e.getMessage());
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/state
    // -------------------------------------------------------------------------

    private void handleGetState(HttpExchange exchange) throws IOException {
        CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(buildStateJson());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        future.whenComplete((data, err) -> {
            try {
                if (err != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to build web panel state JSON", err);
                    sendJson(exchange, 500, "{\"error\":\"Failed to read current state\"}");
                } else {
                    sendJson(exchange, 200, gson.toJson(data));
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write web panel GET /api/state response", e);
            }
        });
    }

    /** Must be called on the main thread. */
    private List<Map<String, Object>> buildStateJson() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            try {
                if (!plugin.getWeatherManager().isManaged(world)) continue;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("world", world.getName());
                entry.put("intensity", plugin.getApi().getIntensity(world).name());
                entry.put("season", plugin.getApi().getSeason(world).name());
                entry.put("daysRemaining", plugin.getApi().getDaysRemainingInSeason(world));

                List<Map<String, Object>> forecast = new ArrayList<>();
                for (ForecastEntry fe : plugin.getApi().getForecast(world)) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("intensity", fe.intensity().name());
                    f.put("etaSeconds", fe.etaSeconds());
                    forecast.add(f);
                }
                entry.put("forecast", forecast);

                result.add(entry);
            } catch (Exception e) {
                plugin.getLogger().warning("Web panel state build failed for world '" + world.getName()
                        + "': " + e.getMessage());
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // POST /api/force
    // -------------------------------------------------------------------------

    private void handlePostForce(HttpExchange exchange) throws IOException {
        Map<?, ?> body;
        try {
            body = readJsonBody(exchange);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, "{\"error\":\"Malformed JSON body\"}");
            return;
        }
        if (body == null) {
            sendJson(exchange, 400, "{\"error\":\"Empty request body\"}");
            return;
        }

        String worldName = asString(body.get("world"), null);
        String intensityName = asString(body.get("intensity"), null);
        int durationSeconds = asInt(body.get("durationSeconds"), 600);

        if (worldName == null || intensityName == null) {
            sendJson(exchange, 400, "{\"error\":\"Missing 'world' or 'intensity'\"}");
            return;
        }

        Intensity intensity;
        try {
            intensity = Intensity.valueOf(intensityName.toUpperCase());
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, "{\"error\":\"Unknown intensity: " + escapeJsonString(intensityName) + "\"}");
            return;
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                future.complete(false);
                return;
            }
            plugin.getApi().forceWeather(world, intensity, durationSeconds * 20);
            future.complete(true);
        });

        future.whenComplete((found, err) -> {
            try {
                if (err != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to apply web panel force POST", err);
                    sendJson(exchange, 500, "{\"error\":\"Failed to force weather\"}");
                } else if (!found) {
                    sendJson(exchange, 404, "{\"error\":\"Unknown world: " + escapeJsonString(worldName) + "\"}");
                } else {
                    sendJson(exchange, 200, "{\"ok\":true}");
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write web panel POST /api/force response", e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // POST /api/season
    // -------------------------------------------------------------------------

    private void handlePostSeason(HttpExchange exchange) throws IOException {
        Map<?, ?> body;
        try {
            body = readJsonBody(exchange);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, "{\"error\":\"Malformed JSON body\"}");
            return;
        }
        if (body == null) {
            sendJson(exchange, 400, "{\"error\":\"Empty request body\"}");
            return;
        }

        String worldName = asString(body.get("world"), null);
        String seasonName = asString(body.get("season"), null);
        if (worldName == null || seasonName == null) {
            sendJson(exchange, 400, "{\"error\":\"Missing 'world' or 'season'\"}");
            return;
        }

        Season season;
        try {
            season = Season.valueOf(seasonName.toUpperCase());
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, "{\"error\":\"Unknown season: " + escapeJsonString(seasonName) + "\"}");
            return;
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                future.complete(false);
                return;
            }
            plugin.getApi().forceSeason(world, season);
            future.complete(true);
        });

        future.whenComplete((found, err) -> {
            try {
                if (err != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to apply web panel season POST", err);
                    sendJson(exchange, 500, "{\"error\":\"Failed to force season\"}");
                } else if (!found) {
                    sendJson(exchange, 404, "{\"error\":\"Unknown world: " + escapeJsonString(worldName) + "\"}");
                } else {
                    sendJson(exchange, 200, "{\"ok\":true}");
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write web panel POST /api/season response", e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // GET /api/config
    // -------------------------------------------------------------------------

    private void handleGetConfig(HttpExchange exchange) throws IOException {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(buildConfigJson());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        future.whenComplete((data, err) -> {
            try {
                if (err != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to build web panel config JSON", err);
                    sendJson(exchange, 500, "{\"error\":\"Failed to read current config\"}");
                } else {
                    sendJson(exchange, 200, gson.toJson(data));
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write web panel GET /api/config response", e);
            }
        });
    }

    /** Must be called on the main thread. Mirrors the exact key paths read by WeatherManager/SeasonManager#reload(). */
    private Map<String, Object> buildConfigJson() {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("enabledWorlds", plugin.getConfig().getStringList("worlds.enabled-worlds"));
        data.put("disabledWorlds", plugin.getConfig().getStringList("worlds.disabled-worlds"));

        data.put("weatherEnabled", plugin.getConfig().getBoolean("weather.enabled", true));
        data.put("checkIntervalSeconds", plugin.getConfig().getInt("weather.check-interval-seconds", 30));
        data.put("forecastSize", plugin.getConfig().getInt("weather.forecast-size", 5));
        data.put("minTransitionMinutes", plugin.getConfig().getInt("weather.min-transition-minutes", 10));
        data.put("maxTransitionMinutes", plugin.getConfig().getInt("weather.max-transition-minutes", 30));

        Map<String, Integer> weights = new LinkedHashMap<>();
        for (Intensity intensity : Intensity.values()) {
            weights.put(intensity.name(), plugin.getConfig().getInt("weather.weights." + intensity.name(), 1));
        }
        data.put("weights", weights);

        data.put("seasonEnabled", plugin.getConfig().getBoolean("season.enabled", true));
        data.put("lengthMode", plugin.getConfig().getString("season.length-mode", "real_seconds"));
        data.put("lengthValue", plugin.getConfig().getLong("season.length-value", 1800));
        data.put("order", plugin.getConfig().getStringList("season.order"));

        return data;
    }

    // -------------------------------------------------------------------------
    // POST /api/config
    // -------------------------------------------------------------------------

    /**
     * Persists a subset of {@code config.yml} settings and applies them live.
     *
     * <p>Uses the plugin's live, already-loaded {@link org.bukkit.configuration.file.FileConfiguration}
     * (via {@code plugin.getConfig()}) and calls {@code .set(path, value)} only for the specific keys
     * this form submits, then {@code saveConfig()} — it never constructs a fresh blank
     * {@code YamlConfiguration} and rewrites the whole file from it, which would silently drop any
     * key this form doesn't know about (e.g. {@code web.enabled}).</p>
     */
    private void handlePostConfig(HttpExchange exchange) throws IOException {
        Map<?, ?> body;
        try {
            body = readJsonBody(exchange);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, "{\"error\":\"Malformed JSON body\"}");
            return;
        }
        if (body == null) {
            sendJson(exchange, 400, "{\"error\":\"Empty request body\"}");
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                applyConfigUpdate(body);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        future.whenComplete((v, err) -> {
            try {
                if (err != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to apply web panel config POST", err);
                    sendJson(exchange, 500, "{\"error\":\"Failed to save config\"}");
                } else {
                    sendJson(exchange, 200, "{\"ok\":true}");
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write web panel POST /api/config response", e);
            }
        });
    }

    /** Must be called on the main thread. */
    private void applyConfigUpdate(Map<?, ?> body) {
        if (body.containsKey("enabledWorlds")) {
            plugin.getConfig().set("worlds.enabled-worlds", asStringList(body.get("enabledWorlds")));
        }
        if (body.containsKey("disabledWorlds")) {
            plugin.getConfig().set("worlds.disabled-worlds", asStringList(body.get("disabledWorlds")));
        }

        if (body.containsKey("weatherEnabled")) {
            plugin.getConfig().set("weather.enabled", asBoolean(body.get("weatherEnabled"), true));
        }
        if (body.containsKey("checkIntervalSeconds")) {
            plugin.getConfig().set("weather.check-interval-seconds", Math.max(1, asInt(body.get("checkIntervalSeconds"), 30)));
        }
        if (body.containsKey("forecastSize")) {
            plugin.getConfig().set("weather.forecast-size", Math.max(1, asInt(body.get("forecastSize"), 5)));
        }
        if (body.containsKey("minTransitionMinutes") || body.containsKey("maxTransitionMinutes")) {
            int min = Math.max(1, asInt(body.get("minTransitionMinutes"),
                    plugin.getConfig().getInt("weather.min-transition-minutes", 10)));
            int max = Math.max(min, asInt(body.get("maxTransitionMinutes"),
                    plugin.getConfig().getInt("weather.max-transition-minutes", 30)));
            plugin.getConfig().set("weather.min-transition-minutes", min);
            plugin.getConfig().set("weather.max-transition-minutes", max);
        }

        Object weightsObj = body.get("weights");
        if (weightsObj instanceof Map<?, ?> weightsMap) {
            for (Intensity intensity : Intensity.values()) {
                if (weightsMap.containsKey(intensity.name())) {
                    int w = asInt(weightsMap.get(intensity.name()), 1);
                    plugin.getConfig().set("weather.weights." + intensity.name(), Math.max(0, w));
                }
            }
        }

        if (body.containsKey("seasonEnabled")) {
            plugin.getConfig().set("season.enabled", asBoolean(body.get("seasonEnabled"), true));
        }
        if (body.containsKey("lengthMode")) {
            String mode = asString(body.get("lengthMode"), "real_seconds");
            if (!"real_seconds".equalsIgnoreCase(mode) && !"game_days".equalsIgnoreCase(mode)) {
                mode = "real_seconds";
            }
            plugin.getConfig().set("season.length-mode", mode);
        }
        if (body.containsKey("lengthValue")) {
            Object lv = body.get("lengthValue");
            long value = lv instanceof Number n ? n.longValue() : 1800L;
            plugin.getConfig().set("season.length-value", Math.max(1, value));
        }
        if (body.containsKey("order")) {
            List<String> rawOrder = asStringList(body.get("order"));
            List<String> validOrder = new ArrayList<>();
            for (String name : rawOrder) {
                try {
                    validOrder.add(Season.valueOf(name.trim().toUpperCase()).name());
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Web panel: ignoring unknown season in season.order: " + name);
                }
            }
            if (!validOrder.isEmpty()) {
                plugin.getConfig().set("season.order", validOrder);
            }
        }

        plugin.saveConfig();

        // Apply live, mirroring the exact reload path used by /sweather reload. Note:
        // weather.check-interval-seconds is only consumed once by WeatherManager#start()
        // at plugin enable time, so a change to that one key still needs a real restart.
        plugin.getWeatherManager().reload();
        plugin.getSeasonManager().reload();
    }

    private List<String> asStringList(Object o) {
        List<String> result = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) continue;
                String s = String.valueOf(item).trim();
                if (!s.isEmpty()) result.add(s);
            }
        }
        return result;
    }

    private boolean asBoolean(Object o, boolean def) {
        return o instanceof Boolean b ? b : def;
    }

    // -------------------------------------------------------------------------
    // JSON body parsing helpers
    // -------------------------------------------------------------------------

    private Map<?, ?> readJsonBody(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, Map.class);
        }
    }

    private String asString(Object o, String def) {
        return o instanceof String s ? s : def;
    }

    private int asInt(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    // -------------------------------------------------------------------------
    // HTTP response helpers
    // -------------------------------------------------------------------------

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private void sendPlain(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
