package me.bill.fppchat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppAddonCommand;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.lang.Lang;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public final class FppChatExtension implements FppExtension {
  private FppApi api;
  private FakePlayerPlugin core;
  private ChatAddonCommand command;
  private BotMessageConfig botMessages;
  private BotChatAI chatAI;

  @Override
  public @NotNull String getName() {
    return "FPP-Chat";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Adds /fpp chat as a FakePlayerPlugin extension.";
  }

  @Override
  public @NotNull List<String> getAuthors() {
    return List.of("Bill");
  }

  @Override
  public void onEnable(@NotNull FppApi api) {
    this.api = api;
    if (!(api.getPlugin() instanceof FakePlayerPlugin fpp)) {
      api.getPlugin().getLogger().warning("[FPP-Chat] Unsupported host plugin instance.");
      return;
    }
    this.core = fpp;
    saveDefaultConfig();
    Config.registerExternalConfig("fake-chat", getConfig());
    migrateLegacyBotMessages();
    botMessages = new BotMessageConfig(this);
    botMessages.reload();
    Config.setChatMessageProvider(botMessages);
    chatAI = new BotChatAI(core, core.getFakePlayerManager());
    core.setBotChatAI(chatAI);
    command = new ChatAddonCommand();
    api.registerCommand(command);
    api.getPlugin().getLogger().info("[FPP-Chat] Enabled.");
  }

  @Override
  public void onDisable() {
    if (api != null && command != null) api.unregisterCommand(command);
    if (chatAI != null) chatAI.cancelAll();
    if (core != null) core.setBotChatAI(null);
    if (botMessages != null) Config.clearChatMessageProvider(botMessages);
    Config.unregisterExternalConfig("fake-chat", getConfig());
    botMessages = null;
    chatAI = null;
    command = null;
    core = null;
    api = null;
  }

  void info(String message) {
    if (api != null) api.getPlugin().getLogger().info("[FPP-Chat] " + message);
  }

  void warn(String message) {
    if (api != null) api.getPlugin().getLogger().warning("[FPP-Chat] " + message);
  }

  private void migrateLegacyBotMessages() {
    File dataFolder = getDataFolder();
    if (dataFolder == null || core == null) return;

    File target = new File(dataFolder, "bot-messages.yml");
    if (target.exists()) return;

    File legacy = new File(core.getDataFolder(), "bot-messages.yml");
    if (!legacy.isFile()) return;

    target.getParentFile().mkdirs();
    try {
      Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
      info("Migrated legacy bot-messages.yml into the FPP-Chat extension folder.");
    } catch (IOException e) {
      try {
        Files.copy(legacy.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        Files.deleteIfExists(legacy.toPath());
        info("Migrated legacy bot-messages.yml into the FPP-Chat extension folder.");
      } catch (IOException fallback) {
        warn("Failed to migrate legacy bot-messages.yml: " + fallback.getMessage());
      }
    }
  }

  private boolean enabled() {
    return getConfig().getBoolean("enabled", true);
  }

  private String permission() {
    return getConfig().getString("permissions.command", "fpp.chat");
  }

  private FakePlayerManager manager() {
    return core != null ? core.getFakePlayerManager() : null;
  }

  private BotChatAI chatAI() {
    return chatAI;
  }

  private void saveAndReloadChat() {
    saveConfig();
    reloadConfig();
    Config.registerExternalConfig("fake-chat", getConfig());
    BotChatAI chatAI = chatAI();
    if (chatAI == null) return;
    if (Config.fakeChatEnabled()) chatAI.restartLoops();
    else chatAI.cancelAll();
  }

  private final class ChatAddonCommand implements FppAddonCommand {
    @Override
    public @NotNull String getName() {
      return "chat";
    }

    @Override
    public @NotNull String getUsage() {
      return "[on|off|status|all] | <bot> [on|off|status|info|mute [sec]|say <msg>]";
    }

    @Override
    public @NotNull String getDescription() {
      return "Toggles bot auto-chat globally or per-bot.";
    }

    @Override
    public @NotNull String getPermission() {
      return permission();
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled()) {
        sender.sendMessage("Chat extension is disabled.");
        return true;
      }

      FakePlayerManager manager = manager();
      if (args.length == 0) {
        setGlobal(sender, !Config.fakeChatEnabled());
        Config.debug("fake-chat.enabled toggled by " + sender.getName());
        return true;
      }

      String first = args[0].toLowerCase();
      switch (first) {
        case "on", "true", "yes", "1" -> {
          setGlobal(sender, true);
          return true;
        }
        case "off", "false", "no", "0" -> {
          setGlobal(sender, false);
          return true;
        }
        case "status" -> {
          sender.sendMessage(Lang.get(Config.fakeChatEnabled() ? "chat-status-on" : "chat-status-off"));
          return true;
        }
        case "all" -> {
          handleAll(sender, manager, args);
          return true;
        }
        default -> {
        }
      }

      if (manager == null) {
        sender.sendMessage(Lang.get("chat-invalid"));
        return true;
      }

      FakePlayer bot = manager.getByName(args[0]);
      if (bot == null) {
        sender.sendMessage(Lang.get("chat-bot-not-found", "name", args[0]));
        return true;
      }

      if (args.length < 2) {
        boolean enable = !bot.isChatEnabled();
        bot.setChatEnabled(enable);
        sender.sendMessage(
            Lang.get(enable ? "chat-bot-enabled" : "chat-bot-disabled", "name", bot.getDisplayName()));
        manager.persistBotSettings(bot);
        Config.debugChat(bot.getName() + " chat toggled to " + enable + " by " + sender.getName());
        return true;
      }

      String sub = args[1].toLowerCase();
      if (sub.equals("status")) {
        sender.sendMessage(
            Lang.get(
                bot.isChatEnabled() ? "chat-bot-status-on" : "chat-bot-status-off",
                "name",
                bot.getDisplayName()));
        return true;
      }
      if (sub.equals("on") || sub.equals("true") || sub.equals("1")) {
        bot.setChatEnabled(true);
        sender.sendMessage(Lang.get("chat-bot-enabled", "name", bot.getDisplayName()));
        manager.persistBotSettings(bot);
        Config.debugChat(bot.getName() + " chat re-enabled by " + sender.getName());
        return true;
      }
      if (sub.equals("off") || sub.equals("false") || sub.equals("0")) {
        bot.setChatEnabled(false);
        sender.sendMessage(Lang.get("chat-bot-disabled", "name", bot.getDisplayName()));
        manager.persistBotSettings(bot);
        Config.debugChat(bot.getName() + " chat disabled by " + sender.getName());
        return true;
      }
      if (sub.equals("say")) {
        if (args.length < 3) {
          sender.sendMessage(Lang.get("chat-say-usage"));
          return true;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        BotChatAI chatAI = chatAI();
        if (chatAI != null) chatAI.forceSendMessageResolved(bot, message);
        sender.sendMessage(Lang.get("chat-bot-said", "name", bot.getDisplayName(), "message", message));
        return true;
      }
      if (sub.equals("info")) {
        sender.sendMessage(
            Lang.get(
                "chat-bot-info",
                "name",
                bot.getDisplayName(),
                "enabled",
                bot.isChatEnabled() ? "yes" : "no"));
        return true;
      }
      if (sub.equals("mute")) {
        int seconds = parseSeconds(args);
        BotChatAI chatAI = chatAI();
        if (chatAI != null) chatAI.timedMute(bot.getUuid(), seconds);
        else bot.setChatEnabled(false);
        sender.sendMessage(
            Lang.get(
                seconds > 0 ? "chat-bot-muted-timed" : "chat-bot-muted",
                "name",
                bot.getDisplayName(),
                "seconds",
                String.valueOf(seconds)));
        return true;
      }

      sender.sendMessage(Lang.get("chat-invalid"));
      return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      FakePlayerManager manager = manager();
      if (args.length == 1) {
        List<String> options = new ArrayList<>(Arrays.asList("on", "off", "status", "all"));
        if (manager != null) {
          for (FakePlayer fp : manager.getActivePlayers()) options.add(fp.getName());
        }
        String prefix = args[0].toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(prefix)).toList();
      }
      if (args.length == 2) {
        String firstLower = args[0].toLowerCase();
        String prefix = args[1].toLowerCase();
        if (firstLower.equals("all")) {
          return List.of("on", "off", "status", "say", "mute").stream()
              .filter(s -> s.startsWith(prefix))
              .toList();
        }
        if (manager != null && manager.getByName(args[0]) != null) {
          return List.of("on", "off", "status", "info", "say", "mute").stream()
              .filter(s -> s.startsWith(prefix))
              .toList();
        }
      }
      if (args.length == 3 && args[1].equalsIgnoreCase("mute")) {
        String prefix = args[2].toLowerCase();
        return List.of("30", "60", "120", "300", "600").stream()
            .filter(s -> s.startsWith(prefix))
            .toList();
      }
      return List.of();
    }

    private void setGlobal(CommandSender sender, boolean enabled) {
      getConfig().set("fake-chat.enabled", enabled);
      saveAndReloadChat();
      sender.sendMessage(Lang.get(enabled ? "chat-enabled" : "chat-disabled"));
      Config.debug("fake-chat.enabled set to " + enabled + " by " + sender.getName());
    }

    private void handleAll(CommandSender sender, FakePlayerManager manager, String[] args) {
      if (manager == null) {
        sender.sendMessage(Lang.get("chat-invalid"));
        return;
      }
      if (args.length < 2) {
        sender.sendMessage(Lang.get("chat-all-usage"));
        return;
      }
      String sub = args[1].toLowerCase();
      switch (sub) {
        case "on", "true" -> {
          manager.getActivePlayers().forEach(fp -> fp.setChatEnabled(true));
          sender.sendMessage(Lang.get("chat-all-enabled", "count", String.valueOf(manager.getActivePlayers().size())));
          manager.getActivePlayers().forEach(manager::persistBotSettings);
        }
        case "off", "false" -> {
          manager.getActivePlayers().forEach(fp -> fp.setChatEnabled(false));
          sender.sendMessage(Lang.get("chat-all-disabled", "count", String.valueOf(manager.getActivePlayers().size())));
          manager.getActivePlayers().forEach(manager::persistBotSettings);
        }
        case "status" -> {
          long enabled = manager.getActivePlayers().stream().filter(FakePlayer::isChatEnabled).count();
          long disabled = manager.getActivePlayers().size() - enabled;
          sender.sendMessage(
              Lang.get(
                  "chat-all-status",
                  "enabled",
                  String.valueOf(enabled),
                  "disabled",
                  String.valueOf(disabled)));
        }
        case "say" -> {
          if (args.length < 3) {
            sender.sendMessage(Lang.get("chat-all-say-usage"));
            return;
          }
          String message = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
          BotChatAI chatAI = chatAI();
          if (chatAI != null) {
            manager.getActivePlayers().forEach(fp -> chatAI.forceSendMessageResolved(fp, message));
          }
          sender.sendMessage(
              Lang.get(
                  "chat-all-said",
                  "count",
                  String.valueOf(manager.getActivePlayers().size()),
                  "message",
                  message));
        }
        case "mute" -> {
          int seconds = parseSeconds(args);
          BotChatAI chatAI = chatAI();
          int count = 0;
          for (FakePlayer fp : manager.getActivePlayers()) {
            if (chatAI != null) chatAI.timedMute(fp.getUuid(), seconds);
            else fp.setChatEnabled(false);
            count++;
          }
          sender.sendMessage(Lang.get("chat-all-disabled", "count", String.valueOf(count)));
        }
        default -> sender.sendMessage(Lang.get("chat-all-usage"));
      }
    }

    private int parseSeconds(String[] args) {
      if (args.length < 3) return 0;
      try {
        return Integer.parseInt(args[2]);
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
  }

  private void saveConfig() {
    File dataFolder = getDataFolder();
    if (dataFolder == null) return;
    try {
      getConfig().save(new File(dataFolder, "config.yml"));
    } catch (IOException e) {
      warn("Failed to save config.yml: " + e.getMessage());
    }
  }
}
