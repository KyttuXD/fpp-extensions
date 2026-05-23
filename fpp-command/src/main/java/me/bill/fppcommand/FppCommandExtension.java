package me.bill.fppcommand;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppAddonCommand;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.util.FppLogger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class FppCommandExtension implements FppExtension {
  private FppApi api;
  private FakePlayerPlugin core;
  private FppAddonCommand command;

  @Override
  public @NotNull String getName() {
    return "FPP-Command";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Adds /fpp cmd as a FakePlayerPlugin extension.";
  }

  @Override
  public @NotNull List<String> getAuthors() {
    return List.of("Bill");
  }

  @Override
  public void onEnable(@NotNull FppApi api) {
    this.api = api;
    if (!(api.getPlugin() instanceof FakePlayerPlugin fpp)) {
      api.getPlugin().getLogger().warning("[FPP-Command] Unsupported host plugin instance.");
      return;
    }
    core = fpp;
    saveDefaultConfig();
    command = new CmdAddonCommand();
    api.registerCommand(command);
    api.getPlugin().getLogger().info("[FPP-Command] Enabled.");
  }

  @Override
  public void onDisable() {
    if (api != null && command != null) api.unregisterCommand(command);
    command = null;
    core = null;
    api = null;
  }

  private boolean enabled() {
    return getConfig().getBoolean("enabled", true);
  }

  private String commandPermission() {
    return getConfig().getString("permissions.command", "fpp.cmd.admin");
  }

  private String legacyPermission() {
    return getConfig().getString("permissions.legacy", "fpp.cmd");
  }

  private boolean hasCommandPermission(CommandSender sender) {
    String primary = commandPermission();
    String legacy = legacyPermission();
    return (primary == null || primary.isBlank() || sender.hasPermission(primary))
        || (legacy != null && !legacy.isBlank() && sender.hasPermission(legacy));
  }

  private FakePlayerManager manager() {
    return core != null ? core.getFakePlayerManager() : null;
  }

  private boolean canControl(CommandSender sender, FakePlayer bot) {
    if (!(sender instanceof Player player)) return true;
    return api != null
        && api.getBot(bot.getUuid()).map(fppBot -> api.canControlBot(player, fppBot)).orElse(false);
  }

  private final class CmdAddonCommand implements FppAddonCommand {
    @Override
    public @NotNull String getName() {
      return "cmd";
    }

    @Override
    public @NotNull List<String> getAliases() {
      return List.of("command");
    }

    @Override
    public @NotNull String getUsage() {
      return "<bot> <command...>  |  <bot> --add <command...>  |  <bot> --clear  |  <bot> --show";
    }

    @Override
    public @NotNull String getDescription() {
      return "Execute a command as a bot, or bind one to right-click.";
    }

    @Override
    public @NotNull String getPermission() {
      return commandPermission();
    }

    @Override
    public @NotNull Material getIcon() {
      return Material.COMMAND_BLOCK;
    }

    @Override
    public boolean canUse(@NotNull CommandSender sender) {
      return hasCommandPermission(sender);
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled()) {
        sender.sendMessage("§cCommand extension is disabled.");
        return true;
      }
      if (!hasCommandPermission(sender)) {
        sender.sendMessage(Lang.get("no-permission"));
        return true;
      }
      if (args.length < 2) {
        sender.sendMessage(Lang.get("cmd-usage"));
        return true;
      }

      FakePlayerManager manager = manager();
      FakePlayer bot = manager != null ? manager.getByName(args[0]) : null;
      if (bot == null) {
        sender.sendMessage(Lang.get("cmd-not-found", "name", args[0]));
        return true;
      }
      if (!canControl(sender, bot)) {
        sender.sendMessage(Lang.get("no-permission"));
        return true;
      }

      Player playerEntity = bot.getPlayer();
      if (playerEntity == null || !playerEntity.isOnline()) {
        sender.sendMessage(Lang.get("cmd-bot-offline", "name", bot.getDisplayName()));
        return true;
      }

      String sub = args[1].toLowerCase(Locale.ROOT);
      if (sub.equals("--add")) {
        return addRightClickCommand(sender, manager, bot, args);
      }
      if (sub.equals("--clear")) {
        return clearRightClickCommand(sender, manager, bot);
      }
      if (sub.equals("--show")) {
        return showRightClickCommand(sender, bot);
      }
      return dispatchAsBot(sender, bot, args);
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled() || !hasCommandPermission(sender)) return List.of();
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
        return List.of("--add", "--clear", "--show").stream()
            .filter(option -> option.startsWith(prefix))
            .toList();
      }
      return List.of();
    }

    private boolean addRightClickCommand(
        CommandSender sender, FakePlayerManager manager, FakePlayer bot, String[] args) {
      if (args.length < 3) {
        sender.sendMessage(Lang.get("cmd-add-usage"));
        return true;
      }
      String command = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
      String previous = bot.getRightClickCommand();
      bot.setRightClickCommand(command);
      String newCommand = "/" + bot.getRightClickCommand();
      manager.persistBotSettings(bot);

      if (previous != null) {
        sender.sendMessage(
            Lang.get(
                "cmd-add-updated",
                "name",
                bot.getDisplayName(),
                "old",
                "/" + previous,
                "cmd",
                newCommand));
      } else {
        sender.sendMessage(Lang.get("cmd-add-set", "name", bot.getDisplayName(), "cmd", newCommand));
      }
      FppLogger.info(
          sender.getName()
              + " set right-click command on "
              + bot.getName()
              + ": "
              + newCommand
              + (previous != null ? " (was: /" + previous + ")" : ""));
      return true;
    }

    private boolean clearRightClickCommand(
        CommandSender sender, FakePlayerManager manager, FakePlayer bot) {
      if (!bot.hasRightClickCommand()) {
        sender.sendMessage(Lang.get("cmd-add-none", "name", bot.getDisplayName()));
        return true;
      }
      bot.setRightClickCommand(null);
      sender.sendMessage(Lang.get("cmd-add-cleared", "name", bot.getDisplayName()));
      manager.persistBotSettings(bot);
      return true;
    }

    private boolean showRightClickCommand(CommandSender sender, FakePlayer bot) {
      if (!bot.hasRightClickCommand()) {
        sender.sendMessage(Lang.get("cmd-add-none", "name", bot.getDisplayName()));
      } else {
        sender.sendMessage(
            Lang.get(
                "cmd-add-show",
                "name",
                bot.getDisplayName(),
                "cmd",
                "/" + bot.getRightClickCommand()));
      }
      return true;
    }

    private boolean dispatchAsBot(CommandSender sender, FakePlayer bot, String[] args) {
      Player player = bot.getPlayer();
      if (player == null || !player.isOnline()) {
        sender.sendMessage(Lang.get("cmd-bot-offline", "name", bot.getDisplayName()));
        return true;
      }

      String command = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
      if (command.startsWith("/")) command = command.substring(1);
      
      if (command.isBlank()) {
        sender.sendMessage("§cNo command specified.");
        return true;
      }

      try {
        boolean success = Bukkit.dispatchCommand(player, command);
        if (success) {
          sender.sendMessage(Lang.get("cmd-executed", "name", bot.getDisplayName(), "cmd", "/" + command));
          FppLogger.info(sender.getName() + " issued server command as " + bot.getName() + ": /" + command);
        } else {
          sender.sendMessage(Lang.get("cmd-failed", "name", bot.getDisplayName(), "cmd", "/" + command));
        }
      } catch (Exception e) {
        sender.sendMessage("§cFailed to execute command: " + e.getMessage());
        core.getLogger().warning("Command execution failed for " + bot.getName() + ": " + e.getMessage());
      }
      return true;
    }
  }
}
