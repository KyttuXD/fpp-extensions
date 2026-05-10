package me.bill.fppnametag;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.api.FppNameTagService;
import me.bill.fakePlayerPlugin.api.event.FppBotSpawnEvent;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.fakeplayer.SkinProfile;
import me.bill.fakePlayerPlugin.util.BotRenameHelper;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class FppNameTagExtension implements FppExtension, Listener {
  private FppApi api;
  private FakePlayerPlugin core;
  private FakePlayerManager manager;
  private NameTagService service;
  private volatile boolean active;

  @Override
  public @NotNull String getName() {
    return "FPP-NameTag";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Adds NameTag plugin integration as a FakePlayerPlugin extension.";
  }

  @Override
  public @NotNull List<String> getAuthors() {
    return List.of("Bill");
  }

  @Override
  public @NotNull List<String> getSoftDependencies() {
    return List.of("NameTag");
  }

  @Override
  public void onEnable(@NotNull FppApi api) {
    this.api = api;
    if (!(api.getPlugin() instanceof FakePlayerPlugin fpp)) {
      api.getPlugin().getLogger().warning("[FPP-NameTag] Unsupported host plugin instance.");
      return;
    }

    core = fpp;
    manager = fpp.getFakePlayerManager();
    saveDefaultConfig();
    active = getConfig().getBoolean("enabled", true);
    service = new NameTagService();
    api.registerService(FppNameTagService.class, service);

    if (!active) {
      info("Disabled by config.");
      return;
    }

    Bukkit.getPluginManager().registerEvents(this, core);
    info(service.isAvailable() ? "Enabled." : "Enabled, waiting for NameTag.");
  }

  @Override
  public void onDisable() {
    active = false;
    HandlerList.unregisterAll(this);
    manager = null;
    core = null;
    api = null;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onBotSpawn(FppBotSpawnEvent event) {
    if (!active || manager == null || core == null || !botIsolation()) return;
    UUID uuid = event.getBot().getUuid();
    FppScheduler.runSyncLater(core, () -> isolateBot(uuid, 0), 3L);
  }

  private void isolateBot(UUID uuid, int attempts) {
    if (!active || manager == null || core == null) return;
    FakePlayer bot = manager.getByUuid(uuid);
    if (bot == null) return;

    FppNameTagService.NickData nickData = service.clearBotFromCache(uuid);
    if (nickData == null) return;

    if (nickData.nick() != null && !nickData.nick().isBlank()) {
      bot.setNameTagNick(nickData.nick());
      if (refreshDisplayNames()) manager.refreshDisplayName(bot);
    }

    if (nickData.skin() != null) {
      applySkin(bot, nickData.skin(), "nametag:" + bot.getName());
    }

    if (!syncNickAsRename() || !nickData.canRename()) return;
    String targetName = nickData.plainNick();
    if (targetName == null || targetName.equalsIgnoreCase(bot.getName())) return;
    if (blockNickConflicts() && service.isNickUsedByRealPlayer(targetName)) return;

    FppNameTagService.BotSkin savedSkin = nickData.skin();
    boolean started = new BotRenameHelper(core, manager).rename(bot, targetName, ignored -> {});
    if (started && savedSkin != null) {
      FppScheduler.runSyncLater(core, () -> applySkinAfterRename(targetName, savedSkin, 0), 5L);
    } else if (!started && attempts < 3) {
      FppScheduler.runSyncLater(core, () -> isolateBot(uuid, attempts + 1), 10L);
    }
  }

  private void applySkinAfterRename(String targetName, FppNameTagService.BotSkin skin, int attempts) {
    if (!active || manager == null || core == null) return;
    FakePlayer renamed = manager.getByName(targetName);
    if (renamed != null) {
      applySkin(renamed, skin, "nametag:" + targetName);
      return;
    }
    if (attempts < 40) {
      FppScheduler.runSyncLater(core, () -> applySkinAfterRename(targetName, skin, attempts + 1), 5L);
    }
  }

  private void applySkin(FakePlayer bot, FppNameTagService.BotSkin skin, String source) {
    if (core == null || core.getSkinManager() == null || skin == null) return;
    SkinProfile profile = new SkinProfile(skin.texture(), skin.signature(), source);
    core.getSkinManager().applySkinFromProfile(bot, profile);
    Player player = bot.getPlayer();
    if (player != null && player.isOnline()) {
      service.applySkin(player, skin);
    }
  }

  private boolean blockNickConflicts() {
    return getConfig().getBoolean("nametag.block-nick-conflicts", true);
  }

  private boolean botIsolation() {
    return getConfig().getBoolean("nametag.bot-isolation", true);
  }

  private boolean syncNickAsRename() {
    return getConfig().getBoolean("nametag.sync-nick-as-rename", false);
  }

  private boolean refreshDisplayNames() {
    return getConfig().getBoolean("nametag.refresh-display-names", true);
  }

  private void info(String message) {
    if (core != null) core.getLogger().info("[FPP-NameTag] " + message);
  }

  private final class NameTagService implements FppNameTagService {
    @Override
    public boolean isAvailable() {
      return active && nameTagPlugin() != null;
    }

    @Override
    public @Nullable String getNick(UUID uuid) {
      try {
        Object api = getApi();
        Class<?> iface = getApiIface();
        if (api == null || iface == null) return null;
        return (String) iface.getMethod("getNick", UUID.class).invoke(api, uuid);
      } catch (Throwable ignored) {
        return null;
      }
    }

    @Override
    public @Nullable String getNick(Player player) {
      try {
        Object api = getApi();
        Class<?> iface = getApiIface();
        if (api == null || iface == null) return null;
        return (String) iface.getMethod("getNick", Player.class).invoke(api, player);
      } catch (Throwable ignored) {
        return null;
      }
    }

    @Override
    public @Nullable BotSkin getSkin(UUID uuid) {
      try {
        Object api = getApi();
        Class<?> iface = getApiIface();
        if (api == null || iface == null) return null;
        Object skin = iface.getMethod("getSkin", UUID.class).invoke(api, uuid);
        if (skin == null) return null;
        return new BotSkin(
            (String) skin.getClass().getMethod("texture").invoke(skin),
            (String) skin.getClass().getMethod("signature").invoke(skin));
      } catch (Throwable ignored) {
        return null;
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable NickData clearBotFromCache(UUID botUuid) {
      try {
        Plugin nt = nameTagPlugin();
        if (nt == null) return null;
        Field f = nt.getClass().getDeclaredField("playerCache");
        f.setAccessible(true);
        ConcurrentHashMap<UUID, ?> cache = (ConcurrentHashMap<UUID, ?>) f.get(nt);
        if (cache == null) return null;

        Object nickPlayer = cache.get(botUuid);
        if (nickPlayer != null) {
          try {
            boolean hasNick = (boolean) nickPlayer.getClass().getMethod("hasNick").invoke(nickPlayer);
            if (hasNick) {
              String rawNick = null;
              try {
                rawNick = (String) nickPlayer.getClass().getMethod("getNickname").invoke(nickPlayer);
              } catch (Throwable ignored) {
              }

              BotSkin skin = null;
              try {
                String texture =
                    (String) nickPlayer.getClass().getMethod("getTexture").invoke(nickPlayer);
                String signature =
                    (String) nickPlayer.getClass().getMethod("getSignature").invoke(nickPlayer);
                if (texture != null && !texture.isBlank()) skin = new BotSkin(texture, signature);
              } catch (Throwable ignored) {
              }

              String plain = stripFormattingForRename(rawNick);
              return new NickData(rawNick != null ? rawNick : "", plain, skin);
            }
          } catch (Throwable ignored) {
          }
        }

        cache.remove(botUuid);
      } catch (Throwable t) {
        if (core != null) {
          core.getLogger().fine("[FPP-NameTag] clearBotFromCache skipped: " + t.getMessage());
        }
      }
      return null;
    }

    @Override
    public void resetBotNickname(Player bot) {
      try {
        Object api = getApi();
        Class<?> iface = getApiIface();
        if (api == null || iface == null) return;
        iface.getMethod("resetNickname", Player.class).invoke(api, bot);
      } catch (Throwable t) {
        if (core != null) {
          core.getLogger().warning("[FPP-NameTag] resetNickname failed: " + t.getMessage());
        }
      }
    }

    @Override
    public void applySkin(Player player, BotSkin skin) {
      try {
        Object api = getApi();
        Class<?> iface = getApiIface();
        if (api == null || iface == null || skin == null) return;
        iface
            .getMethod("setSkinFromTextureAndSignature", Player.class, String.class, String.class)
            .invoke(api, player, skin.texture(), skin.signature());
      } catch (Throwable t) {
        if (core != null) {
          core.getLogger().fine("[FPP-NameTag] applySkin failed: " + t.getMessage());
        }
      }
    }

    @Override
    public boolean isNickUsedByRealPlayer(String candidateName) {
      try {
        Object api = getApi();
        Class<?> iface = getApiIface();
        if (api == null || iface == null || manager == null) return false;
        Method getNickByPlayer = iface.getMethod("getNick", Player.class);
        for (Player online : Bukkit.getOnlinePlayers()) {
          if (manager.getByUuid(online.getUniqueId()) != null) continue;
          String nick = (String) getNickByPlayer.invoke(api, online);
          String plain = stripFormattingForRename(nick);
          if (plain != null && plain.equalsIgnoreCase(candidateName)) {
            return true;
          }
        }
      } catch (Throwable ignored) {
      }
      return false;
    }

    private @Nullable Plugin nameTagPlugin() {
      Plugin plugin = Bukkit.getPluginManager().getPlugin("NameTag");
      return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    private @Nullable Object getApi() {
      try {
        Plugin nt = nameTagPlugin();
        if (nt == null) return null;
        Class<?> cls = nt.getClass().getClassLoader().loadClass("gg.lode.nametagapi.NameTagAPI");
        return cls.getMethod("getApi").invoke(null);
      } catch (Throwable ignored) {
        return null;
      }
    }

    private @Nullable Class<?> getApiIface() {
      try {
        Plugin nt = nameTagPlugin();
        if (nt == null) return null;
        return nt.getClass().getClassLoader().loadClass("gg.lode.nametagapi.INameTagAPI");
      } catch (Throwable ignored) {
        return null;
      }
    }
  }

  private static @Nullable String stripFormattingForRename(@Nullable String nick) {
    if (nick == null) return null;
    String stripped = nick.replaceAll("<[^>]*>", "");
    stripped = stripped.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "");
    stripped = stripped.replaceAll("[^a-zA-Z0-9_]", "");
    if (stripped.length() > 16) stripped = stripped.substring(0, 16);
    return stripped.isBlank() ? null : stripped;
  }
}
