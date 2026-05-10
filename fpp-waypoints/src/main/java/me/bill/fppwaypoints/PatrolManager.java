package me.bill.fppwaypoints;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppBot;
import org.bukkit.Location;
import org.bukkit.entity.Player;

final class PatrolManager {
  private record PatrolState(String routeName, List<Location> route, boolean random, List<Integer> order) {}

  private final FppApi api;
  private final double arrivalDistance;
  private final boolean reshuffleEachCycle;
  private final Map<UUID, PatrolState> active = new ConcurrentHashMap<>();

  PatrolManager(FppApi api, double arrivalDistance, boolean reshuffleEachCycle) {
    this.api = api;
    this.arrivalDistance = arrivalDistance;
    this.reshuffleEachCycle = reshuffleEachCycle;
  }

  boolean start(FppBot bot, String routeName, List<Location> route, boolean random) {
    if (route == null || route.isEmpty()) return false;
    Player entity = bot.getEntity();
    if (entity == null || !entity.isOnline()) return false;
    Location first = route.get(0);
    if (first.getWorld() == null || !first.getWorld().equals(entity.getWorld())) return false;

    api.cancelNavigation(bot);
    List<Location> clonedRoute = route.stream().map(Location::clone).toList();
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < clonedRoute.size(); i++) order.add(i);
    if (random) Collections.shuffle(order, new Random());
    active.put(bot.getUuid(), new PatrolState(routeName, clonedRoute, random, order));
    navigateStep(bot.getUuid(), 0);
    return true;
  }

  boolean stop(FppBot bot) {
    boolean wasActive = active.remove(bot.getUuid()) != null;
    api.cancelNavigation(bot);
    return wasActive;
  }

  void stopAll() {
    for (UUID uuid : new ArrayList<>(active.keySet())) {
      api.getBot(uuid).ifPresent(api::cancelNavigation);
    }
    active.clear();
  }

  boolean isPatrolling(UUID botUuid) {
    return active.containsKey(botUuid);
  }

  String routeName(UUID botUuid) {
    PatrolState state = active.get(botUuid);
    return state != null ? state.routeName() : null;
  }

  private void navigateStep(UUID botUuid, int cycleIndex) {
    PatrolState state = active.get(botUuid);
    if (state == null || state.route().isEmpty()) return;
    FppBot bot = api.getBot(botUuid).orElse(null);
    if (bot == null || !bot.isOnline()) {
      active.remove(botUuid);
      return;
    }
    Player entity = bot.getEntity();
    if (entity == null || !entity.isOnline()) {
      active.remove(botUuid);
      return;
    }

    int routeIndex;
    int nextCycle;
    if (state.random()) {
      List<Integer> order = state.order();
      if (order.isEmpty()) {
        active.remove(botUuid);
        return;
      }
      int normalized = cycleIndex;
      if (normalized >= order.size()) {
        if (reshuffleEachCycle) Collections.shuffle(order, new Random());
        normalized = 0;
      }
      routeIndex = order.get(normalized);
      nextCycle = normalized + 1;
    } else {
      routeIndex = Math.floorMod(cycleIndex, state.route().size());
      nextCycle = (routeIndex + 1) % state.route().size();
    }

    Location dest = state.route().get(routeIndex);
    if (dest.getWorld() == null || !dest.getWorld().equals(entity.getWorld())) {
      active.remove(botUuid);
      return;
    }

    api.navigateTo(
        bot,
        dest,
        () -> navigateStep(botUuid, nextCycle),
        () -> navigateStep(botUuid, nextCycle),
        () -> active.remove(botUuid),
        arrivalDistance);
  }
}
