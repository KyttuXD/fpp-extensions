package me.bill.fppwaypoints;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.bill.fakePlayerPlugin.api.FppAddonCommand;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppBot;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.api.event.FppBotDespawnEvent;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public final class FppWaypointsExtension implements FppExtension, Listener {
  private FppApi api;
  private Plugin plugin;
  private WaypointStore store;
  private PatrolManager patrols;
  private FppAddonCommand command;

  @Override
  public @NotNull String getName() {
    return "FPP-Waypoints";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Named waypoint route storage and patrols for FPP bots.";
  }

  @Override
  public @NotNull List<String> getAuthors() {
    return List.of("Bill");
  }

  @Override
  public void onEnable(@NotNull FppApi api) {
    this.api = api;
    this.plugin = api.getPlugin();
    saveDefaultConfig();

    File dataFolder = getDataFolder();
    if (dataFolder == null) {
      plugin.getLogger().warning("[FPP-Waypoints] No extension data folder; disabling.");
      return;
    }

    store = new WaypointStore(plugin, dataFolder);
    store.load(getConfig().getBoolean("migration.import-core-waypoints", true));
    patrols =
        new PatrolManager(
            api,
            getConfig().getDouble("patrol.arrival-distance", 1.5),
            getConfig().getBoolean("patrol.random-reshuffle-each-cycle", true));
    command = new WaypointAddonCommand();
    api.registerCommand(command);
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    plugin.getLogger().info("[FPP-Waypoints] Enabled.");
  }

  @Override
  public void onDisable() {
    HandlerList.unregisterAll(this);
    if (patrols != null) patrols.stopAll();
    if (api != null && command != null) api.unregisterCommand(command);
    command = null;
    patrols = null;
    store = null;
    plugin = null;
    api = null;
  }

  @EventHandler
  public void onBotDespawn(FppBotDespawnEvent event) {
    if (patrols != null) patrols.stop(event.getBot());
  }

  private boolean enabled() {
    return getConfig().getBoolean("enabled", true);
  }

  private final class WaypointAddonCommand implements FppAddonCommand {
    @Override
    public @NotNull String getName() {
      return "waypoint";
    }

    @Override
    public @NotNull List<String> getAliases() {
      return List.of("wp");
    }

    @Override
    public @NotNull String getDescription() {
      return "Manage named waypoint routes and patrols.";
    }

    @Override
    public @NotNull String getUsage() {
      return "add <route> | create <route> | remove <route> <index> | delete <route> | clear <route> | list [route] | patrol <bot|all> <route> [--random] | stop <bot|all>";
    }

    @Override
    public @NotNull String getPermission() {
      return "fpp.waypoint";
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled()) {
        sender.sendMessage("§cWaypoint addon is disabled.");
        return true;
      }
      if (args.length == 0) {
        sendUsage(sender);
        return true;
      }

      switch (args[0].toLowerCase()) {
        case "create", "new" -> create(sender, args);
        case "add" -> add(sender, args);
        case "remove", "removepos" -> remove(sender, args);
        case "delete", "del" -> delete(sender, args);
        case "clear" -> clear(sender, args);
        case "list", "info" -> list(sender, args);
        case "patrol", "start" -> patrol(sender, args);
        case "stop" -> stop(sender, args);
        case "reload" -> reload(sender);
        default -> sendUsage(sender);
      }
      return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      List<String> out = new ArrayList<>();
      if (args.length == 1) {
        String partial = args[0].toLowerCase();
        for (String opt : List.of("create", "add", "remove", "delete", "clear", "list", "patrol", "stop", "reload")) {
          if (opt.startsWith(partial)) out.add(opt);
        }
        return out;
      }
      String sub = args[0].toLowerCase();
      String partial = args[args.length - 1].toLowerCase();
      if (args.length == 2 && Set.of("remove", "removepos", "delete", "del", "clear", "list").contains(sub)) {
        addRouteCompletions(out, partial);
      } else if (args.length == 2 && Set.of("patrol", "start", "stop").contains(sub)) {
        if ("all".startsWith(partial)) out.add("all");
        if ("--all".startsWith(partial)) out.add("--all");
        for (FppBot bot : api.getBots()) {
          if (bot.getName().toLowerCase().startsWith(partial)) out.add(bot.getName());
        }
      } else if (args.length == 3 && Set.of("patrol", "start").contains(sub)) {
        addRouteCompletions(out, partial);
      } else if (args.length == 4 && Set.of("patrol", "start").contains(sub) && "--random".startsWith(partial)) {
        out.add("--random");
      } else if (args.length == 3 && Set.of("remove", "removepos").contains(sub)) {
        int count = store.getPositionCount(args[1]);
        for (int i = 1; i <= count; i++) out.add(String.valueOf(i));
      }
      return out;
    }

    private void create(CommandSender sender, String[] args) {
      if (args.length < 2) {
        sender.sendMessage("§cUsage: /fpp waypoint create <route>");
        return;
      }
      String name = args[1];
      if (!validName(sender, name)) return;
      if (!store.createRoute(name)) {
        sender.sendMessage("§cRoute already exists: §e" + name);
        return;
      }
      sender.sendMessage("§aCreated waypoint route §e" + name + "§a.");
    }

    private void add(CommandSender sender, String[] args) {
      if (!(sender instanceof Player player)) {
        sender.sendMessage("§cOnly players can add waypoint positions.");
        return;
      }
      if (args.length < 2) {
        sender.sendMessage("§cUsage: /fpp waypoint add <route>");
        return;
      }
      String name = args[1];
      if (!validName(sender, name)) return;
      boolean created = !store.hasRoute(name);
      int count = store.addPosition(name, player.getLocation());
      if (created) sender.sendMessage("§7Created route §e" + name + "§7.");
      Location loc = player.getLocation();
      sender.sendMessage(
          "§aAdded position §f#"
              + count
              + " §ato §e"
              + name
              + " §7("
              + loc.getWorld().getName()
              + " "
              + loc.getBlockX()
              + ", "
              + loc.getBlockY()
              + ", "
              + loc.getBlockZ()
              + ")");
    }

    private void remove(CommandSender sender, String[] args) {
      if (args.length < 3) {
        sender.sendMessage("§cUsage: /fpp waypoint remove <route> <index>");
        return;
      }
      int index;
      try {
        index = Integer.parseInt(args[2]) - 1;
      } catch (NumberFormatException ex) {
        sender.sendMessage("§cInvalid waypoint index: §e" + args[2]);
        return;
      }
      if (!store.removePosition(args[1], index)) {
        sender.sendMessage("§cNo waypoint §e#" + args[2] + " §cin route §e" + args[1] + "§c.");
        return;
      }
      sender.sendMessage("§aRemoved waypoint §f#" + args[2] + " §afrom §e" + args[1] + "§a.");
    }

    private void delete(CommandSender sender, String[] args) {
      if (args.length < 2) {
        sender.sendMessage("§cUsage: /fpp waypoint delete <route>");
        return;
      }
      if (!store.delete(args[1])) {
        sender.sendMessage("§cRoute not found: §e" + args[1]);
        return;
      }
      sender.sendMessage("§aDeleted waypoint route §e" + args[1] + "§a.");
    }

    private void clear(CommandSender sender, String[] args) {
      if (args.length < 2) {
        sender.sendMessage("§cUsage: /fpp waypoint clear <route>");
        return;
      }
      if (!store.clear(args[1])) {
        sender.sendMessage("§cRoute not found or already empty: §e" + args[1]);
        return;
      }
      sender.sendMessage("§aCleared waypoint route §e" + args[1] + "§a.");
    }

    private void list(CommandSender sender, String[] args) {
      if (args.length >= 2) {
        List<Location> route = store.getRoute(args[1]);
        if (route == null) {
          sender.sendMessage("§cRoute not found: §e" + args[1]);
          return;
        }
        sender.sendMessage("§7Route §e" + args[1] + " §7(" + route.size() + " positions):");
        for (int i = 0; i < route.size(); i++) {
          Location loc = route.get(i);
          sender.sendMessage(
              "§8"
                  + (i + 1)
                  + ". §7"
                  + loc.getWorld().getName()
                  + " §f"
                  + loc.getBlockX()
                  + " "
                  + loc.getBlockY()
                  + " "
                  + loc.getBlockZ());
        }
        return;
      }

      Set<String> names = store.getNames();
      if (names.isEmpty()) {
        sender.sendMessage("§7No waypoint routes yet.");
        return;
      }
      sender.sendMessage("§7Waypoint routes (§f" + names.size() + "§7):");
      for (String name : names) {
        sender.sendMessage("§8- §e" + name + " §7(" + store.getPositionCount(name) + " positions)");
      }
    }

    private void patrol(CommandSender sender, String[] args) {
      if (args.length < 3) {
        sender.sendMessage("§cUsage: /fpp waypoint patrol <bot|all> <route> [--random]");
        return;
      }
      List<Location> route = store.getRoute(args[2]);
      if (route == null) {
        sender.sendMessage("§cRoute not found: §e" + args[2]);
        return;
      }
      boolean random = args.length >= 4 && args[3].equalsIgnoreCase("--random");
      if (isAll(args[1])) {
        int started = 0;
        int skipped = 0;
        for (FppBot bot : api.getBots()) {
          if (patrols.start(bot, args[2], route, random)) started++;
          else skipped++;
        }
        sender.sendMessage(
            "§aStarted §f"
                + started
                + " §abot patrol(s) on §e"
                + args[2]
                + (random ? " §7(random)" : "")
                + "§a. §8("
                + skipped
                + " skipped)");
        return;
      }

      FppBot bot = api.getBot(args[1]).orElse(null);
      if (bot == null) {
        sender.sendMessage("§cNo active bot named §e" + args[1] + "§c.");
        return;
      }
      if (!patrols.start(bot, args[2], route, random)) {
        sender.sendMessage("§cCould not start patrol. Check bot/world/route positions.");
        return;
      }
      sender.sendMessage(
          "§a"
              + bot.getName()
              + " is patrolling §e"
              + args[2]
              + (random ? " §7(random)" : "")
              + "§a.");
    }

    private void stop(CommandSender sender, String[] args) {
      if (args.length < 2) {
        sender.sendMessage("§cUsage: /fpp waypoint stop <bot|all>");
        return;
      }
      if (isAll(args[1])) {
        patrols.stopAll();
        sender.sendMessage("§aStopped all waypoint patrols.");
        return;
      }
      FppBot bot = api.getBot(args[1]).orElse(null);
      if (bot == null) {
        sender.sendMessage("§cNo active bot named §e" + args[1] + "§c.");
        return;
      }
      boolean stopped = patrols.stop(bot);
      sender.sendMessage(stopped ? "§aStopped waypoint patrol for " + bot.getName() + "." : "§7That bot was not on a waypoint patrol.");
    }

    private void reload(CommandSender sender) {
      reloadConfig();
      store.load(getConfig().getBoolean("migration.import-core-waypoints", false));
      sender.sendMessage("§aWaypoint addon reloaded.");
    }

    private boolean validName(CommandSender sender, String name) {
      if (name.matches("[a-zA-Z0-9_\\-]+")) return true;
      sender.sendMessage("§cInvalid route name §e" + name + "§c. Use letters, numbers, _ or -.");
      return false;
    }

    private boolean isAll(String value) {
      return value.equalsIgnoreCase("all") || value.equalsIgnoreCase("--all");
    }

    private void addRouteCompletions(List<String> out, String partial) {
      for (String name : store.getNames()) {
        if (name.startsWith(partial)) out.add(name);
      }
    }

    private void sendUsage(CommandSender sender) {
      sender.sendMessage("§7Usage: §e/fpp waypoint add <route>");
      sender.sendMessage("§7       §e/fpp waypoint list [route]");
      sender.sendMessage("§7       §e/fpp waypoint patrol <bot|all> <route> [--random]");
      sender.sendMessage("§7       §e/fpp waypoint stop <bot|all>");
    }
  }
}
