package com.swag.weather.command;

import com.swag.weather.SwagWeather;
import com.swag.weather.manager.ClaimWeatherManager;
import com.swag.weather.model.ForecastEntry;
import com.swag.weather.model.Intensity;
import com.swag.weather.model.Season;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command for SwagWeather: {@code /sweather <status|forecast|force|season|reload|claim>}.
 *
 * <p>Unlike the SwagTags/SwagRestartScheduler idiom of a single {@code permission:}
 * on the plugin.yml command entry, this command mixes admin-only subcommands with
 * the player-facing {@code claim} subcommand, so plugin.yml declares no command-level
 * permission and each admin subcommand instead checks {@code swagweather.admin}
 * itself via {@link #requireAdmin(CommandSender)}. {@code claim} checks
 * {@code swagweather.claim} (default true) plus GriefPrevention's own
 * {@code Claim#allowEdit} check inside {@link ClaimWeatherManager}.</p>
 */
public class SwagWeatherCommand implements CommandExecutor, TabCompleter {

    private final SwagWeather plugin;

    public SwagWeatherCommand(SwagWeather plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> { if (requireAdmin(sender)) handleStatus(sender, args); }
            case "forecast" -> { if (requireAdmin(sender)) handleForecast(sender, args); }
            case "force" -> { if (requireAdmin(sender)) handleForce(sender, args); }
            case "season" -> { if (requireAdmin(sender)) handleSeason(sender, args); }
            case "reload" -> { if (requireAdmin(sender)) handleReload(sender); }
            case "claim" -> handleClaim(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("swagweather.admin")) return true;
        sender.sendMessage(ChatColor.RED + plugin.getPrefix() + "You don't have permission to do that.");
        return false;
    }

    private void handleStatus(CommandSender sender, String[] args) {
        World world = resolveWorld(sender, args, 1);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + plugin.getPrefix() + "Could not resolve a world — specify one explicitly.");
            return;
        }
        Intensity intensity = plugin.getApi().getIntensity(world);
        Season season = plugin.getApi().getSeason(world);
        long daysRemaining = plugin.getApi().getDaysRemainingInSeason(world);

        sender.sendMessage(ChatColor.GOLD + plugin.getPrefix() + ChatColor.WHITE + world.getName() + ":");
        sender.sendMessage(ChatColor.YELLOW + "  Intensity: " + ChatColor.WHITE + intensity.name());
        sender.sendMessage(ChatColor.YELLOW + "  Season: " + ChatColor.WHITE + season.name()
                + ChatColor.GRAY + " (" + daysRemaining + " day(s) remaining)");
    }

    private void handleForecast(CommandSender sender, String[] args) {
        World world = resolveWorld(sender, args, 1);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + plugin.getPrefix() + "Could not resolve a world — specify one explicitly.");
            return;
        }
        List<ForecastEntry> forecast = plugin.getApi().getForecast(world);
        sender.sendMessage(ChatColor.GOLD + plugin.getPrefix() + ChatColor.WHITE + "Forecast for " + world.getName() + ":");
        if (forecast.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "  (no forecast queued yet — try again shortly)");
            return;
        }
        for (ForecastEntry entry : forecast) {
            sender.sendMessage(ChatColor.YELLOW + "  " + entry.intensity().name()
                    + ChatColor.GRAY + " in ~" + entry.etaSeconds() + "s");
        }
    }

    private void handleForce(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /sweather force <world> <intensity> [durationSeconds]");
            return;
        }
        World world = plugin.getServer().getWorld(args[1]);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + plugin.getPrefix() + "Unknown world: " + args[1]);
            return;
        }
        Intensity intensity;
        try {
            intensity = Intensity.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + plugin.getPrefix() + "Unknown intensity: " + args[2]
                    + ". Valid: " + intensityNames());
            return;
        }
        int durationSeconds = 600;
        if (args.length >= 4) {
            try {
                durationSeconds = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + plugin.getPrefix() + "Invalid duration seconds: " + args[3]);
                return;
            }
        }
        plugin.getApi().forceWeather(world, intensity, durationSeconds * 20);
        sender.sendMessage(ChatColor.GREEN + plugin.getPrefix() + "Forced " + intensity.name()
                + " on " + world.getName() + " for " + durationSeconds + "s.");
    }

    private void handleSeason(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /sweather season <world> <season>");
            return;
        }
        World world = plugin.getServer().getWorld(args[1]);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + plugin.getPrefix() + "Unknown world: " + args[1]);
            return;
        }
        Season season;
        try {
            season = Season.valueOf(args[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + plugin.getPrefix() + "Unknown season: " + args[2]
                    + ". Valid: SPRING, SUMMER, FALL, WINTER");
            return;
        }
        plugin.getApi().forceSeason(world, season);
        sender.sendMessage(ChatColor.GREEN + plugin.getPrefix() + "Forced season " + season.name() + " on " + world.getName() + ".");
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.getWeatherManager().reload();
        plugin.getSeasonManager().reload();
        plugin.getClaimWeatherManager().reload();
        sender.sendMessage(ChatColor.GREEN + plugin.getPrefix() + "Configuration reloaded.");
    }

    /**
     * {@code /sweather claim <clear|rain|storm|reset>} — sets or clears a purely
     * cosmetic per-player weather override for the GriefPrevention claim the
     * sender is currently standing in. Available to any player with
     * {@code swagweather.claim} (default true) whom GriefPrevention's own
     * {@code Claim#allowEdit} permits (owner + trusted builders) — not gated by
     * {@code swagweather.admin}.
     */
    private void handleClaim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + plugin.getPrefix() + "Only players can use this command.");
            return;
        }
        if (!sender.hasPermission("swagweather.claim")) {
            sender.sendMessage(ChatColor.RED + plugin.getPrefix() + "You don't have permission to do that.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /sweather claim <clear|rain|storm|reset>");
            return;
        }

        ClaimWeatherManager manager = plugin.getClaimWeatherManager();
        String action = args[1].toLowerCase();
        ClaimWeatherManager.Result result = switch (action) {
            case "clear" -> manager.trySetOverride(player, ClaimWeatherManager.ClaimOverride.CLEAR);
            case "rain", "storm" -> manager.trySetOverride(player, ClaimWeatherManager.ClaimOverride.DOWNFALL);
            case "reset" -> manager.tryClearOverride(player);
            default -> null;
        };

        if (result == null) {
            sender.sendMessage(ChatColor.RED + "Usage: /sweather claim <clear|rain|storm|reset>");
            return;
        }

        switch (result) {
            case SUCCESS -> {
                if (action.equals("reset")) {
                    sender.sendMessage(ChatColor.GREEN + plugin.getPrefix()
                            + "Your claim's weather override was reset — you'll now see the real world weather again.");
                } else {
                    sender.sendMessage(ChatColor.GREEN + plugin.getPrefix()
                            + "Set this claim's weather override to " + action.toUpperCase()
                            + ". This is purely visual for players in this claim — it doesn't change the server's real weather.");
                }
            }
            case NOT_IN_CLAIM -> sender.sendMessage(ChatColor.RED + plugin.getPrefix()
                    + "You must be standing inside a GriefPrevention claim to use this.");
            case NO_PERMISSION -> sender.sendMessage(ChatColor.RED + plugin.getPrefix()
                    + "You don't have edit permission in this claim.");
            case DISABLED -> sender.sendMessage(ChatColor.RED + plugin.getPrefix()
                    + "Claim weather overrides are unavailable (GriefPrevention not installed, or disabled in config).");
        }
    }

    private World resolveWorld(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            return plugin.getServer().getWorld(args[index]);
        }
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        return null;
    }

    private String intensityNames() {
        StringBuilder sb = new StringBuilder();
        for (Intensity i : Intensity.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(i.name());
        }
        return sb.toString();
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + plugin.getPrefix() + ChatColor.WHITE + "Commands:");
        sender.sendMessage(ChatColor.YELLOW + "  /sweather status [world]");
        sender.sendMessage(ChatColor.YELLOW + "  /sweather forecast [world]");
        sender.sendMessage(ChatColor.YELLOW + "  /sweather force <world> <intensity> [durationSeconds]");
        sender.sendMessage(ChatColor.YELLOW + "  /sweather season <world> <season>");
        sender.sendMessage(ChatColor.YELLOW + "  /sweather reload");
        sender.sendMessage(ChatColor.YELLOW + "  /sweather claim <clear|rain|storm|reset>"
                + ChatColor.GRAY + " — cosmetic-only, requires standing in a GriefPrevention claim you can edit");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("status", "forecast", "force", "season", "reload", "claim"), args[0]);
        }
        if (args.length == 2 && List.of("status", "forecast", "force", "season").contains(args[0].toLowerCase())) {
            return filter(plugin.getServer().getWorlds().stream().map(World::getName).collect(Collectors.toList()), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("claim")) {
            return filter(List.of("clear", "rain", "storm", "reset"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("force")) {
            List<String> names = new ArrayList<>();
            for (Intensity i : Intensity.values()) names.add(i.name());
            return filter(names, args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("season")) {
            return filter(List.of("SPRING", "SUMMER", "FALL", "WINTER"), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(lower)).collect(Collectors.toList());
    }
}
