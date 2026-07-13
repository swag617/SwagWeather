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
