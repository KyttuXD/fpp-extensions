package me.bill.fppgroups;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.bill.fakePlayerPlugin.api.FppAddonCommand;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppBot;
import me.bill.fakePlayerPlugin.api.FppCommandExtension;
import me.bill.fakePlayerPlugin.api.FppExtension;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public final class FppGroupsExtension implements FppExtension {
  private static final List<String> TASK_COMMANDS =
      List.of("move", "mine", "find", "place", "use", "attack", "follow", "sleep", "stop", "storage");

  private FppApi api;
  private Plugin plugin;
  private GroupStore store;
  private FppAddonCommand command;
  private final List<FppCommandExtension> taskExtensions = new ArrayList<>();

  @Override
  public @NotNull String getName() {
    return "FPP-Groups";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Personal bot groups and grouped task dispatch for FPP bots.";
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
      plugin.getLogger().warning("[FPP-Groups] No extension data folder; disabling.");
      return;
    }

    store = new GroupStore(api, dataFolder);
    store.load(getConfig().getBoolean("migration.import-core-groups", true));

    command = new GroupsCommand();
    api.registerCommand(command);
    for (String task : TASK_COMMANDS) {
      FppCommandExtension extension = new GroupTaskExtension(task);
      taskExtensions.add(extension);
      api.registerCommandExtension(extension);
    }
    plugin.getLogger().info("[FPP-Groups] Enabled.");
  }

  @Override
  public void onDisable() {
    if (api != null && command != null) api.unregisterCommand(command);
    if (api != null) {
      for (FppCommandExtension extension : taskExtensions) api.unregisterCommandExtension(extension);
    }
    taskExtensions.clear();
    command = null;
    store = null;
    plugin = null;
    api = null;
  }

  private boolean enabled() {
    return getConfig().getBoolean("enabled", true);
  }

  private String permission() {
    return getConfig().getString("permissions.command", "fpp.settings");
  }

  private Component msg(String text, NamedTextColor color) {
    return Component.text(text, color);
  }

  private final class GroupsCommand implements FppAddonCommand {
    @Override
    public @NotNull String getName() {
      return "groups";
    }

    @Override
    public @NotNull List<String> getAliases() {
      return List.of("group", "botgroups");
    }

    @Override
    public @NotNull String getUsage() {
      return "[gui|list|create|delete|add|remove]";
    }

    @Override
    public @NotNull String getDescription() {
      return "Manage personal bot groups.";
    }

    @Override
    public @NotNull String getPermission() {
      return permission();
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled()) {
        sender.sendMessage(msg("Bot groups addon is disabled.", NamedTextColor.RED));
        return true;
      }
      if (!(sender instanceof Player player)) {
        sender.sendMessage(msg("Only players can use bot groups.", NamedTextColor.RED));
        return true;
      }
      if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
        openGui(player);
        return true;
      }
      String action = args[0].toLowerCase(Locale.ROOT);
      if (action.equals("list")) {
        player.sendMessage(msg("Bot groups: " + String.join(", ", store.getGroups(player)), NamedTextColor.AQUA));
        return true;
      }
      if (args.length < 2) {
        usage(player);
        return true;
      }
      String group = args[1];
      switch (action) {
        case "create" ->
            player.sendMessage(msg(store.create(player, group) ? "Group created." : "Could not create group.", NamedTextColor.YELLOW));
        case "delete" ->
            player.sendMessage(msg(store.delete(player, group) ? "Group deleted." : "Could not delete group.", NamedTextColor.YELLOW));
        case "add" -> {
          if (args.length < 3) {
            player.sendMessage(msg("Usage: /fpp groups add <group> <bot>", NamedTextColor.RED));
            return true;
          }
          FppBot bot = api.getBot(args[2]).orElse(null);
          player.sendMessage(msg(store.add(player, group, bot) ? "Bot added." : "Could not add bot.", NamedTextColor.YELLOW));
        }
        case "remove" -> {
          if (args.length < 3) {
            player.sendMessage(msg("Usage: /fpp groups remove <group> <bot>", NamedTextColor.RED));
            return true;
          }
          player.sendMessage(msg(store.remove(player, group, args[2]) ? "Bot removed." : "Could not remove bot.", NamedTextColor.YELLOW));
        }
        default -> usage(player);
      }
      return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!(sender instanceof Player player)) return List.of();
      if (args.length == 1) {
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("gui", "list", "create", "delete", "add", "remove").stream()
            .filter(s -> s.startsWith(prefix))
            .toList();
      }
      if (args.length == 2 && !args[0].equalsIgnoreCase("create")) {
        String prefix = args[1].toLowerCase(Locale.ROOT);
        return store.getGroups(player).stream().filter(s -> s.startsWith(prefix)).toList();
      }
      if (args.length == 3 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
        String prefix = args[2].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (FppBot bot : api.getBots()) {
          if (bot.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(bot.getName());
        }
        return out;
      }
      return List.of();
    }

    private void usage(Player player) {
      player.sendMessage(msg("Usage: /fpp groups create|delete <group>, add|remove <group> <bot>", NamedTextColor.RED));
    }

    private void openGui(Player player) {
      Inventory inv = Bukkit.createInventory(null, 54, Component.text("FPP Bot Groups", NamedTextColor.AQUA));
      int slot = 0;
      for (String group : store.getGroups(player)) {
        ItemStack item = new ItemStack(group.equals(GroupStore.DEFAULT_GROUP) ? Material.NETHER_STAR : Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        List<FppBot> bots = store.resolve(player, group);
        meta.displayName(Component.text(group, NamedTextColor.AQUA));
        meta.lore(
            List.of(
                Component.text(bots.size() + " bot(s)", NamedTextColor.GRAY),
                Component.text("Use /fpp groups add/remove to edit", NamedTextColor.YELLOW)));
        item.setItemMeta(meta);
        inv.setItem(slot++, item);
        if (slot >= 54) break;
      }
      player.openInventory(inv);
    }
  }

  private final class GroupTaskExtension implements FppCommandExtension {
    private final String commandName;

    private GroupTaskExtension(String commandName) {
      this.commandName = commandName;
    }

    @Override
    public @NotNull String getCommandName() {
      return commandName;
    }

    @Override
    public @NotNull String getUsage() {
      return "--group <group> ...";
    }

    @Override
    public @NotNull String getDescription() {
      return "Adds --group targeting for bot groups.";
    }

    @Override
    public @NotNull String getPermission() {
      return permission();
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled() || args.length == 0 || !args[0].equalsIgnoreCase("--group")) return false;
      if (!(sender instanceof Player player)) {
        sender.sendMessage(msg("Only players can use --group.", NamedTextColor.RED));
        return true;
      }
      if (args.length < 2) {
        sender.sendMessage(msg("Usage: /fpp " + commandName + " --group <group> ...", NamedTextColor.RED));
        return true;
      }

      List<FppBot> bots = store.resolve(player, args[1]);
      if (bots.isEmpty()) {
        sender.sendMessage(msg("No bots found in group: " + args[1], NamedTextColor.RED));
        return true;
      }

      String[] rest = java.util.Arrays.copyOfRange(args, 2, args.length);
      int started = 0;
      for (FppBot bot : bots) {
        Player entity = bot.getEntity();
        if (entity == null || !entity.isOnline()) continue;
        Bukkit.dispatchCommand(sender, buildCommand(bot.getName(), rest));
        started++;
      }
      sender.sendMessage(msg("Group task dispatched to " + started + " bot(s).", NamedTextColor.YELLOW));
      return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled() || !(sender instanceof Player player)) return List.of();
      if (args.length == 1) {
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return "--group".startsWith(prefix) ? List.of("--group") : List.of();
      }
      if (args.length == 2 && args[0].equalsIgnoreCase("--group")) {
        String prefix = args[1].toLowerCase(Locale.ROOT);
        return store.getGroups(player).stream().filter(s -> s.startsWith(prefix)).toList();
      }
      return List.of();
    }

    private String buildCommand(String botName, String[] rest) {
      StringBuilder out = new StringBuilder("fpp ").append(commandName).append(' ').append(botName);
      for (String arg : rest) out.append(' ').append(arg);
      return out.toString();
    }
  }
}
