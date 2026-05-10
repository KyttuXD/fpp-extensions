package me.bill.fppbots;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppAddonCommand;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.gui.BotSettingGui;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.util.BotAccess;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

public final class FppBotsExtension implements FppExtension, Listener {
  private static final int SIZE = 54;
  private static final int CONTENT_SIZE = 45;
  private static final int PREV_SLOT = 45;
  private static final int INFO_SLOT = 49;
  private static final int NEXT_SLOT = 53;

  private FppApi api;
  private FakePlayerPlugin core;
  private FppAddonCommand command;

  @Override
  public @NotNull String getName() {
    return "FPP-Bots";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Adds /fpp bots as a FakePlayerPlugin extension.";
  }

  @Override
  public @NotNull List<String> getAuthors() {
    return List.of("Bill");
  }

  @Override
  public void onEnable(@NotNull FppApi api) {
    this.api = api;
    if (!(api.getPlugin() instanceof FakePlayerPlugin fpp)) {
      api.getPlugin().getLogger().warning("[FPP-Bots] Unsupported host plugin instance.");
      return;
    }
    core = fpp;
    saveDefaultConfig();
    command = new BotsAddonCommand();
    api.registerCommand(command);
    Bukkit.getPluginManager().registerEvents(this, api.getPlugin());
    api.getPlugin().getLogger().info("[FPP-Bots] Enabled.");
  }

  @Override
  public void onDisable() {
    HandlerList.unregisterAll(this);
    if (api != null && command != null) api.unregisterCommand(command);
    command = null;
    core = null;
    api = null;
  }

  private boolean enabled() {
    return getConfig().getBoolean("enabled", true);
  }

  private String permission() {
    return getConfig().getString("permissions.command", "fpp.settings");
  }

  private FakePlayerManager manager() {
    return core != null ? core.getFakePlayerManager() : null;
  }

  private BotSettingGui botSettingGui() {
    return core != null ? core.getBotSettingGui() : null;
  }

  private final class BotsAddonCommand implements FppAddonCommand {
    @Override
    public @NotNull String getName() {
      return "bots";
    }

    @Override
    public @NotNull List<String> getAliases() {
      return List.of("mybots", "botmenu");
    }

    @Override
    public @NotNull String getUsage() {
      return "[bot]";
    }

    @Override
    public @NotNull String getDescription() {
      return "Open your manageable bot selection menu";
    }

    @Override
    public @NotNull String getPermission() {
      return permission();
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled()) {
        sender.sendMessage("Bots extension is disabled.");
        return true;
      }
      if (!(sender instanceof Player player)) {
        sender.sendMessage(Lang.get("player-only"));
        return true;
      }
      FakePlayerManager manager = manager();
      BotSettingGui gui = botSettingGui();
      if (manager == null || gui == null) {
        sender.sendMessage(Lang.get("settings-bot-not-found", "name", args.length > 0 ? args[0] : ""));
        return true;
      }
      if (args.length > 0) {
        FakePlayer fp = manager.getByName(args[0]);
        if (fp == null) {
          sender.sendMessage(Lang.get("chat-bot-not-found", "name", args[0]));
          return true;
        }
        if (!BotAccess.canAdminister(player, fp)) {
          sender.sendMessage(Lang.get("no-permission"));
          return true;
        }
        gui.open(player, fp);
        return true;
      }
      open(player, 0);
      return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      if (!enabled() || !(sender instanceof Player player) || args.length != 1) return List.of();
      FakePlayerManager manager = manager();
      if (manager == null) return List.of();
      String prefix = args[0].toLowerCase();
      return manager.getActivePlayers().stream()
          .filter(fp -> BotAccess.canAdminister(player, fp))
          .map(FakePlayer::getName)
          .filter(name -> name.toLowerCase().startsWith(prefix))
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .toList();
    }
  }

  private void open(Player player, int page) {
    FakePlayerManager manager = manager();
    if (manager == null) return;
    List<FakePlayer> bots = new ArrayList<>(manager.getActivePlayers());
    bots.removeIf(fp -> !BotAccess.canAdminister(player, fp));
    bots.sort(Comparator.comparing(FakePlayer::getName, String.CASE_INSENSITIVE_ORDER));
    int maxPage = Math.max(0, (bots.size() - 1) / CONTENT_SIZE);
    page = Math.max(0, Math.min(page, maxPage));

    Inventory inv =
        Bukkit.createInventory(
            new Holder(player.getUniqueId(), page),
            SIZE,
            Component.text("FPP Bot Control " + (page + 1) + "/" + (maxPage + 1))
                .color(NamedTextColor.AQUA));

    int start = page * CONTENT_SIZE;
    int end = Math.min(start + CONTENT_SIZE, bots.size());
    for (int i = start; i < end; i++) {
      inv.setItem(i - start, botItem(player, bots.get(i)));
    }
    if (bots.isEmpty()) {
      ItemStack item = new ItemStack(Material.BARRIER);
      ItemMeta meta = item.getItemMeta();
      meta.displayName(Component.text("No manageable bots").color(NamedTextColor.RED));
      meta.lore(List.of(Component.text("Spawn a bot or ask an owner to share access.").color(NamedTextColor.GRAY)));
      item.setItemMeta(meta);
      inv.setItem(22, item);
    }
    inv.setItem(INFO_SLOT, pageInfoItem(page, maxPage, bots.size()));
    if (page > 0) inv.setItem(PREV_SLOT, navItem(Material.ARROW, "Previous page"));
    if (page < maxPage) inv.setItem(NEXT_SLOT, navItem(Material.ARROW, "Next page"));
    player.openInventory(inv);
  }

  private ItemStack pageInfoItem(int page, int maxPage, int count) {
    ItemStack item = new ItemStack(Material.BOOK);
    ItemMeta meta = item.getItemMeta();
    meta.displayName(
        Component.text("Page " + (page + 1) + "/" + (maxPage + 1))
            .color(NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
    meta.lore(List.of(Component.text(count + " manageable bot(s)").color(NamedTextColor.GRAY)));
    item.setItemMeta(meta);
    return item;
  }

  private ItemStack navItem(Material material, String label) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    meta.displayName(Component.text(label).color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
    item.setItemMeta(meta);
    return item;
  }

  private ItemStack botItem(Player viewer, FakePlayer fp) {
    ItemStack item = new ItemStack(Material.PLAYER_HEAD);
    ItemMeta meta = item.getItemMeta();
    meta.displayName(Component.text(fp.getName()).color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
    String access = BotAccess.isAdmin(viewer) ? "admin" : BotAccess.isOwner(viewer, fp) ? "owner" : "shared";
    meta.lore(
        List.of(
            Component.text("Access: " + access).color(NamedTextColor.GRAY),
            Component.text("Owner: " + fp.getSpawnedBy()).color(NamedTextColor.GRAY),
            Component.text("Click to open settings").color(NamedTextColor.YELLOW)));
    item.setItemMeta(meta);
    return item;
  }

  @EventHandler
  public void onClick(InventoryClickEvent event) {
    if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player player)) return;
    if (!player.getUniqueId().equals(holder.viewerUuid())) return;
    if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getInventory())) return;
    int slot = event.getRawSlot();
    if (slot == PREV_SLOT) {
      open(player, holder.page() - 1);
      return;
    }
    if (slot == NEXT_SLOT) {
      open(player, holder.page() + 1);
      return;
    }
    if (slot >= CONTENT_SIZE) return;
    ItemStack clicked = event.getCurrentItem();
    if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;
    Component name = clicked.getItemMeta().displayName();
    if (name == null) return;
    FakePlayerManager manager = manager();
    BotSettingGui gui = botSettingGui();
    if (manager == null || gui == null) return;
    String plain = PlainTextComponentSerializer.plainText().serialize(name);
    FakePlayer fp = manager.getByName(plain);
    if (fp == null || !BotAccess.canAdminister(player, fp)) return;
    gui.open(player, fp);
  }

  private record Holder(UUID viewerUuid, int page) implements InventoryHolder {
    @Override
    public Inventory getInventory() {
      return null;
    }
  }
}
