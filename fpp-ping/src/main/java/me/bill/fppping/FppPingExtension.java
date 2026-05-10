package me.bill.fppping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import me.bill.fakePlayerPlugin.api.FppAddonCommand;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppBot;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.config.Config;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public final class FppPingExtension implements FppExtension {
  private FppApi api;
  private Plugin plugin;
  private FppAddonCommand command;

  @Override
  public @NotNull String getName() {
    return "FPP-Ping";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Adds the /fpp ping command as a FakePlayerPlugin extension.";
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
    Config.registerExternalConfig("ping", getConfig());
    command = new PingAddonCommand();
    api.registerCommand(command);
    plugin.getLogger().info("[FPP-Ping] Enabled.");
  }

  @Override
  public void onDisable() {
    if (api != null && command != null) api.unregisterCommand(command);
    Config.unregisterExternalConfig("ping", getConfig());
    command = null;
    plugin = null;
    api = null;
  }

  private boolean enabled() {
    return getConfig().getBoolean("enabled", true);
  }

  private String prefix() {
    return color(getConfig().getString("messages.prefix", "&8[&bFPP Ping&8]&r "));
  }

  private String color(String message) {
    return ChatColor.translateAlternateColorCodes('&', message);
  }

  private String perm(String key, String fallback) {
    return getConfig().getString("permissions." + key, fallback);
  }

  private int randomPing() {
    YamlConfiguration cfg = getConfig();
    int min = Math.max(0, Math.min(9999, cfg.getInt("random.min", 20)));
    int max = Math.max(0, Math.min(9999, cfg.getInt("random.max", 200)));
    if (max < min) {
      int tmp = min;
      min = max;
      max = tmp;
    }
    return ThreadLocalRandom.current().nextInt(min, max + 1);
  }

  private boolean has(CommandSender sender, String permission) {
    return permission == null || permission.isBlank() || sender.hasPermission(permission);
  }

  private boolean require(CommandSender sender, String permission) {
    if (has(sender, permission)) return true;
    sender.sendMessage(prefix() + ChatColor.RED + "You do not have permission.");
    return false;
  }

  private final class PingAddonCommand implements FppAddonCommand {
    @Override
    public @NotNull String getName() {
      return "ping";
    }

    @Override
    public @NotNull String getDescription() {
      return "Show or spoof bot ping values.";
    }

    @Override
    public @NotNull String getUsage() {
      return "[<bot>|--count <n>] [--ping <ms>|--random|--reset]";
    }

    @Override
    public @NotNull String getPermission() {
      return perm("base", "fpp.ping");
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled()) {
        sender.sendMessage(prefix() + ChatColor.RED + "Ping extension is disabled.");
        return true;
      }

      ParsedArgs parsed = parse(sender, args);
      if (parsed == null) return true;

      if (parsed.hasPing && !require(sender, perm("set", "fpp.ping.set"))) return true;
      if (parsed.random && !require(sender, perm("random", "fpp.ping.random"))) return true;
      if (parsed.count != null && !require(sender, perm("bulk", "fpp.ping.bulk"))) return true;

      if (parsed.botName != null && parsed.count != null) {
        sender.sendMessage(prefix() + ChatColor.RED + "Cannot use a bot name and --count together.");
        return true;
      }

      int changes = (parsed.hasPing ? 1 : 0) + (parsed.random ? 1 : 0) + (parsed.reset ? 1 : 0);
      if (changes > 1) {
        sender.sendMessage(prefix() + ChatColor.RED + "Conflicting ping options cannot be used together.");
        return true;
      }

      if (parsed.botName != null) {
        Optional<FppBot> bot = api.getBot(parsed.botName);
        if (bot.isEmpty()) {
          sender.sendMessage(prefix() + ChatColor.RED + "Bot not found: " + ChatColor.YELLOW + parsed.botName);
          return true;
        }
        if (sender instanceof Player player && !api.canControlBot(player, bot.get())) {
          sender.sendMessage(prefix() + ChatColor.RED + "You do not have permission.");
          return true;
        }
        handleSingle(sender, bot.get(), parsed);
        return true;
      }

      List<FppBot> bots = new ArrayList<>(api.getBots());
      if (bots.isEmpty()) {
        sender.sendMessage(prefix() + ChatColor.RED + "No bots are currently active.");
        return true;
      }

      if (parsed.count != null) {
        Collections.shuffle(bots);
        bots = new ArrayList<>(bots.subList(0, Math.min(parsed.count, bots.size())));
      }

      if (changes == 0) {
        for (FppBot bot : bots) show(sender, bot);
        return true;
      }

      handleMany(sender, bots, parsed);
      return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      List<String> out = new ArrayList<>();
      String partial = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);

      if (args.length == 1) {
        addIfStarts(out, "--count", partial);
        addBotNames(out, partial);
      } else if (args.length >= 2) {
        addIfStarts(out, "--ping", partial);
        addIfStarts(out, "--random", partial);
        addIfStarts(out, "--reset", partial);
      }
      return out;
    }

    private ParsedArgs parse(CommandSender sender, String[] args) {
      ParsedArgs parsed = new ParsedArgs();
      for (int i = 0; i < args.length; i++) {
        String arg = args[i].toLowerCase(Locale.ROOT);
        switch (arg) {
          case "--ping" -> {
            if (parsed.hasPing) {
              sender.sendMessage(prefix() + ChatColor.RED + "Duplicate option: --ping");
              return null;
            }
            if (i + 1 >= args.length) {
              sender.sendMessage(prefix() + ChatColor.RED + "Missing value for --ping.");
              return null;
            }
            i++;
            try {
              parsed.ping = Integer.parseInt(args[i]);
              parsed.hasPing = true;
            } catch (NumberFormatException e) {
              sender.sendMessage(prefix() + ChatColor.RED + "Invalid number: " + ChatColor.WHITE + args[i]);
              return null;
            }
            if (parsed.ping < 0 || parsed.ping > 9999) {
              sender.sendMessage(prefix() + ChatColor.RED + "Ping value must be between 0 and 9999.");
              return null;
            }
          }
          case "--random" -> {
            if (parsed.random) {
              sender.sendMessage(prefix() + ChatColor.RED + "Duplicate option: --random");
              return null;
            }
            parsed.random = true;
          }
          case "--reset" -> {
            if (parsed.reset) {
              sender.sendMessage(prefix() + ChatColor.RED + "Duplicate option: --reset");
              return null;
            }
            parsed.reset = true;
          }
          case "--count" -> {
            if (parsed.count != null) {
              sender.sendMessage(prefix() + ChatColor.RED + "Duplicate option: --count");
              return null;
            }
            if (i + 1 >= args.length) {
              sender.sendMessage(prefix() + ChatColor.RED + "Missing value for --count.");
              return null;
            }
            i++;
            try {
              parsed.count = Integer.parseInt(args[i]);
            } catch (NumberFormatException e) {
              sender.sendMessage(prefix() + ChatColor.RED + "Invalid number: " + ChatColor.WHITE + args[i]);
              return null;
            }
            if (parsed.count < 1) {
              sender.sendMessage(prefix() + ChatColor.RED + "--count must be at least 1.");
              return null;
            }
          }
          default -> {
            if (arg.startsWith("--")) {
              sender.sendMessage(prefix() + ChatColor.RED + "Unknown option: " + ChatColor.WHITE + args[i]);
              return null;
            }
            if (parsed.botName != null) {
              sender.sendMessage(prefix() + ChatColor.RED + "Usage: /fpp ping " + getUsage());
              return null;
            }
            parsed.botName = args[i];
          }
        }
      }
      return parsed;
    }

    private void handleSingle(CommandSender sender, FppBot bot, ParsedArgs parsed) {
      if (parsed.hasPing) {
        api.setBotPing(bot, parsed.ping);
        api.persistBotSettings(bot);
        sender.sendMessage(
            prefix()
                + ChatColor.GREEN
                + "Set ping for "
                + ChatColor.YELLOW
                + bot.getDisplayName()
                + ChatColor.GREEN
                + " to "
                + ChatColor.WHITE
                + parsed.ping
                + "ms"
                + ChatColor.GREEN
                + ".");
      } else if (parsed.random) {
        int value = randomPing();
        api.setBotPing(bot, value);
        api.persistBotSettings(bot);
        sender.sendMessage(
            prefix()
                + ChatColor.GREEN
                + "Set ping for "
                + ChatColor.YELLOW
                + bot.getDisplayName()
                + ChatColor.GREEN
                + " to "
                + ChatColor.WHITE
                + value
                + "ms"
                + ChatColor.GREEN
                + ".");
      } else if (parsed.reset) {
        api.resetBotPing(bot);
        api.persistBotSettings(bot);
        sender.sendMessage(
            prefix()
                + ChatColor.GREEN
                + "Reset ping for "
                + ChatColor.YELLOW
                + bot.getDisplayName()
                + ChatColor.GREEN
                + ".");
      } else {
        show(sender, bot);
      }
    }

    private void handleMany(CommandSender sender, List<FppBot> bots, ParsedArgs parsed) {
      if (parsed.hasPing) {
        for (FppBot bot : bots) {
          api.setBotPing(bot, parsed.ping);
          api.persistBotSettings(bot);
        }
        sender.sendMessage(
            prefix()
                + ChatColor.GREEN
                + "Set ping for "
                + ChatColor.YELLOW
                + bots.size()
                + ChatColor.GREEN
                + " bot(s) to "
                + ChatColor.WHITE
                + parsed.ping
                + "ms"
                + ChatColor.GREEN
                + ".");
      } else if (parsed.random) {
        for (FppBot bot : bots) {
          api.setBotPing(bot, randomPing());
          api.persistBotSettings(bot);
        }
        sender.sendMessage(
            prefix()
                + ChatColor.GREEN
                + "Assigned random pings to "
                + ChatColor.YELLOW
                + bots.size()
                + ChatColor.GREEN
                + " bot(s).");
      } else if (parsed.reset) {
        for (FppBot bot : bots) {
          api.resetBotPing(bot);
          api.persistBotSettings(bot);
        }
        sender.sendMessage(
            prefix()
                + ChatColor.GREEN
                + "Reset ping for "
                + ChatColor.YELLOW
                + bots.size()
                + ChatColor.GREEN
                + " bot(s).");
      }
    }

    private void show(CommandSender sender, FppBot bot) {
      if (bot.hasCustomPing()) {
        sender.sendMessage(
            prefix()
                + ChatColor.YELLOW
                + bot.getDisplayName()
                + ChatColor.GRAY
                + " is spoofing "
                + ChatColor.WHITE
                + bot.getPing()
                + "ms"
                + ChatColor.GRAY
                + ".");
      } else {
        sender.sendMessage(
            prefix()
                + ChatColor.YELLOW
                + bot.getDisplayName()
                + ChatColor.GRAY
                + " is currently showing "
                + ChatColor.WHITE
                + bot.getPing()
                + "ms"
                + ChatColor.DARK_GRAY
                + " (default)");
      }
    }

    private void addBotNames(List<String> out, String partial) {
      for (FppBot bot : api.getBots()) {
        if (bot.getName().toLowerCase(Locale.ROOT).startsWith(partial)) out.add(bot.getName());
      }
    }

    private void addIfStarts(List<String> out, String value, String partial) {
      if (value.startsWith(partial)) out.add(value);
    }
  }

  private static final class ParsedArgs {
    String botName;
    Integer count;
    boolean hasPing;
    int ping;
    boolean random;
    boolean reset;
  }
}
