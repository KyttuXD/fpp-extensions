package me.bill.fppluckperms;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.util.FppLogger;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class LuckPermsHelper {

  private static EventSubscription<UserDataRecalculateEvent> eventSub;

  private LuckPermsHelper() {}

  private static void debug(String message) {
    FppLogger.debug("LP", false, message);
  }

  public static boolean isAvailable() {
    Plugin p = Bukkit.getPluginManager().getPlugin("LuckPerms");
    return p != null && p.isEnabled();
  }

  private static LuckPerms lp() {
    if (!isAvailable()) return null;
    try {
      return LuckPermsProvider.get();
    } catch (IllegalStateException e) {
      return null;
    }
  }

  public static void subscribeLpEvents(FakePlayerPlugin plugin, FakePlayerManager manager) {
    LuckPerms api = lp();
    if (api == null) return;

    if (eventSub != null) {
      eventSub.close();
      eventSub = null;
    }
    try {
      eventSub =
          api.getEventBus()
              .subscribe(
                  plugin,
                  UserDataRecalculateEvent.class,
                  event -> {
                    UUID uuid = event.getUser().getUniqueId();
                    FakePlayer fp = manager.getByUuid(uuid);
                    if (fp == null) return;

                    String newGroup;
                    try {
                      newGroup = event.getUser().getPrimaryGroup();
                    } catch (NullPointerException ignored) {
                      newGroup = null;
                    }
                    if (newGroup == null) newGroup = "default";
                    fp.setLuckpermsGroup(newGroup);
                    FppScheduler.runSync(plugin, () -> manager.refreshDisplayName(fp));
                    debug(
                        "UserDataRecalculate for bot '"
                            + fp.getName()
                            + "' - new group='"
                            + newGroup
                            + "', refreshing display name.");
                  });
      debug(
          "Subscribed to UserDataRecalculateEvent - bot display names will auto-update"
              + " when LP groups change.");
    } catch (Exception e) {
      FppLogger.warn("[LP] Failed to subscribe to LP events: " + e.getMessage());
    }
  }

  public static void unsubscribeLpEvents() {
    if (eventSub != null) {
      try {
        eventSub.close();
      } catch (Exception ignored) {
      }
      eventSub = null;
    }
  }

  public static CompletableFuture<String> ensureGroupBeforeSpawn(UUID botUuid, String configGroup) {
    LuckPerms api = lp();
    if (api == null) return CompletableFuture.completedFuture("default");

    String targetGroup =
        (configGroup != null && !configGroup.trim().isEmpty()) ? configGroup.trim() : "default";

    return api.getUserManager()
        .loadUser(botUuid)
        .thenCompose(
            user -> {
              if (user == null) return CompletableFuture.completedFuture(targetGroup);

              String storedPrimary = user.getPrimaryGroup();

              boolean hasExplicitGroup =
                  storedPrimary != null
                      && !storedPrimary.equalsIgnoreCase("default")
                      && !storedPrimary.isBlank();
              boolean configForcesGroup = configGroup != null && !configGroup.trim().isEmpty();

              if (hasExplicitGroup && !configForcesGroup) {

                debug(
                    "ensureGroupBeforeSpawn: keeping stored group '"
                        + storedPrimary
                        + "' for "
                        + botUuid);
                return CompletableFuture.completedFuture(storedPrimary);
              }

              user.data().clear(NodeType.INHERITANCE::matches);
              user.data().add(InheritanceNode.builder(targetGroup).build());
              user.setPrimaryGroup(targetGroup);

              user.getCachedData().invalidate();

              return api.getUserManager()
                  .saveUser(user)
                  .thenApply(
                      v -> {
                        debug(
                            "ensureGroupBeforeSpawn: set group '"
                                + targetGroup
                                + "' for "
                                + botUuid);

                        user.getCachedData().invalidate();
                        return targetGroup;
                      });
            })
        .exceptionally(
            ex -> {
              FppLogger.warn(
                  "[LP] ensureGroupBeforeSpawn error for " + botUuid + ": " + ex.getMessage());
              return targetGroup;
            });
  }

  public static CompletableFuture<Void> applyGroupToOnlineUser(UUID botUuid, String groupName) {
    LuckPerms api = lp();
    if (api == null) return CompletableFuture.completedFuture(null);

    User onlineUser = api.getUserManager().getUser(botUuid);
    if (onlineUser != null) {
      onlineUser.data().clear(NodeType.INHERITANCE::matches);
      onlineUser.data().add(InheritanceNode.builder(groupName).build());
      onlineUser.setPrimaryGroup(groupName);
      onlineUser.getCachedData().invalidate();
      debug(
          "applyGroupToOnlineUser: applying '" + groupName + "' to online user " + botUuid);

      return api.getUserManager().saveUser(onlineUser);
    }

    debug(
        "applyGroupToOnlineUser: user not online yet, falling back to setPlayerGroup for "
            + botUuid);
    return setPlayerGroup(botUuid, groupName);
  }

  public static CompletableFuture<Void> setPlayerGroup(UUID playerUuid, String newGroupName) {
    LuckPerms api = lp();
    if (api == null)
      return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms not available"));

    User onlineUser = api.getUserManager().getUser(playerUuid);
    if (onlineUser != null) {
      onlineUser.data().clear(NodeType.INHERITANCE::matches);
      onlineUser.data().add(InheritanceNode.builder(newGroupName).build());
      onlineUser.setPrimaryGroup(newGroupName);
      onlineUser.getCachedData().invalidate();
      return api.getUserManager()
          .saveUser(onlineUser)
          .thenRun(
              () ->
                  debug(
                      "setPlayerGroup (online): " + playerUuid + " → '" + newGroupName + "'"));
    }

    return api.getUserManager()
        .loadUser(playerUuid)
        .thenCompose(
            user -> {
              if (user == null)
                return CompletableFuture.failedFuture(
                    new IllegalStateException("LP user not found for " + playerUuid));
              user.data().clear(NodeType.INHERITANCE::matches);
              user.data().add(InheritanceNode.builder(newGroupName).build());
              user.setPrimaryGroup(newGroupName);
              user.getCachedData().invalidate();
              return api.getUserManager()
                  .saveUser(user)
                  .thenRun(
                      () ->
                          debug(
                              "setPlayerGroup (offline): "
                                  + playerUuid
                                  + " → '"
                                  + newGroupName
                                  + "'"));
            });
  }

  public static String getResolvedPrefix(UUID botUuid) {
    LuckPerms api = lp();
    if (api == null) return "";
    try {
      User user = api.getUserManager().getUser(botUuid);
      if (user == null) return "";
      String prefix = user.getCachedData().getMetaData().getPrefix();
      return prefix != null ? prefix : "";
    } catch (Exception e) {
      debug("getResolvedPrefix error for " + botUuid + ": " + e.getMessage());
      return "";
    }
  }

  public static String getResolvedSuffix(UUID botUuid) {
    LuckPerms api = lp();
    if (api == null) return "";
    try {
      User user = api.getUserManager().getUser(botUuid);
      if (user == null) return "";
      String suffix = user.getCachedData().getMetaData().getSuffix();
      return suffix != null ? suffix : "";
    } catch (Exception e) {
      debug("getResolvedSuffix error for " + botUuid + ": " + e.getMessage());
      return "";
    }
  }

  public static String getPrimaryGroup(UUID botUuid) {
    LuckPerms api = lp();
    if (api == null) return "default";
    try {
      User user = api.getUserManager().getUser(botUuid);
      if (user == null) return "default";
      String group = user.getPrimaryGroup();
      return group != null && !group.isBlank() ? group : "default";
    } catch (Exception e) {
      debug("getPrimaryGroup error for " + botUuid + ": " + e.getMessage());
      return "default";
    }
  }

  public static int getGroupWeight(String groupName) {
    LuckPerms api = lp();
    if (api == null || groupName == null || groupName.isBlank()) return 0;
    try {
      Group group = api.getGroupManager().getGroup(groupName);
      if (group == null) return 0;
      return group.getWeight().orElse(0);
    } catch (Exception e) {
      debug("getGroupWeight error for '" + groupName + "': " + e.getMessage());
      return 0;
    }
  }

  public static void refreshUserCache(UUID uuid) {
    LuckPerms api = lp();
    if (api == null) return;
    try {
      User user = api.getUserManager().getUser(uuid);
      if (user == null) {

        api.getUserManager()
            .loadUser(uuid)
            .thenAccept(
                loadedUser -> {
                  if (loadedUser != null) {
                    loadedUser.getCachedData().invalidate();
                    debug("Forced cache refresh for " + uuid + " (loaded first)");
                  }
                });
      } else {

        user.getCachedData().invalidate();
        debug("Forced cache refresh for " + uuid);
      }
    } catch (Exception e) {
      debug("refreshUserCache error for " + uuid + ": " + e.getMessage());
    }
  }

  public static CompletableFuture<String> getStoredPrimaryGroup(UUID playerUuid) {
    LuckPerms api = lp();
    if (api == null) return CompletableFuture.completedFuture("default");
    return api.getUserManager()
        .loadUser(playerUuid)
        .thenApply(
            user -> {
              if (user == null) return "default";
              user.getCachedData().invalidate();
              String group = user.getPrimaryGroup();
              return group != null && !group.isBlank() ? group : "default";
            })
        .exceptionally(ex -> "default");
  }

  public static boolean groupExists(String name) {
    LuckPerms api = lp();
    if (api == null || name == null || name.isBlank()) return false;
    return api.getGroupManager().getGroup(name.toLowerCase()) != null;
  }

  public static List<String> getAllGroupNames() {
    LuckPerms api = lp();
    if (api == null) return Collections.emptyList();
    return api.getGroupManager().getLoadedGroups().stream().map(Group::getName).sorted().toList();
  }

  public static String buildGroupSummary() {
    LuckPerms api = lp();
    if (api == null) return "(LP unavailable)";
    try {
      StringBuilder sb = new StringBuilder();
      api.getGroupManager().getLoadedGroups().stream()
          .sorted(Comparator.comparing(Group::getName))
          .forEach(
              g -> {
                int w = g.getWeight().orElse(0);
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(g.getName()).append("(w=").append(w).append(')');
              });
      return sb.isEmpty() ? "(none)" : sb.toString();
    } catch (Exception e) {
      return "(error: " + e.getMessage() + ")";
    }
  }

  @Deprecated
  public static void invalidateCache() {}

  @Deprecated
  public static CompletableFuture<Void> addPlayerToGroup(UUID playerUuid, String groupName) {
    return setPlayerGroup(playerUuid, groupName);
  }

  @Deprecated
  public static CompletableFuture<Void> cleanupBotUser(UUID playerUuid) {
    return setPlayerGroup(playerUuid, "default");
  }

  @Deprecated
  public static CompletableFuture<String> getPlayerPrimaryGroup(UUID playerUuid) {
    return getStoredPrimaryGroup(playerUuid);
  }
}
