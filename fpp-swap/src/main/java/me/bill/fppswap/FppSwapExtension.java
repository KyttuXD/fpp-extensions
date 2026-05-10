package me.bill.fppswap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppAddonCommand;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.api.FppSettingsItem;
import me.bill.fakePlayerPlugin.api.FppSettingsTab;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.lang.Lang;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public final class FppSwapExtension implements FppExtension {
  private FppApi api;
  private FakePlayerPlugin core;
  private Plugin plugin;
  private SwapAddonCommand command;
  private SwapSettingsTab settingsTab;
  private BotSwapAI swapAI;

  @Override
  public @NotNull String getName() {
    return "FPP-Swap";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Adds /fpp swap and swap settings as a FakePlayerPlugin extension.";
  }

  @Override
  public @NotNull List<String> getAuthors() {
    return List.of("Bill");
  }

  @Override
  public int getPriority() {
    return 80;
  }

  @Override
  public void onEnable(@NotNull FppApi api) {
    this.api = api;
    this.plugin = api.getPlugin();
    if (!(plugin instanceof FakePlayerPlugin fpp)) {
      plugin.getLogger().warning("[FPP-Swap] Unsupported host plugin instance.");
      return;
    }
    this.core = fpp;
    saveDefaultConfig();
    Config.registerExternalConfig("swap", getConfig());
    swapAI = new BotSwapAI(core, core.getFakePlayerManager());
    core.getFakePlayerManager().setBotSwapAI(swapAI);
    command = new SwapAddonCommand();
    settingsTab = new SwapSettingsTab();
    api.registerCommand(command);
    api.registerSettingsTab(settingsTab);
    plugin.getLogger().info("[FPP-Swap] Enabled.");
  }

  @Override
  public void onDisable() {
    if (api != null && command != null) api.unregisterCommand(command);
    if (api != null && settingsTab != null) api.unregisterSettingsTab(settingsTab);
    if (swapAI != null) swapAI.cancelAll();
    if (core != null && core.getFakePlayerManager() != null) {
      core.getFakePlayerManager().setBotSwapAI(null);
    }
    Config.unregisterExternalConfig("swap", getConfig());
    swapAI = null;
    settingsTab = null;
    command = null;
    core = null;
    plugin = null;
    api = null;
  }

  private boolean enabled() {
    return getConfig().getBoolean("enabled", true);
  }

  private String permission() {
    return getConfig().getString("permissions.command", "fpp.swap");
  }

  private FakePlayerManager manager() {
    return core != null ? core.getFakePlayerManager() : null;
  }

  private BotSwapAI swapAI() {
    return swapAI;
  }

  private void saveCoreConfigAndReloadSwap() {
    saveConfig();
    reloadConfig();
    Config.registerExternalConfig("swap", getConfig());
    BotSwapAI ai = swapAI();
    FakePlayerManager manager = manager();
    if (ai == null) return;
    ai.cancelAll();
    if (Config.swapEnabled() && manager != null) {
      manager.getActivePlayers().forEach(ai::schedule);
    }
  }

  private final class SwapAddonCommand implements FppAddonCommand {
    @Override
    public @NotNull String getName() {
      return "swap";
    }

    @Override
    public @NotNull String getUsage() {
      return "[on|off|status|now <bot>|list|info <bot>]";
    }

    @Override
    public @NotNull String getDescription() {
      return "Toggle bot session rotation.";
    }

    @Override
    public @NotNull String getPermission() {
      return permission();
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled()) {
        sender.sendMessage("Swap extension is disabled.");
        return true;
      }

      if (args.length == 0) {
        boolean enable = !Config.swapEnabled();
        if (enable) enableSwap(sender);
        else disableSwap(sender);
        Config.debug("swap toggled to " + enable + " by " + sender.getName());
        return true;
      }

      switch (args[0].toLowerCase()) {
        case "on", "true", "yes", "1" -> enableSwap(sender);
        case "off", "false", "no", "0" -> disableSwap(sender);
        case "status" -> sendStatus(sender);
        case "now" -> {
          if (args.length < 2) {
            sender.sendMessage(Lang.get("swap-now-usage"));
            return true;
          }
          String botName = args[1];
          BotSwapAI ai = swapAI();
          if (ai == null) {
            sender.sendMessage(Lang.get("swap-not-available"));
            return true;
          }
          sender.sendMessage(
              ai.triggerNow(botName)
                  ? Lang.get("swap-now-success", "name", botName)
                  : Lang.get("swap-now-failed", "name", botName));
        }
        case "list" -> sendList(sender);
        case "info" -> {
          if (args.length < 2) {
            sender.sendMessage(Lang.get("swap-info-usage"));
            return true;
          }
          sendBotInfo(sender, args[1]);
        }
        default -> sender.sendMessage(Lang.get("swap-invalid"));
      }
      return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      FakePlayerManager manager = manager();
      if (args.length == 1) {
        String pfx = args[0].toLowerCase();
        return List.of("on", "off", "status", "now", "list", "info").stream()
            .filter(s -> s.startsWith(pfx))
            .collect(Collectors.toList());
      }
      if (args.length == 2
          && manager != null
          && (args[0].equalsIgnoreCase("now") || args[0].equalsIgnoreCase("info"))) {
        String pfx = args[1].toLowerCase();
        return manager.getActiveNames().stream()
            .filter(n -> n.toLowerCase().startsWith(pfx))
            .collect(Collectors.toList());
      }
      return List.of();
    }

    private void enableSwap(CommandSender sender) {
      getConfig().set("swap.enabled", true);
      saveCoreConfigAndReloadSwap();
      sender.sendMessage(Lang.get("swap-enabled"));
      Config.debug("swap.enabled set to true by FPP-Swap");
    }

    private void disableSwap(CommandSender sender) {
      getConfig().set("swap.enabled", false);
      saveCoreConfigAndReloadSwap();
      sender.sendMessage(Lang.get("swap-disabled"));
      Config.debug("swap.enabled set to false by FPP-Swap");
    }

    private void sendStatus(CommandSender sender) {
      boolean on = Config.swapEnabled();
      BotSwapAI ai = swapAI();
      if (on && ai != null) {
        long nextSec = ai.getNextSwapSeconds();
        String nextLabel =
            nextSec >= 0
                ? (nextSec >= 60 ? (nextSec / 60) + "m " + (nextSec % 60) + "s" : nextSec + "s")
                : "none";
        int minOnline = Config.swapMinOnline();
        String minLabel = minOnline > 0 ? String.valueOf(minOnline) : "off";
        sender.sendMessage(
            Lang.get(
                "swap-status-on",
                "sessions",
                String.valueOf(ai.getActiveSessionCount()),
                "offline",
                String.valueOf(ai.getSwappedOutCount()),
                "next",
                nextLabel,
                "min",
                minLabel));
      } else {
        sender.sendMessage(Lang.get("swap-status-off"));
      }
    }

    private void sendList(CommandSender sender) {
      BotSwapAI ai = swapAI();
      FakePlayerManager manager = manager();
      if (ai == null || manager == null || ai.getActiveSessions().isEmpty()) {
        sender.sendMessage(Lang.get("swap-list-empty"));
        return;
      }

      Set<UUID> sessions = ai.getActiveSessions();
      List<FakePlayer> scheduled =
          manager.getActivePlayers().stream()
              .filter(fp -> sessions.contains(fp.getUuid()))
              .collect(Collectors.toList());

      if (scheduled.isEmpty()) {
        sender.sendMessage(Lang.get("swap-list-empty"));
        return;
      }

      sender.sendMessage(Lang.get("swap-list-header", "count", String.valueOf(scheduled.size())));
      long now = System.currentTimeMillis();
      for (FakePlayer fp : scheduled) {
        long expiry = ai.getSessionExpiry(fp.getUuid());
        long remainSec = expiry > 0 ? Math.max(0, (expiry - now) / 1000L) : -1;
        String timeLabel =
            remainSec >= 0
                ? (remainSec >= 60 ? (remainSec / 60) + "m" + (remainSec % 60) + "s" : remainSec + "s")
                : "?";
        sender.sendMessage(
            Lang.get(
                "swap-list-entry",
                "name",
                fp.getDisplayName(),
                "personality",
                ai.getPersonalityLabel(fp.getUuid()),
                "swaps",
                String.valueOf(ai.getSwapCount(fp.getUuid())),
                "time",
                timeLabel));
      }
    }

    private void sendBotInfo(CommandSender sender, String botName) {
      FakePlayerManager manager = manager();
      BotSwapAI ai = swapAI();
      FakePlayer fp = manager != null ? manager.getByName(botName) : null;
      if (fp == null) {
        sender.sendMessage(Lang.get("swap-info-not-found", "name", botName));
        return;
      }
      if (ai == null) {
        sender.sendMessage(Lang.get("swap-not-available"));
        return;
      }

      UUID id = fp.getUuid();
      long expiry = ai.getSessionExpiry(id);
      long now = System.currentTimeMillis();
      long remainSec = expiry > 0 ? Math.max(0, (expiry - now) / 1000L) : -1;
      String timeLabel =
          remainSec >= 0
              ? (remainSec >= 60 ? (remainSec / 60) + "m " + (remainSec % 60) + "s" : remainSec + "s")
              : "not scheduled";

      sender.sendMessage(
          Lang.get(
              "swap-info",
              "name",
              fp.getDisplayName(),
              "personality",
              ai.getPersonalityLabel(id),
              "swaps",
              String.valueOf(ai.getSwapCount(id)),
              "time",
              timeLabel,
              "offline",
              String.valueOf(ai.getSwappedOutCount())));
    }
  }

  private final class SwapSettingsTab implements FppSettingsTab {
    @Override
    public @NotNull String getId() {
      return "fpp-swap";
    }

    @Override
    public @NotNull String getLabel() {
      return "🔄 ꜱᴡᴀᴘ";
    }

    @Override
    public @NotNull Material getActiveMaterial() {
      return Material.ENDER_PEARL;
    }

    @Override
    public @NotNull Material getInactiveMaterial() {
      return Material.CLOCK;
    }

    @Override
    public @NotNull Material getSeparatorGlass() {
      return Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    }

    @Override
    public @NotNull List<FppSettingsItem> getItems(@NotNull Player viewer) {
      List<FppSettingsItem> out = new ArrayList<>();
      out.add(toggle("swap.enabled", "ꜱᴡᴀᴘ ꜱʏꜱᴛᴇᴍ", "ʙᴏᴛꜱ ᴘᴇʀɪᴏᴅɪᴄᴀʟʟʏ ʟᴇᴀᴠᴇ ᴀɴᴅ\nʀᴇ-ᴊᴏɪɴ.", Material.ENDER_PEARL));
      out.add(toggle("swap.farewell-chat", "ꜰᴀʀᴇᴡᴇʟʟ ᴍᴇꜱꜱᴀɢᴇꜱ", "ʙᴏᴛꜱ ꜱᴀʏ ɢᴏᴏᴅʙʏᴇ ʙᴇꜰᴏʀᴇ\nʟᴇᴀᴠɪɴɢ.", Material.POPPY));
      out.add(toggle("swap.greeting-chat", "ɢʀᴇᴇᴛɪɴɢ ᴍᴇꜱꜱᴀɢᴇꜱ", "ʙᴏᴛꜱ ɢʀᴇᴇᴛ ᴛʜᴇ ꜱᴇʀᴠᴇʀ\nᴡʜᴇɴ ᴛʜᴇʏ ʀᴇᴛᴜʀɴ.", Material.DANDELION));
      out.add(toggle("swap.same-name-on-rejoin", "ᴋᴇᴇᴘ ɴᴀᴍᴇ ᴏɴ ʀᴇᴊᴏɪɴ", "ʙᴏᴛꜱ ᴛʀʏ ᴛᴏ ʀᴇᴄʟᴀɪᴍ\nᴛʜᴇɪʀ ᴏʀɪɢɪɴᴀʟ ɴᴀᴍᴇ.", Material.NAME_TAG));
      out.add(cycle("swap.session.min", "ꜱᴇꜱꜱɪᴏɴ - ᴍɪɴ (ꜱ)", "ꜱʜᴏʀᴛᴇꜱᴛ ᴏɴʟɪɴᴇ ꜱᴇꜱꜱɪᴏɴ.", Material.CLOCK, new int[] {30, 60, 120, 300, 600}));
      out.add(cycle("swap.session.max", "ꜱᴇꜱꜱɪᴏɴ - ᴍᴀx (ꜱ)", "ʟᴏɴɢᴇꜱᴛ ᴏɴʟɪɴᴇ ꜱᴇꜱꜱɪᴏɴ.", Material.CLOCK, new int[] {60, 120, 300, 600, 1200}));
      out.add(cycle("swap.absence.min", "ᴀʙꜱᴇɴᴄᴇ - ᴍɪɴ (ꜱ)", "ꜱʜᴏʀᴛᴇꜱᴛ ᴏꜰꜰʟɪɴᴇ ᴡᴀɪᴛ.", Material.GRAY_DYE, new int[] {15, 30, 60, 120}));
      out.add(cycle("swap.absence.max", "ᴀʙꜱᴇɴᴄᴇ - ᴍᴀx (ꜱ)", "ʟᴏɴɢᴇꜱᴛ ᴏꜰꜰʟɪɴᴇ ᴡᴀɪᴛ.", Material.GRAY_DYE, new int[] {30, 60, 120, 300}));
      out.add(cycle("swap.max-swapped-out", "ᴍᴀx ᴏꜰꜰʟɪɴᴇ", "0 = ᴜɴʟɪᴍɪᴛᴇᴅ.", Material.HOPPER, new int[] {0, 1, 2, 3, 5, 10}));
      out.add(cycle("swap.min-online", "ᴍɪɴ ᴏɴʟɪɴᴇ", "ᴍɪɴɪᴍᴜᴍ ʙᴏᴛꜱ ᴛᴏ ᴋᴇᴇᴘ ᴏɴʟɪɴᴇ.", Material.LIME_DYE, new int[] {0, 1, 2, 3, 5, 10}));
      out.add(toggle("swap.retry-rejoin", "ʀᴇᴛʀʏ ʀᴇᴊᴏɪɴ", "ʀᴇᴛʀʏ ɪꜰ ᴀ ꜱᴡᴀᴘᴘᴇᴅ ʙᴏᴛ\nꜰᴀɪʟꜱ ᴛᴏ ʀᴇᴊᴏɪɴ.", Material.RECOVERY_COMPASS));
      out.add(cycle("swap.retry-delay", "ʀᴇᴛʀʏ ᴅᴇʟᴀʏ (ꜱ)", "ᴡᴀɪᴛ ʙᴇꜰᴏʀᴇ ʀᴇᴛʀʏɪɴɢ.", Material.REPEATER, new int[] {30, 60, 120, 300, 600}));
      return out;
    }

    private FppSettingsItem toggle(String path, String label, String description, Material icon) {
      return new SwapItem(
          path,
          label,
          description,
          icon,
          () -> getConfig().getBoolean(path, false) ? "✔ ᴏɴ" : "✘ ᴏꜰꜰ",
          () -> {
            boolean next = !getConfig().getBoolean(path, false);
            getConfig().set(path, next);
            saveCoreConfigAndReloadSwap();
          });
    }

    private FppSettingsItem cycle(String path, String label, String description, Material icon, int[] values) {
      return new SwapItem(
          path,
          label,
          description,
          icon,
          () -> String.valueOf(getConfig().getInt(path, values[0])),
          () -> {
            int current = getConfig().getInt(path, values[0]);
            int next = values[0];
            for (int i = 0; i < values.length; i++) {
              if (values[i] == current) {
                next = values[(i + 1) % values.length];
                break;
              }
            }
            getConfig().set(path, next);
            saveCoreConfigAndReloadSwap();
          });
    }
  }

  private record SwapItem(
      @NotNull String getId,
      @NotNull String getLabel,
      @NotNull String getDescription,
      @NotNull Material getIcon,
      java.util.function.Supplier<String> valueSupplier,
      Runnable clickAction)
      implements FppSettingsItem {
    @Override
    public String getValue() {
      return valueSupplier.get();
    }

    @Override
    public void onClick(@NotNull Player viewer) {
      clickAction.run();
    }
  }

  private void saveConfig() {
    File dataFolder = getDataFolder();
    if (dataFolder == null) return;
    try {
      getConfig().save(new File(dataFolder, "config.yml"));
    } catch (IOException e) {
      plugin.getLogger().warning("[FPP-Swap] Failed to save config.yml: " + e.getMessage());
    }
  }
}
