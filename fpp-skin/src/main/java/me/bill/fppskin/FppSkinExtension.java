package me.bill.fppskin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppAddonCommand;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.api.FppNameTagService;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.SkinManager;
import me.bill.fakePlayerPlugin.fakeplayer.SkinRepository;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class FppSkinExtension implements FppExtension {
  private FppApi api;
  private FakePlayerPlugin core;
  private FppAddonCommand command;
  private SkinManager skinManager;

  @Override
  public @NotNull String getName() {
    return "FPP-Skin";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Adds /fpp skin as a FakePlayerPlugin extension.";
  }

  @Override
  public @NotNull List<String> getAuthors() {
    return List.of("Bill");
  }

  @Override
  public void onEnable(@NotNull FppApi api) {
    this.api = api;
    if (!(api.getPlugin() instanceof FakePlayerPlugin fpp)) {
      api.getPlugin().getLogger().warning("[FPP-Skin] Unsupported host plugin instance.");
      return;
    }
    core = fpp;
    saveDefaultConfig();
    syncSkinConfig();
    ensureSkinFolder();
    SkinRepository.get().init(core);
    skinManager = new SkinManager(core);
    core.setSkinManager(skinManager);
    command = new SkinAddonCommand();
    api.registerCommand(command);
    api.getPlugin().getLogger().info("[FPP-Skin] Enabled.");
  }

  @Override
  public void onDisable() {
    if (api != null && command != null) api.unregisterCommand(command);
    if (core != null && core.getSkinManager() == skinManager) {
      core.setSkinManager(null);
    }
    skinManager = null;
    command = null;
    core = null;
    api = null;
  }

  private boolean enabled() {
    return getConfig().getBoolean("enabled", true);
  }

  private String permission() {
    return getConfig().getString("permissions.command", "fpp.skin");
  }

  private FakePlayerManager manager() {
    return core != null ? core.getFakePlayerManager() : null;
  }

  private void syncSkinConfig() {
    org.bukkit.configuration.file.FileConfiguration ext = getConfig();
    org.bukkit.configuration.file.FileConfiguration coreConfig = core.getConfig();
    coreConfig.set("skin.mode", ext.getString("skin.mode", "player"));
    coreConfig.set("skin.guaranteed-skin", ext.getBoolean("skin.guaranteed-skin", true));
    coreConfig.set(
        "skin.clear-cache-on-reload", ext.getBoolean("skin.clear-cache-on-reload", true));
    org.bukkit.configuration.ConfigurationSection overrides =
        ext.getConfigurationSection("skin.overrides");
    coreConfig.set("skin.overrides", overrides != null ? overrides.getValues(false) : java.util.Map.of());
    coreConfig.set("skin.pool", ext.getStringList("skin.pool"));
    coreConfig.set("skin.use-skin-folder", ext.getBoolean("skin.use-skin-folder", true));
    coreConfig.set(
        "skin.mineskin.url-upload-enabled",
        ext.getBoolean("skin.mineskin.url-upload-enabled", true));
    coreConfig.set("skin.mineskin.api-key", ext.getString("skin.mineskin.api-key", ""));
    coreConfig.set("skin.mineskin.visibility", ext.getString("skin.mineskin.visibility", "public"));
  }

  private void ensureSkinFolder() {
    java.io.File root = core.getDataFolder();
    java.io.File skinsDir = new java.io.File(root, "skins");
    if (!skinsDir.exists()) skinsDir.mkdirs();
    java.io.File readme = new java.io.File(skinsDir, "README.txt");
    if (readme.exists()) return;
    try (java.io.PrintWriter w = new java.io.PrintWriter(readme)) {
      w.println("# FakePlayerPlugin - Skin Folder");
      w.println("#");
      w.println("# This folder is owned by the FPP-Skin extension.");
      w.println("# Place PNG skin files here to use them for bots.");
      w.println("# Requires the FPP-Skin extension to be installed and enabled.");
      w.println("#");
      w.println("# Naming rules:");
      w.println("#   <botname>.png  - assigned exclusively to the bot with that name");
      w.println("#   anything.png   - added to the random skin pool");
      w.println("#");
      w.println("# Skin files must be standard 64x64 or 64x32 Minecraft skin PNGs.");
    } catch (java.io.IOException e) {
      core.getLogger().fine("[FPP-Skin] Could not write skins/README.txt: " + e.getMessage());
    }
  }

  private boolean canControl(CommandSender sender, FakePlayer bot) {
    if (!(sender instanceof Player player)) return true;
    return api != null
        && api.getBot(bot.getUuid()).map(fppBot -> api.canControlBot(player, fppBot)).orElse(false);
  }

  private final class SkinAddonCommand implements FppAddonCommand {
    @Override
    public @NotNull String getName() {
      return "skin";
    }

    @Override
    public @NotNull String getUsage() {
      return "<bot> <username|url|reset>";
    }

    @Override
    public @NotNull String getDescription() {
      return "Apply a skin to a bot from a Minecraft username, URL, or reset it.";
    }

    @Override
    public @NotNull String getPermission() {
      return permission();
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled()) {
        sender.sendMessage("Skin extension is disabled.");
        return true;
      }

      if (args.length < 1) {
        sender.sendMessage(Lang.get("skin-usage"));
        return true;
      }

      FakePlayerManager manager = manager();
      FakePlayer bot = manager != null ? manager.getByName(args[0]) : null;
      if (bot == null) {
        sender.sendMessage(Lang.get("skin-bot-not-found", "name", args[0]));
        return true;
      }

      if (!canControl(sender, bot)) {
        sender.sendMessage(Lang.get("no-permission"));
        return true;
      }

      if (args.length < 2) {
        sender.sendMessage(Lang.get("skin-usage"));
        return true;
      }

      SkinManager activeSkinManager = skinManager;
      if (activeSkinManager == null) {
        sender.sendMessage(Lang.get("skin-failed", "name", bot.getDisplayName()));
        return true;
      }

      FppNameTagService nameTagService = api.getService(FppNameTagService.class);
      if (nameTagService != null
          && nameTagService.isAvailable()
          && nameTagService.getSkin(bot.getUuid()) != null) {
        sender.sendMessage(Lang.get("skin-no-nametag"));
        return true;
      }

      String skinArg = args[1];
      if (skinArg.equalsIgnoreCase("reset")) {
        boolean ok = activeSkinManager.resetToDefaultSkin(bot);
        sender.sendMessage(Lang.get(ok ? "skin-reset" : "skin-failed", "name", bot.getDisplayName()));
        return true;
      }

      if (isUrlSkin(skinArg)) {
        sender.sendMessage(Lang.get("skin-applying", "name", bot.getDisplayName()));
        activeSkinManager
            .applySkinByUrl(bot, skinArg)
            .thenAccept(success -> sendApplyResult(sender, bot, success, null));
        return true;
      }

      sender.sendMessage(
          Lang.get("skin-applying-player", "name", bot.getDisplayName(), "player", skinArg));
      activeSkinManager
          .applySkinByUsername(bot, skinArg)
          .thenAccept(success -> sendApplyResult(sender, bot, success, skinArg));
      return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      FakePlayerManager manager = manager();
      if (args.length == 1) {
        String prefix = args[0].toLowerCase(Locale.ROOT);
        if (manager == null) return List.of();
        return manager.getActiveNames().stream()
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
            .toList();
      }

      if (args.length == 2) {
        String prefix = args[1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        options.add("reset");
        for (Player player : Bukkit.getOnlinePlayers()) {
          if (manager == null || manager.getByUuid(player.getUniqueId()) == null) {
            options.add(player.getName());
          }
        }
        return options.stream()
            .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix))
            .toList();
      }

      return List.of();
    }

    private boolean isUrlSkin(String skinArg) {
      return skinArg.startsWith("http://")
          || skinArg.startsWith("https://")
          || skinArg.startsWith("data:image");
    }

    private void sendApplyResult(
        CommandSender sender, FakePlayer bot, boolean success, String playerName) {
      FppScheduler.runSync(
          core,
          () -> {
            if (success) {
              sender.sendMessage(Lang.get("skin-applied", "name", bot.getDisplayName()));
            } else if (playerName != null) {
              sender.sendMessage(
                  Lang.get(
                      "skin-player-not-found",
                      "name",
                      bot.getDisplayName(),
                      "player",
                      playerName));
            } else {
              sender.sendMessage(Lang.get("skin-failed", "name", bot.getDisplayName()));
            }
          });
    }
  }
}
