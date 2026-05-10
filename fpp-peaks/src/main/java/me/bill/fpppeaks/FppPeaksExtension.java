package me.bill.fpppeaks;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppAddonCommand;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.BotSwapController;
import me.bill.fakePlayerPlugin.fakeplayer.BotType;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public final class FppPeaksExtension implements FppExtension {
  private FppApi api;
  private FakePlayerPlugin core;
  private FppAddonCommand command;
  private PeakHoursManager peakHoursManager;

  @Override
  public @NotNull String getName() {
    return "FPP-Peaks";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Adds /fpp peaks as a FakePlayerPlugin extension.";
  }

  @Override
  public @NotNull List<String> getAuthors() {
    return List.of("Bill");
  }

  @Override
  public int getPriority() {
    return 90;
  }

  @Override
  public void onEnable(@NotNull FppApi api) {
    this.api = api;
    if (!(api.getPlugin() instanceof FakePlayerPlugin fpp)) {
      api.getPlugin().getLogger().warning("[FPP-Peaks] Unsupported host plugin instance.");
      return;
    }
    core = fpp;
    saveDefaultConfig();
    Config.registerExternalConfig("peak-hours", getConfig());
    peakHoursManager = new PeakHoursManager(core, core.getFakePlayerManager());
    if (core.getDatabaseManager() != null) {
      peakHoursManager.setDatabaseManager(core.getDatabaseManager());
      if (Config.persistOnRestart()) {
        peakHoursManager.restoreSleepingBotsFromDatabase(core.getDatabaseManager());
      }
    }
    if (Config.peakHoursEnabled() && Config.swapEnabled()) {
      peakHoursManager.start();
    }
    command = new PeaksAddonCommand();
    api.registerCommand(command);
    api.getPlugin().getLogger().info("[FPP-Peaks] Enabled.");
  }

  @Override
  public void onDisable() {
    if (api != null && command != null) api.unregisterCommand(command);
    if (peakHoursManager != null) peakHoursManager.shutdown();
    Config.unregisterExternalConfig("peak-hours", getConfig());
    peakHoursManager = null;
    command = null;
    core = null;
    api = null;
  }

  private boolean enabled() {
    return getConfig().getBoolean("enabled", true);
  }

  private String permission() {
    return getConfig().getString("permissions.command", "fpp.peaks");
  }

  private FakePlayerManager manager() {
    return core != null ? core.getFakePlayerManager() : null;
  }

  private PeakHoursManager peaksManager() {
    return peakHoursManager;
  }

  private final class PeaksAddonCommand implements FppAddonCommand {
    @Override
    public @NotNull String getName() {
      return "peaks";
    }

    @Override
    public @NotNull String getUsage() {
      return "[on|off|status|next|force|list|wake [name]|sleep <name>]";
    }

    @Override
    public @NotNull String getDescription() {
      return "Manage peak-hours bot pool scheduling.";
    }

    @Override
    public @NotNull String getPermission() {
      return permission();
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled()) {
        sender.sendMessage("Peaks extension is disabled.");
        return true;
      }
      if (args.length == 0) {
        if (Config.peakHoursEnabled()) disablePeaks(sender);
        else enablePeaks(sender);
        return true;
      }

      switch (args[0].toLowerCase()) {
        case "on", "true", "yes", "1" -> enablePeaks(sender);
        case "off", "false", "no", "0" -> disablePeaks(sender);
        case "status", "info" -> sendStatus(sender);
        case "next" -> sendNext(sender);
        case "force", "check" -> forceCheck(sender);
        case "list" -> sendList(sender);
        case "wake" -> doWake(sender, args.length >= 2 ? args[1] : null);
        case "sleep" -> {
          if (args.length < 2) {
            sender.sendMessage(Lang.get("peaks-invalid"));
            return true;
          }
          doSleep(sender, args[1]);
        }
        default -> sender.sendMessage(Lang.get("peaks-invalid"));
      }
      return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled()) return List.of();
      if (args.length == 1) {
        String pfx = args[0].toLowerCase();
        return List.of("on", "off", "status", "next", "force", "list", "wake", "sleep").stream()
            .filter(s -> s.startsWith(pfx))
            .collect(Collectors.toList());
      }
      if (args.length == 2) {
        String sub = args[0].toLowerCase();
        String pfx = args[1].toLowerCase();
        if (sub.equals("wake")) {
          PeakHoursManager ph = peaksManager();
          List<String> opts = ph != null ? ph.getSleepingNames() : List.of();
          return java.util.stream.Stream.concat(java.util.stream.Stream.of("all"), opts.stream())
              .filter(s -> s.toLowerCase().startsWith(pfx))
              .collect(Collectors.toList());
        }
        if (sub.equals("sleep")) {
          FakePlayerManager manager = manager();
          if (manager == null) return List.of();
          return manager.getActivePlayers().stream()
              .filter(fp -> fp.getBotType() == BotType.AFK)
              .map(FakePlayer::getName)
              .filter(n -> n.toLowerCase().startsWith(pfx))
              .collect(Collectors.toList());
        }
      }
      return List.of();
    }

    private void enablePeaks(CommandSender sender) {
      if (!Config.swapEnabled()) {
        sender.sendMessage(Lang.get("peaks-requires-swap"));
        return;
      }
      getConfig().set("peak-hours.enabled", true);
      saveConfig();
      reloadConfig();
      Config.registerExternalConfig("peak-hours", getConfig());
      PeakHoursManager ph = peaksManager();
      if (ph != null) {
        if (!ph.isRunning()) ph.start();
        FppScheduler.runSyncLater(core, ph::forceCheck, 5L);
      }
      sender.sendMessage(Lang.get("peaks-enabled"));
      Config.debug("[PeakHours] enabled by " + sender.getName());
    }

    private void disablePeaks(CommandSender sender) {
      getConfig().set("peak-hours.enabled", false);
      saveConfig();
      reloadConfig();
      Config.registerExternalConfig("peak-hours", getConfig());
      PeakHoursManager ph = peaksManager();
      if (ph != null) ph.wakeAll();
      sender.sendMessage(Lang.get("peaks-disabled"));
      Config.debug("[PeakHours] disabled by " + sender.getName());
    }

    private void forceCheck(CommandSender sender) {
      if (!Config.swapEnabled()) {
        sender.sendMessage(Lang.get("peaks-requires-swap"));
        return;
      }
      PeakHoursManager ph = peaksManager();
      if (ph != null) ph.forceCheck();
      sender.sendMessage(Lang.get("peaks-force-check"));
    }

    private void sendStatus(CommandSender sender) {
      if (!Config.peakHoursEnabled()) {
        sender.sendMessage(Lang.get("peaks-status-off"));
        return;
      }
      PeakHoursManager ph = peaksManager();
      FakePlayerManager manager = manager();
      if (ph == null || manager == null) {
        sender.sendMessage(Lang.get("peaks-status-off"));
        return;
      }
      double fraction = ph.computeTargetFraction();
      int sleeping = ph.getSleepingCount();
      int online = (int) manager.getActivePlayers().stream().filter(fp -> fp.getBotType() == BotType.AFK).count();
      BotSwapController swapAI = manager.getBotSwapAI();
      int swapping = swapAI != null ? swapAI.getSwappedOutCount() : 0;
      int total = ph.getTotalPool();
      int target = Math.max(Config.peakHoursMinOnline(), (int) Math.round(fraction * total));

      sender.sendMessage(
          Lang.get(
              "peaks-status-on",
              "window",
              ph.getCurrentWindowLabel(),
              "fraction",
              String.format("%.0f%%", fraction * 100),
              "target",
              String.valueOf(target),
              "sleeping",
              String.valueOf(sleeping),
              "online",
              String.valueOf(online),
              "swapping",
              String.valueOf(swapping),
              "total",
              String.valueOf(total),
              "timezone",
              Config.peakHoursTimezone()));
    }

    private void sendNext(CommandSender sender) {
      PeakHoursManager ph = peaksManager();
      if (ph == null || !Config.peakHoursEnabled()) {
        sender.sendMessage(Lang.get("peaks-status-off"));
        return;
      }
      long seconds = ph.getSecondsToNextWindow();
      if (seconds < 0) {
        sender.sendMessage(Lang.get("peaks-no-windows"));
        return;
      }
      double nextFrac = ph.getNextWindowFraction();
      String timeLabel =
          seconds >= 3600
              ? (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m"
              : seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
      String nextFracLabel = nextFrac >= 0 ? String.format("%.0f%%", nextFrac * 100) : "?";
      sender.sendMessage(Lang.get("peaks-next-window", "time", timeLabel, "next_fraction", nextFracLabel));
    }

    private void sendList(CommandSender sender) {
      PeakHoursManager ph = peaksManager();
      if (ph == null || ph.getSleepingCount() == 0) {
        sender.sendMessage(Lang.get("peaks-list-empty"));
        return;
      }
      List<PeakHoursManager.SleepingBot> entries = ph.getSleepingEntries();
      sender.sendMessage(Lang.get("peaks-list-header", "count", String.valueOf(entries.size())));
      for (PeakHoursManager.SleepingBot sb : entries) {
        Location loc = sb.loc();
        String world = loc != null && loc.getWorld() != null ? loc.getWorld().getName() : "?";
        String x = loc != null ? String.valueOf((int) loc.getX()) : "?";
        String y = loc != null ? String.valueOf((int) loc.getY()) : "?";
        String z = loc != null ? String.valueOf((int) loc.getZ()) : "?";
        sender.sendMessage(
            Lang.get("peaks-list-entry", "name", sb.name(), "world", world, "x", x, "y", y, "z", z));
      }
    }

    private void doWake(CommandSender sender, String name) {
      PeakHoursManager ph = peaksManager();
      if (ph == null) {
        sender.sendMessage(Lang.get("peaks-status-off"));
        return;
      }
      if (name == null || name.equalsIgnoreCase("all")) {
        int count = ph.getSleepingCount();
        ph.wakeAll();
        sender.sendMessage(Lang.get("peaks-wake-all", "count", String.valueOf(count)));
      } else if (ph.wakeBotByName(name)) {
        sender.sendMessage(Lang.get("peaks-wake-success", "name", name));
      } else {
        sender.sendMessage(Lang.get("peaks-sleep-failed", "name", name));
      }
    }

    private void doSleep(CommandSender sender, String name) {
      if (!Config.swapEnabled()) {
        sender.sendMessage(Lang.get("peaks-requires-swap"));
        return;
      }
      PeakHoursManager ph = peaksManager();
      if (ph == null) {
        sender.sendMessage(Lang.get("peaks-status-off"));
        return;
      }
      if (ph.putBotToSleepByName(name)) sender.sendMessage(Lang.get("peaks-sleep-success", "name", name));
      else sender.sendMessage(Lang.get("peaks-sleep-failed", "name", name));
    }
  }

  private void saveConfig() {
    File dataFolder = getDataFolder();
    if (dataFolder == null) return;
    try {
      getConfig().save(new File(dataFolder, "config.yml"));
    } catch (IOException e) {
      if (api != null) {
        api.getPlugin()
            .getLogger()
            .warning("[FPP-Peaks] Failed to save config.yml: " + e.getMessage());
      }
    }
  }
}
