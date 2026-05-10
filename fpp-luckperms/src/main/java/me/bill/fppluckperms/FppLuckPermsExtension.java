package me.bill.fppluckperms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppAddonCommand;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.event.FppBotSpawnEvent;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.api.FppBot;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

public final class FppLuckPermsExtension implements FppExtension, Listener {
  private FppApi api;
  private FakePlayerPlugin core;
  private FppAddonCommand lpInfoCommand;
  private FppAddonCommand rankCommand;

  @Override
  public @NotNull String getName() {
    return "FPP-LuckPerms";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Adds /fpp lpinfo and /fpp rank as FakePlayerPlugin extension commands.";
  }

  @Override
  public @NotNull List<String> getAuthors() {
    return List.of("Bill");
  }

  @Override
  public void onEnable(@NotNull FppApi api) {
    this.api = api;
    if (!(api.getPlugin() instanceof FakePlayerPlugin fpp)) {
      api.getPlugin().getLogger().warning("[FPP-LuckPerms] Unsupported host plugin instance.");
      return;
    }
    core = fpp;
    saveDefaultConfig();
    if (manager() != null) LuckPermsHelper.subscribeLpEvents(core, manager());
    lpInfoCommand = new LpInfoAddonCommand();
    rankCommand = new RankAddonCommand();
    api.registerCommand(lpInfoCommand);
    api.registerCommand(rankCommand);
    Bukkit.getPluginManager().registerEvents(this, core);
    api.getPlugin().getLogger().info("[FPP-LuckPerms] Enabled.");
  }

  @Override
  public void onDisable() {
    if (api != null) {
      if (lpInfoCommand != null) api.unregisterCommand(lpInfoCommand);
      if (rankCommand != null) api.unregisterCommand(rankCommand);
    }
    HandlerList.unregisterAll(this);
    LuckPermsHelper.unsubscribeLpEvents();
    rankCommand = null;
    lpInfoCommand = null;
    core = null;
    api = null;
  }

  private boolean enabled() {
    return getConfig().getBoolean("enabled", true);
  }

  private String defaultGroup() {
    return getConfig().getString("default-group", "");
  }

  private String permission(String key, String fallback) {
    return getConfig().getString("permissions." + key, fallback);
  }

  private FakePlayerManager manager() {
    return core != null ? core.getFakePlayerManager() : null;
  }

  @EventHandler
  public void onBotSpawn(FppBotSpawnEvent event) {
    if (!enabled() || !LuckPermsHelper.isAvailable()) return;
    String defaultGroup = defaultGroup();

    FppBot apiBot = event.getBot();
    if (apiBot.hasMetadata("fpp.explicit-uuid-spawn")) return;

    if (defaultGroup == null || defaultGroup.isBlank()) {
      LuckPermsHelper.getStoredPrimaryGroup(apiBot.getUuid())
          .thenAccept(group -> updateSpawnedBotGroup(apiBot.getUuid(), group));
      return;
    }

    LuckPermsHelper.ensureGroupBeforeSpawn(apiBot.getUuid(), defaultGroup)
        .thenAccept(group -> updateSpawnedBotGroup(apiBot.getUuid(), group));
  }

  private void updateSpawnedBotGroup(java.util.UUID uuid, String group) {
    FppScheduler.runSync(
        core,
        () -> {
          FakePlayerManager manager = manager();
          FakePlayer bot = manager != null ? manager.getByUuid(uuid) : null;
          if (bot == null) return;
          if (group != null && !group.isBlank()) bot.setLuckpermsGroup(group);
          manager.refreshDisplayName(bot);
          manager.persistBotSettings(bot);
        });
  }

  private final class LpInfoAddonCommand implements FppAddonCommand {
    @Override
    public @NotNull String getName() {
      return "lpinfo";
    }

    @Override
    public @NotNull String getUsage() {
      return "";
    }

    @Override
    public @NotNull String getDescription() {
      return "Show LuckPerms integration status for bots.";
    }

    @Override
    public @NotNull String getPermission() {
      return permission("lpinfo", "fpp.lpinfo");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled()) {
        sender.sendMessage("LuckPerms extension is disabled.");
        return true;
      }

      sender.sendMessage(
          Component.text("═══ ", NamedTextColor.DARK_GRAY)
              .append(Component.text("LuckPerms Integration", NamedTextColor.BLUE))
              .append(Component.text(" ═══", NamedTextColor.DARK_GRAY)));

      boolean lpAvail = LuckPermsHelper.isAvailable();
      sender.sendMessage(
          Component.text("LP Installed: ", NamedTextColor.GRAY)
              .append(
                  Component.text(
                      lpAvail ? "YES ✔" : "NO ✘",
                      lpAvail ? NamedTextColor.GREEN : NamedTextColor.RED)));

      if (!lpAvail) {
        sender.sendMessage(
            Component.text(
                "Install LuckPerms for automatic bot prefix & tab ordering.", NamedTextColor.GOLD));
        return true;
      }

      sender.sendMessage(Component.text("─── Config ───", NamedTextColor.DARK_GRAY));
      String defaultGroup = defaultGroup();
      sender.sendMessage(
          Component.text("default-group: ", NamedTextColor.GRAY)
              .append(
                  Component.text(
                      defaultGroup.isBlank() ? "(LP default)" : defaultGroup,
                      NamedTextColor.WHITE)));
      sender.sendMessage(
          Component.text("Groups loaded: ", NamedTextColor.GRAY)
              .append(Component.text(LuckPermsHelper.buildGroupSummary(), NamedTextColor.WHITE)));

      FakePlayerManager manager = manager();
      Collection<FakePlayer> bots = manager != null ? manager.getActivePlayers() : List.of();
      sender.sendMessage(Component.text("─── Active Bots ───", NamedTextColor.DARK_GRAY));
      if (bots.isEmpty()) {
        sender.sendMessage(Component.text("  (none)", NamedTextColor.GRAY));
      } else {
        for (FakePlayer bot : bots) {
          String cachedGroup = bot.getLuckpermsGroup();
          if (cachedGroup != null && !cachedGroup.isBlank()) {
            sender.sendMessage(
                Component.text("  " + bot.getName(), NamedTextColor.AQUA)
                    .append(Component.text(" → ", NamedTextColor.GRAY))
                    .append(Component.text(cachedGroup, NamedTextColor.GREEN))
                    .append(Component.text(" (cached)", NamedTextColor.DARK_GRAY)));
          } else {
            LuckPermsHelper.getStoredPrimaryGroup(bot.getUuid())
                .thenAccept(group -> sendSync(sender, botGroupLine(bot.getName(), group)));
          }
        }
      }

      sender.sendMessage(
          Component.text(
              "Tip: use /fpp rank <bot> <group> or /fpp rank random <group> [num] to"
                  + " change bot LP groups.",
              NamedTextColor.GRAY));
      return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      return List.of();
    }
  }

  private final class RankAddonCommand implements FppAddonCommand {
    @Override
    public @NotNull String getName() {
      return "rank";
    }

    @Override
    public @NotNull String getUsage() {
      return "/fpp rank <bot> <group|clear> | /fpp rank random <group> [num] | /fpp rank list";
    }

    @Override
    public @NotNull String getDescription() {
      return "Assign LuckPerms groups to one bot or random bots.";
    }

    @Override
    public @NotNull String getPermission() {
      return permission("rank", "fpp.rank");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled()) {
        sender.sendMessage("LuckPerms extension is disabled.");
        return true;
      }

      if (!LuckPermsHelper.isAvailable()) {
        sender.sendMessage(Lang.get("rank-no-luckperms"));
        return true;
      }

      if (args.length == 0) {
        sender.sendMessage(Lang.get("rank-usage"));
        return true;
      }

      if (args[0].equalsIgnoreCase("list")) {
        executeList(sender);
        return true;
      }

      if (args[0].equalsIgnoreCase("random")) {
        executeRandom(sender, args);
        return true;
      }

      if (args.length < 2) {
        sender.sendMessage(Lang.get("rank-usage"));
        return true;
      }

      FakePlayerManager manager = manager();
      FakePlayer bot = manager != null ? manager.getByName(args[0]) : null;
      if (bot == null) {
        sender.sendMessage(Lang.get("rank-bot-not-found", "name", args[0]));
        return true;
      }

      if (args[1].equalsIgnoreCase("clear")) executeClear(sender, bot);
      else executeSet(sender, bot, args[1]);
      return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled() || !LuckPermsHelper.isAvailable()) return List.of();
      FakePlayerManager manager = manager();
      if (args.length == 1) {
        List<String> options = new ArrayList<>();
        options.add("list");
        options.add("random");
        if (manager != null) manager.getActivePlayers().forEach(bot -> options.add(bot.getName()));
        return filter(options, args[0]);
      }
      if (args.length == 2 && !args[0].equalsIgnoreCase("list")) {
        List<String> options = new ArrayList<>(LuckPermsHelper.getAllGroupNames());
        if (!args[0].equalsIgnoreCase("random")) options.add("clear");
        return filter(options, args[1]);
      }
      if (args.length == 3 && args[0].equalsIgnoreCase("random")) {
        return filter(new ArrayList<>(List.of("1", "2", "3", "5", "10", "25", "50", "100")), args[2]);
      }
      return List.of();
    }

    private CompletableFuture<Void> applyGroup(FakePlayer bot, String groupName) {
      FakePlayerManager manager = manager();
      return LuckPermsHelper.setPlayerGroup(bot.getUuid(), groupName)
          .thenRun(
              () -> {
                bot.setLuckpermsGroup(groupName);
                if (manager != null) {
                  manager.persistBotSettings(bot);
                  FppScheduler.runSyncLater(
                      core,
                      () -> {
                        if (manager.getByName(bot.getName()) == null) return;
                        manager.refreshDisplayName(bot);
                      },
                      2L);
                }
              });
    }

    private void executeSet(CommandSender sender, FakePlayer bot, String groupName) {
      if (!LuckPermsHelper.groupExists(groupName)) {
        sender.sendMessage(Lang.get("rank-group-not-found", "group", groupName));
        return;
      }

      sender.sendMessage(
          Component.text("Assigning ", NamedTextColor.GRAY)
              .append(Component.text(bot.getName(), NamedTextColor.AQUA))
              .append(Component.text(" → ", NamedTextColor.GRAY))
              .append(Component.text(groupName, NamedTextColor.GREEN))
              .append(Component.text("…", NamedTextColor.GRAY)));

      applyGroup(bot, groupName)
          .thenRun(
              () ->
                  sendSync(
                      sender,
                      Component.text("✔ ", NamedTextColor.GREEN)
                          .append(Component.text(bot.getName(), NamedTextColor.AQUA))
                          .append(Component.text(" → ", NamedTextColor.GRAY))
                          .append(Component.text(groupName, NamedTextColor.GREEN))))
          .exceptionally(
              throwable -> {
                sendSync(
                    sender,
                    Component.text("✘ Failed: " + throwable.getMessage(), NamedTextColor.RED));
                return null;
              });
    }

    private void executeClear(CommandSender sender, FakePlayer bot) {
      String defaultGroup = defaultGroup();
      String targetGroup =
          defaultGroup != null && !defaultGroup.trim().isEmpty() ? defaultGroup.trim() : "default";

      applyGroup(bot, targetGroup)
          .thenRun(
              () ->
                  sendSync(
                      sender,
                      Component.text("✔ ", NamedTextColor.GREEN)
                          .append(Component.text(bot.getName(), NamedTextColor.AQUA))
                          .append(Component.text(" reset to group ", NamedTextColor.GRAY))
                          .append(Component.text(targetGroup, NamedTextColor.GREEN))))
          .exceptionally(
              throwable -> {
                sendSync(
                    sender,
                    Component.text("✘ Failed: " + throwable.getMessage(), NamedTextColor.RED));
                return null;
              });
    }

    private void executeRandom(CommandSender sender, String[] args) {
      if (args.length < 2) {
        sender.sendMessage(Lang.get("rank-usage"));
        return;
      }

      String groupName = args[1];
      if (!LuckPermsHelper.groupExists(groupName)) {
        sender.sendMessage(Lang.get("rank-group-not-found", "group", groupName));
        return;
      }

      int requested = 1;
      if (args.length >= 3) {
        try {
          requested = Integer.parseInt(args[2]);
        } catch (NumberFormatException ignored) {
          sender.sendMessage(Component.text("✘ Invalid number: " + args[2], NamedTextColor.RED));
          return;
        }
      }

      if (requested <= 0) {
        sender.sendMessage(Component.text("✘ Number must be at least 1.", NamedTextColor.RED));
        return;
      }

      FakePlayerManager manager = manager();
      List<FakePlayer> candidates =
          manager != null ? new ArrayList<>(manager.getActivePlayers()) : new ArrayList<>();
      if (candidates.isEmpty()) {
        sender.sendMessage(Component.text("No active bots.", NamedTextColor.GRAY));
        return;
      }

      Collections.shuffle(candidates);
      int count = Math.min(requested, candidates.size());
      List<FakePlayer> selected = new ArrayList<>(candidates.subList(0, count));

      sender.sendMessage(
          Component.text("Assigning ", NamedTextColor.GRAY)
              .append(Component.text(groupName, NamedTextColor.GREEN))
              .append(Component.text(" to ", NamedTextColor.GRAY))
              .append(Component.text(count + " random bot(s)", NamedTextColor.AQUA))
              .append(Component.text("…", NamedTextColor.GRAY)));

      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (FakePlayer bot : selected) {
        futures.add(applyGroup(bot, groupName));
      }

      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .thenRun(
              () ->
                  sendSync(
                      sender,
                      Component.text("✔ ", NamedTextColor.GREEN)
                          .append(Component.text("Assigned ", NamedTextColor.GRAY))
                          .append(Component.text(groupName, NamedTextColor.GREEN))
                          .append(Component.text(" to ", NamedTextColor.GRAY))
                          .append(Component.text(count + " bot(s)", NamedTextColor.AQUA))))
          .exceptionally(
              throwable -> {
                sendSync(
                    sender,
                    Component.text("✘ Failed: " + throwable.getMessage(), NamedTextColor.RED));
                return null;
              });
    }

    private void executeList(CommandSender sender) {
      FakePlayerManager manager = manager();
      Collection<FakePlayer> bots = manager != null ? manager.getActivePlayers() : List.of();
      if (bots.isEmpty()) {
        sender.sendMessage(Component.text("No active bots.", NamedTextColor.GRAY));
        return;
      }

      sender.sendMessage(Component.text("─── Bot LP Groups ───", NamedTextColor.DARK_GRAY));
      for (FakePlayer bot : bots) {
        LuckPermsHelper.getStoredPrimaryGroup(bot.getUuid())
            .thenAccept(group -> sendSync(sender, botGroupLine(bot.getName(), group)));
      }
    }
  }

  private void sendSync(CommandSender sender, Component message) {
    FppScheduler.runSync(core, () -> sender.sendMessage(message));
  }

  private static Component botGroupLine(String botName, String group) {
    return Component.text("  " + botName, NamedTextColor.AQUA)
        .append(Component.text(" → ", NamedTextColor.GRAY))
        .append(Component.text(group, NamedTextColor.GREEN));
  }

  private static List<String> filter(List<String> list, String prefix) {
    if (prefix.isBlank()) return list;
    String lower = prefix.toLowerCase(Locale.ROOT);
    return list.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
  }
}
