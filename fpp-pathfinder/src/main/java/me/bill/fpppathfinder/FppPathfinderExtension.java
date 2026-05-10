package me.bill.fpppathfinder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppBot;
import me.bill.fakePlayerPlugin.api.FppBotSettingsTab;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.api.FppSettingsItem;
import me.bill.fakePlayerPlugin.api.FppSettingsTab;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class FppPathfinderExtension implements FppExtension {
  private FppApi api;
  private FakePlayerPlugin core;
  private FppPathfindingService service;
  private FppSettingsTab settingsTab;
  private FppBotSettingsTab botSettingsTab;

  @Override
  public @NotNull String getName() {
    return "FPP-Pathfinder";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Provides FakePlayerPlugin pathfinding services.";
  }

  @Override
  public @NotNull List<String> getAuthors() {
    return List.of("Bill");
  }

  @Override
  public int getPriority() {
    return 20;
  }

  @Override
  public void onEnable(@NotNull FppApi api) {
    this.api = api;
    if (!(api.getPlugin() instanceof FakePlayerPlugin fpp)) {
      api.getPlugin().getLogger().warning("[FPP-Pathfinder] Unsupported host plugin instance.");
      return;
    }
    core = fpp;
    saveDefaultConfig();
    Config.registerExternalConfig("pathfinding", getConfig());
    settingsTab = new PathfindingSettingsTab();
    botSettingsTab = new BotPathfindingSettingsTab();
    api.registerSettingsTab(settingsTab);
    api.registerBotSettingsTab(botSettingsTab);
    if (!getConfig().getBoolean("enabled", true)) {
      api.getPlugin().getLogger().info("[FPP-Pathfinder] Disabled by config.");
      return;
    }
    service = new FppPathfindingService(core, core.getFakePlayerManager());
    core.getPathfindingService().setController(service);
    api.getPlugin()
        .getLogger()
        .info(
            "[FPP-Pathfinder] Enabled. pathfinding="
                + (getConfig().getBoolean("enabled", true) ? "on" : "off")
                + ", parkour="
                + Config.pathfindingParkour()
                + ", break-blocks="
                + Config.pathfindingBreakBlocks()
                + ", place-blocks="
                + Config.pathfindingPlaceBlocks());
  }

  @Override
  public void onDisable() {
    if (api != null) {
      if (settingsTab != null) api.unregisterSettingsTab(settingsTab);
      if (botSettingsTab != null) api.unregisterBotSettingsTab(botSettingsTab);
    }
    if (core != null && core.getPathfindingService() != null) {
      core.getPathfindingService().setController(null);
    }
    Config.unregisterExternalConfig("pathfinding", getConfig());
    service = null;
    settingsTab = null;
    botSettingsTab = null;
    core = null;
    api = null;
  }

  private final class PathfindingSettingsTab implements FppSettingsTab {
    @Override public @NotNull String getId() { return "fpp-pathfinder"; }
    @Override public @NotNull String getLabel() { return "🧭 ᴘᴀᴛʜ"; }
    @Override public @NotNull Material getActiveMaterial() { return Material.COMPASS; }
    @Override public @NotNull Material getInactiveMaterial() { return Material.MAP; }
    @Override public @NotNull Material getSeparatorGlass() { return Material.CYAN_STAINED_GLASS_PANE; }

    @Override
    public @NotNull List<FppSettingsItem> getItems(@NotNull Player viewer) {
      List<FppSettingsItem> out = new ArrayList<>();
      out.add(toggle("pathfinding.parkour", "ᴘᴀʀᴋᴏᴜʀ", "ʙᴏᴛꜱ ꜱᴘʀɪɴᴛ-ᴊᴜᴍᴘ ᴀᴄʀᴏꜱꜱ 1-2 ʙʟᴏᴄᴋ\nɢᴀᴘꜱ ᴅᴜʀɪɴɢ ɢʟᴏʙᴀʟ ɴᴀᴠɪɢᴀᴛɪᴏɴ.", Material.LEATHER_BOOTS));
      out.add(toggle("pathfinding.break-blocks", "ʙʀᴇᴀᴋ ʙʟᴏᴄᴋꜱ", "ʙᴏᴛꜱ ʙʀᴇᴀᴋ ꜱᴏʟɪᴅ ʙʟᴏᴄᴋꜱ ᴛʜᴀᴛ\nʙʟᴏᴄᴋ ᴛʜᴇ ɢʟᴏʙᴀʟ ɴᴀᴠɪɢᴀᴛɪᴏɴ ᴘᴀᴛʜ.", Material.IRON_PICKAXE));
      out.add(toggle("pathfinding.place-blocks", "ᴘʟᴀᴄᴇ ʙʟᴏᴄᴋꜱ", "ʙᴏᴛꜱ ᴘʟᴀᴄᴇ ʙʀɪᴅɢᴇ ʙʟᴏᴄᴋꜱ ᴛᴏ\nᴄʀᴏꜱꜱ 1-ʙʟᴏᴄᴋ ɢᴀᴘꜱ.", Material.DIRT));
      out.add(cycleDouble("pathfinding.arrival-distance", "ᴀʀʀɪᴠᴀʟ ᴅɪꜱᴛᴀɴᴄᴇ", "ʜᴏʀɪᴢᴏɴᴛᴀʟ ʀᴀᴅɪᴜꜱ ᴛʜᴀᴛ ᴄᴏᴜɴᴛꜱ ᴀꜱ\nᴀʀʀɪᴠᴇᴅ ꜰᴏʀ ꜰɪxᴇᴅ ɴᴀᴠɪɢᴀᴛɪᴏɴ.", Material.TARGET, new double[] {0.8, 1.0, 1.2, 1.5, 2.0}));
      out.add(cycleDouble("pathfinding.waypoint-arrival-distance", "ᴡᴀʏᴘᴏɪɴᴛ ꜱɴᴀᴘ", "ʜᴏᴡ ᴄʟᴏꜱᴇ ᴀ ʙᴏᴛ ᴍᴜꜱᴛ ɢᴇᴛ\nᴛᴏ ᴇᴀᴄʜ ᴘᴀᴛʜ ɴᴏᴅᴇ.", Material.STRING, new double[] {0.45, 0.65, 0.85, 1.0, 1.25}));
      out.add(cycleDouble("pathfinding.sprint-distance", "ꜱᴘʀɪɴᴛ ᴅɪꜱᴛᴀɴᴄᴇ", "ʙᴏᴛꜱ ꜱᴛᴀʀᴛ ꜱᴘʀɪɴᴛɪɴɢ ᴡʜᴇɴ\nꜰᴀʀᴛʜᴇʀ ᴀᴡᴀʏ ᴛʜᴀɴ ᴛʜɪꜱ.", Material.SUGAR, new double[] {0.0, 3.0, 6.0, 8.0, 12.0, 16.0}));
      out.add(cycleInt("pathfinding.recalc-interval", "ʀᴇᴄᴀʟᴄ ɪɴᴛᴇʀᴠᴀʟ", "ᴛɪᴄᴋꜱ ʙᴇᴛᴡᴇᴇɴ ᴀᴜᴛᴏᴍᴀᴛɪᴄ\nᴘᴀᴛʜ ʀᴇᴄᴀʟᴄᴜʟᴀᴛɪᴏɴ.", Material.REPEATER, new int[] {10, 20, 40, 60, 100, 200}));
      out.add(cycleInt("pathfinding.stuck-ticks", "ꜱᴛᴜᴄᴋ ᴛɪᴄᴋꜱ", "ʟᴏᴡ-ᴍᴏᴠᴇᴍᴇɴᴛ ᴛɪᴄᴋꜱ ʙᴇꜰᴏʀᴇ\nᴀ ʙᴏᴛ ɪꜱ ᴛʀᴇᴀᴛᴇᴅ ᴀꜱ ꜱᴛᴜᴄᴋ.", Material.COBWEB, new int[] {4, 6, 8, 10, 15, 20}));
      out.add(cycleInt("pathfinding.max-fall", "ᴍᴀx ꜰᴀʟʟ", "ᴍᴀxɪᴍᴜᴍ ʙʟᴏᴄᴋꜱ ᴀ ʙᴏᴛ\nᴡɪʟʟ ꜰᴀʟʟ.", Material.FEATHER, new int[] {1, 2, 3, 4, 6, 8, 12, 16}));
      out.add(cycleInt("pathfinding.max-range", "ᴍᴀx ʀᴀɴɢᴇ", "ᴍᴀx ꜱᴛʀᴀɪɢʜᴛ-ʟɪɴᴇ ꜱᴇᴀʀᴄʜ\nʀᴀɴɢᴇ ɪɴ ʙʟᴏᴄᴋꜱ.", Material.SPYGLASS, new int[] {16, 32, 48, 64, 96, 128}));
      out.add(cycleInt("pathfinding.max-nodes", "ᴍᴀx ɴᴏᴅᴇꜱ", "ɴᴏᴅᴇ ᴄᴀᴘ ꜰᴏʀ ꜱᴛᴀɴᴅᴀʀᴅ\nꜱᴇᴀʀᴄʜᴇꜱ.", Material.REDSTONE, new int[] {500, 1000, 2000, 4000, 8000}));
      out.add(cycleInt("pathfinding.max-nodes-extended", "ᴍᴀx ɴᴏᴅᴇꜱ +", "ɴᴏᴅᴇ ᴄᴀᴘ ᴡʜᴇɴ ᴘᴀʀᴋᴏᴜʀ,\nʙʀᴇᴀᴋ, ᴏʀ ᴘʟᴀᴄᴇ ɪꜱ ᴇɴᴀʙʟᴇᴅ.", Material.GLOWSTONE_DUST, new int[] {2000, 4000, 6000, 8000, 16000}));
      return out;
    }
  }

  private final class BotPathfindingSettingsTab implements FppBotSettingsTab {
    @Override public @NotNull String getId() { return "fpp-pathfinder-bot"; }
    @Override public @NotNull String getLabel() { return "🧭 ᴘᴀᴛʜ"; }
    @Override public @NotNull Material getActiveMaterial() { return Material.COMPASS; }
    @Override public @NotNull Material getInactiveMaterial() { return Material.MAP; }
    @Override public @NotNull Material getSeparatorGlass() { return Material.CYAN_STAINED_GLASS_PANE; }

    @Override
    public @NotNull List<FppSettingsItem> getItems(@NotNull Player viewer, @NotNull FppBot bot) {
      List<FppSettingsItem> out = new ArrayList<>();
      out.add(new Item("follow_player", "ꜰᴏʟʟᴏᴡ ᴘʟᴀʏᴇʀ", "ʙᴏᴛ ᴄᴏɴᴛɪɴᴜᴏᴜꜱʟʏ ꜰᴏʟʟᴏᴡꜱ ᴛʜᴇ\nᴘʟᴀʏᴇʀ ᴡʜᴏ ᴏᴘᴇɴᴇᴅ ᴛʜɪꜱ ɢᴜɪ.", Material.LEAD, () -> isFollowing(bot) ? "✔ ꜰᴏʟʟᴏᴡɪɴɢ" : "✘ ɪᴅʟᴇ", () -> toggleFollow(viewer, bot)));
      out.add(botToggle(bot, "nav_parkour", "ᴘᴀʀᴋᴏᴜʀ", "ʙᴏᴛ ꜱᴘʀɪɴᴛ-ᴊᴜᴍᴘꜱ ᴀᴄʀᴏꜱꜱ 1-2\nʙʟᴏᴄᴋ ɢᴀᴘꜱ.", Material.SLIME_BALL));
      out.add(botToggle(bot, "nav_break_blocks", "ʙʀᴇᴀᴋ ʙʟᴏᴄᴋꜱ", "ʙᴏᴛ ʙʀᴇᴀᴋꜱ ᴏʙꜱᴛʀᴜᴄᴛɪɴɢ ʙʟᴏᴄᴋꜱ\nᴅᴜʀɪɴɢ ɴᴀᴠɪɢᴀᴛɪᴏɴ.", Material.DIAMOND_PICKAXE));
      out.add(botToggle(bot, "nav_place_blocks", "ᴘʟᴀᴄᴇ ʙʟᴏᴄᴋꜱ", "ʙᴏᴛ ᴘʟᴀᴄᴇꜱ ʙʟᴏᴄᴋꜱ ᴛᴏ ʙʀɪᴅɢᴇ\nɢᴀᴘꜱ ᴅᴜʀɪɴɢ ɴᴀᴠɪɢᴀᴛɪᴏɴ.", Material.GRASS_BLOCK));
      return out;
    }
  }

  private FppSettingsItem toggle(String path, String label, String description, Material icon) {
    return new Item(path, label, description, icon,
        () -> getConfig().getBoolean(path, false) ? "✔ ᴏɴ" : "✘ ᴏꜰꜰ",
        () -> {
          getConfig().set(path, !getConfig().getBoolean(path, false));
          saveConfig();
        });
  }

  private FppSettingsItem cycleInt(String path, String label, String description, Material icon, int[] values) {
    return new Item(path, label, description, icon,
        () -> String.valueOf(getConfig().getInt(path, values[0])),
        () -> {
          int current = getConfig().getInt(path, values[0]);
          int next = values[0];
          for (int i = 0; i < values.length; i++) if (values[i] == current) next = values[(i + 1) % values.length];
          getConfig().set(path, next);
          saveConfig();
        });
  }

  private FppSettingsItem cycleDouble(String path, String label, String description, Material icon, double[] values) {
    return new Item(path, label, description, icon,
        () -> String.valueOf(getConfig().getDouble(path, values[0])),
        () -> {
          double current = getConfig().getDouble(path, values[0]);
          double next = values[0];
          for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < 0.0001) next = values[(i + 1) % values.length];
          getConfig().set(path, next);
          saveConfig();
        });
  }

  private FppSettingsItem botToggle(FppBot bot, String id, String label, String description, Material icon) {
    return new Item(id, label, description, icon,
        () -> botBool(bot, id) ? "✔ ᴇɴᴀʙʟᴇᴅ" : "✘ ᴅɪꜱᴀʙʟᴇᴅ",
        () -> {
          boolean next = !botBool(bot, id);
          switch (id) {
            case "nav_parkour" -> bot.setNavParkour(next);
            case "nav_break_blocks" -> bot.setNavBreakBlocks(next);
            case "nav_place_blocks" -> bot.setNavPlaceBlocks(next);
            default -> {}
          }
          api.persistBotSettings(bot);
        });
  }

  private boolean botBool(FppBot bot, String id) {
    return switch (id) {
      case "nav_parkour" -> bot.isNavParkour();
      case "nav_break_blocks" -> bot.isNavBreakBlocks();
      case "nav_place_blocks" -> bot.isNavPlaceBlocks();
      default -> false;
    };
  }

  private boolean isFollowing(FppBot bot) {
    return core != null && core.getFollowCommand() != null && core.getFollowCommand().isFollowing(bot.getUuid());
  }

  private void toggleFollow(Player viewer, FppBot bot) {
    if (core == null || core.getFollowCommand() == null) return;
    if (isFollowing(bot)) {
      core.getFollowCommand().stopFollowing(bot.getUuid());
      return;
    }
    FakePlayer fp = core.getFakePlayerManager().getByUuid(bot.getUuid());
    if (fp != null && fp.getPlayer() != null && fp.getPlayer().getWorld().equals(viewer.getWorld())) {
      core.getFollowCommand().startFollowingFromSettings(fp, viewer);
    } else {
      viewer.sendActionBar(Component.text("Bot must be online in your world.", NamedTextColor.RED));
    }
  }

  private record Item(
      @NotNull String getId,
      @NotNull String getLabel,
      @NotNull String getDescription,
      @NotNull Material getIcon,
      java.util.function.Supplier<String> valueSupplier,
      Runnable clickAction) implements FppSettingsItem {
    @Override public String getValue() { return valueSupplier.get(); }
    @Override public void onClick(@NotNull Player viewer) { clickAction.run(); }
  }

  private void saveConfig() {
    File dataFolder = getDataFolder();
    if (dataFolder == null) return;
    try {
      getConfig().save(new File(dataFolder, "config.yml"));
    } catch (IOException e) {
      api.getPlugin().getLogger().warning("[FPP-Pathfinder] Failed to save config.yml: " + e.getMessage());
    }
  }
}
