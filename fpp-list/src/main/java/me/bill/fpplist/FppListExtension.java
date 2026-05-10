package me.bill.fpplist;

import java.util.List;
import java.util.function.BooleanSupplier;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.api.event.FppBotDespawnEvent;
import me.bill.fakePlayerPlugin.api.event.FppBotSpawnEvent;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

public final class FppListExtension implements FppExtension, Listener {
  private FppApi api;
  private FakePlayerPlugin core;
  private FakePlayerManager manager;
  private BotTabTeam tabTeam;
  private ServerPlayerListListener serverPlayerListListener;
  private BooleanSupplier tabListEnabledProvider;
  private int syncTaskId = -1;

  @Override
  public @NotNull String getName() {
    return "FPP-List";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Adds bot tab-list teams and server player-list samples as an FPP extension.";
  }

  @Override
  public @NotNull List<String> getAuthors() {
    return List.of("Bill");
  }

  @Override
  public void onEnable(@NotNull FppApi api) {
    this.api = api;
    if (!(api.getPlugin() instanceof FakePlayerPlugin fpp)) {
      api.getPlugin().getLogger().warning("[FPP-List] Unsupported host plugin instance.");
      return;
    }

    this.core = fpp;
    this.manager = fpp.getFakePlayerManager();
    saveDefaultConfig();
    tabListEnabledProvider = () -> getConfig().getBoolean("bot-tab-list.enabled", true);
    Config.setTabListEnabledProvider(tabListEnabledProvider);

    if (!getConfig().getBoolean("enabled", true)) {
      info("Disabled by config.");
      return;
    }

    if (getConfig().getBoolean("bot-tab-list.enabled", true)) {
      tabTeam = new BotTabTeam();
      tabTeam.init();
      tabTeam.syncIncremental(manager.getActivePlayers());
      int interval = Math.max(20, getConfig().getInt("bot-tab-list.sync-interval-ticks", 40));
      syncTaskId =
          FppScheduler.runSyncRepeatingWithId(
              core, () -> tabTeam.syncIncremental(manager.getActivePlayers()), interval, interval);
    }

    Bukkit.getPluginManager().registerEvents(this, core);
    if (getConfig().getBoolean("server-player-list.enabled", true)) {
      serverPlayerListListener = new ServerPlayerListListener(this, core, manager);
      Bukkit.getPluginManager().registerEvents(serverPlayerListListener, core);
    }

    info("Enabled.");
  }

  @Override
  public void onDisable() {
    HandlerList.unregisterAll(this);
    if (serverPlayerListListener != null) {
      HandlerList.unregisterAll(serverPlayerListListener);
      serverPlayerListListener = null;
    }
    if (syncTaskId != -1) {
      FppScheduler.cancelTask(syncTaskId);
      syncTaskId = -1;
    }
    if (tabTeam != null) {
      tabTeam.destroy();
      tabTeam = null;
    }
    if (tabListEnabledProvider != null) {
      Config.clearTabListEnabledProvider(tabListEnabledProvider);
      tabListEnabledProvider = null;
    }
    manager = null;
    core = null;
    api = null;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onBotSpawn(FppBotSpawnEvent event) {
    if (tabTeam == null || manager == null || core == null) return;
    FppScheduler.runSyncLater(
        core,
        () -> {
          FakePlayer fp = manager.getByUuid(event.getBot().getUuid());
          if (fp != null) tabTeam.addBot(fp);
        },
        20L);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onBotDespawn(FppBotDespawnEvent event) {
    if (tabTeam == null || manager == null) return;
    FakePlayer fp = manager.getByUuid(event.getBot().getUuid());
    if (fp != null) tabTeam.removeBot(fp);
    else tabTeam.removeEntry(event.getBot().getName());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerJoin(PlayerJoinEvent event) {
    if (tabTeam == null || manager == null || core == null) return;
    long delay = manager.isRestorationInProgress() ? 40L : 5L;
    FppScheduler.runSyncLater(core, () -> tabTeam.syncToPlayer(event.getPlayer()), delay);
  }

  private void info(String message) {
    if (api != null) api.getPlugin().getLogger().info("[FPP-List] " + message);
  }
}
