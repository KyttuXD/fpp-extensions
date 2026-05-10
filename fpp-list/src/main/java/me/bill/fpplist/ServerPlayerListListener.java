package me.bill.fpplist;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppNameTagService;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.RemoteBotEntry;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

final class ServerPlayerListListener implements Listener {

  private static final int MAX_SAMPLE = 12;
  private static volatile Boolean serverPropertiesHideOnlinePlayers = null;

  private final FakePlayerPlugin plugin;
  private final FakePlayerManager manager;
  private final FppListExtension extension;

  ServerPlayerListListener(
      FppListExtension extension, FakePlayerPlugin plugin, FakePlayerManager manager) {
    this.extension = extension;
    this.plugin = plugin;
    this.manager = manager;
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPing(PaperServerListPingEvent event) {
    boolean hidePlayers = shouldKeepPlayersHidden(event) || isServerPropertiesHideOnlinePlayers();
    List<FakePlayer> localBots = new ArrayList<>(manager.getActivePlayers());

    if (hidePlayers) {
      event.getListedPlayers().clear();
      return;
    }

    if (countBots()) {
      int realPlayers = Math.max(0, Bukkit.getOnlinePlayers().size() - localBots.size());
      int botCount = localBots.size();
      if (Config.isNetworkMode() && includeRemoteBots()) {
        var cache = plugin.getRemoteBotCache();
        if (cache != null) botCount += cache.count();
      }
      int total = realPlayers + botCount;
      event.setNumPlayers(total);
      if (event.getMaxPlayers() < total) event.setMaxPlayers(total + 1);
    } else {
      int realPlayers = Math.max(0, Bukkit.getOnlinePlayers().size() - localBots.size());
      event.setNumPlayers(realPlayers);
    }

    List<PaperServerListPingEvent.ListedPlayerInfo> freshSample = new ArrayList<>();

    if (countBots()) {
      Map<UUID, String> botNames = new LinkedHashMap<>();
      FppNameTagService nameTagService = plugin.getFppApi().getService(FppNameTagService.class);
      for (FakePlayer fp : localBots) {
        String displayName = fp.getName();
        if (nameTagService != null && nameTagService.isAvailable()) {
          try {
            String freshNick = nameTagService.getNick(fp.getUuid());
            displayName = freshNick != null ? freshNick : fp.getName();
          } catch (Throwable ignored) {
            String cachedNick = fp.getNameTagNick();
            displayName = cachedNick != null ? cachedNick : fp.getName();
          }
        } else {
          String cachedNick = fp.getNameTagNick();
          displayName = cachedNick != null ? cachedNick : fp.getName();
        }
        botNames.put(fp.getUuid(), displayName);
      }

      for (Map.Entry<UUID, String> entry : botNames.entrySet()) {
        String name = entry.getValue();
        if (name != null && !name.isBlank()) {
          freshSample.add(new PaperServerListPingEvent.ListedPlayerInfo(name, entry.getKey()));
        }
      }

      if (Config.isNetworkMode() && includeRemoteBots()) {
        var cache = plugin.getRemoteBotCache();
        if (cache != null) {
          for (RemoteBotEntry remote : cache.getAll()) {
            String name = remote.displayName();
            if (name == null || name.isBlank()) name = remote.name();
            if (!name.isBlank()) {
              freshSample.add(new PaperServerListPingEvent.ListedPlayerInfo(name, remote.uuid()));
            }
          }
        }
      }
    }

    Set<UUID> botUuids = new HashSet<>();
    for (FakePlayer fp : localBots) botUuids.add(fp.getUuid());

    for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
      if (botUuids.contains(player.getUniqueId())) continue;
      String name = player.getName();
      if (!name.isBlank()) {
        freshSample.add(new PaperServerListPingEvent.ListedPlayerInfo(name, player.getUniqueId()));
      }
    }

    Collections.shuffle(freshSample);
    if (freshSample.size() > MAX_SAMPLE) freshSample = freshSample.subList(0, MAX_SAMPLE);

    List<PaperServerListPingEvent.ListedPlayerInfo> listed = event.getListedPlayers();
    listed.clear();
    listed.addAll(freshSample);
  }

  private boolean countBots() {
    return extension.getConfig().getBoolean("server-player-list.count-bots", true);
  }

  private boolean includeRemoteBots() {
    return extension.getConfig().getBoolean("server-player-list.include-remote-bots", false);
  }

  private static boolean shouldKeepPlayersHidden(PaperServerListPingEvent event) {
    String[] boolMethods = {
      "shouldHidePlayers", "getHidePlayers", "isHidePlayers", "isPlayersHidden"
    };
    for (String methodName : boolMethods) {
      try {
        Method method = event.getClass().getMethod(methodName);
        if (method.getReturnType() == boolean.class) return (boolean) method.invoke(event);
      } catch (Throwable ignored) {
      }
    }
    return event.getNumPlayers() < 0;
  }

  private static boolean isServerPropertiesHideOnlinePlayers() {
    Boolean cached = serverPropertiesHideOnlinePlayers;
    if (cached != null) return cached;

    synchronized (ServerPlayerListListener.class) {
      if (serverPropertiesHideOnlinePlayers != null) return serverPropertiesHideOnlinePlayers;

      boolean hidden = false;
      for (File file : candidateServerProperties()) {
        if (file == null || !file.isFile()) continue;
        try (FileInputStream in = new FileInputStream(file)) {
          Properties props = new Properties();
          props.load(in);
          hidden = Boolean.parseBoolean(props.getProperty("hide-online-players", "false"));
          if (hidden) break;
        } catch (Exception ignored) {
        }
      }

      serverPropertiesHideOnlinePlayers = hidden;
      return hidden;
    }
  }

  private static List<File> candidateServerProperties() {
    List<File> files = new ArrayList<>();
    files.add(new File("server.properties"));
    try {
      File worldContainer = Bukkit.getWorldContainer();
      if (worldContainer != null) {
        files.add(new File(worldContainer, "server.properties"));
        File parent = worldContainer.getParentFile();
        if (parent != null) files.add(new File(parent, "server.properties"));
      }
    } catch (Throwable ignored) {
    }
    return files;
  }
}
