package me.bill.fppwaypoints;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

final class WaypointStore {
  private static final String ROOT = "waypoints.routes";

  private final Plugin plugin;
  private final File dataFolder;
  private final Logger logger;
  private final Map<String, List<Location>> routes = new ConcurrentHashMap<>();
  private final File file;

  WaypointStore(Plugin plugin, File dataFolder) {
    this.plugin = plugin;
    this.dataFolder = dataFolder;
    this.logger = plugin.getLogger();
    this.file = new File(dataFolder, "waypoints.yml");
  }

  void load(boolean importCoreWaypoints) {
    routes.clear();
    if (!dataFolder.exists() && !dataFolder.mkdirs()) {
      logger.warning("[FPP-Waypoints] Could not create data folder: " + dataFolder.getAbsolutePath());
    }

    if (file.exists()) {
      loadFromSection(YamlConfiguration.loadConfiguration(file), "");
    }

    if (routes.isEmpty() && importCoreWaypoints) {
      importCoreRoutes();
      if (!routes.isEmpty()) save();
    }

    if (!routes.isEmpty()) {
      int positions = routes.values().stream().mapToInt(List::size).sum();
      logger.info(
          "[FPP-Waypoints] Loaded " + routes.size() + " route(s) with " + positions + " position(s).");
    }
  }

  private void importCoreRoutes() {
    File unified = new File(plugin.getDataFolder(), "data/bot-data.yml");
    if (unified.exists()) {
      ConfigurationSection root = YamlConfiguration.loadConfiguration(unified).getConfigurationSection(ROOT);
      if (root != null) {
        loadFromSection(root, "");
        if (!routes.isEmpty()) {
          logger.info("[FPP-Waypoints] Imported routes from core data/bot-data.yml.");
          return;
        }
      }
    }

    File legacy = new File(plugin.getDataFolder(), "data/waypoints.yml");
    if (legacy.exists()) {
      loadFromSection(YamlConfiguration.loadConfiguration(legacy), "");
      if (!routes.isEmpty()) {
        logger.info("[FPP-Waypoints] Imported routes from legacy data/waypoints.yml.");
      }
    }
  }

  private void loadFromSection(ConfigurationSection root, String unusedPrefix) {
    for (String routeName : root.getKeys(false)) {
      ConfigurationSection routeSection = root.getConfigurationSection(routeName);
      if (routeSection == null) continue;
      List<Location> positions = new ArrayList<>();
      int i = 0;
      while (routeSection.contains(String.valueOf(i))) {
        ConfigurationSection pos = routeSection.getConfigurationSection(String.valueOf(i));
        if (pos != null) {
          Location loc = readLocation(pos);
          if (loc != null) positions.add(loc);
        }
        i++;
      }
      if (!positions.isEmpty()) routes.put(routeName.toLowerCase(), positions);
    }
  }

  private Location readLocation(ConfigurationSection pos) {
    String worldName = pos.getString("world", "world");
    World world = Bukkit.getWorld(worldName != null ? worldName : "world");
    if (world == null) return null;
    return new Location(
        world,
        pos.getDouble("x"),
        pos.getDouble("y"),
        pos.getDouble("z"),
        (float) pos.getDouble("yaw"),
        (float) pos.getDouble("pitch"));
  }

  void save() {
    YamlConfiguration yaml = new YamlConfiguration();
    for (Map.Entry<String, List<Location>> entry : routes.entrySet()) {
      String routeName = entry.getKey();
      List<Location> positions = entry.getValue();
      for (int i = 0; i < positions.size(); i++) {
        Location loc = positions.get(i);
        String path = routeName + "." + i + ".";
        yaml.set(path + "world", loc.getWorld() != null ? loc.getWorld().getName() : "world");
        yaml.set(path + "x", loc.getX());
        yaml.set(path + "y", loc.getY());
        yaml.set(path + "z", loc.getZ());
        yaml.set(path + "yaw", (double) loc.getYaw());
        yaml.set(path + "pitch", (double) loc.getPitch());
      }
    }
    try {
      yaml.save(file);
    } catch (IOException ex) {
      logger.warning("[FPP-Waypoints] Could not save waypoints.yml: " + ex.getMessage());
    }
  }

  boolean createRoute(String name) {
    String key = normalize(name);
    if (routes.containsKey(key)) return false;
    routes.put(key, new ArrayList<>());
    save();
    return true;
  }

  int addPosition(String name, Location loc) {
    List<Location> positions = routes.computeIfAbsent(normalize(name), ignored -> new ArrayList<>());
    positions.add(loc.clone());
    save();
    return positions.size();
  }

  boolean removePosition(String name, int index) {
    List<Location> positions = routes.get(normalize(name));
    if (positions == null || index < 0 || index >= positions.size()) return false;
    positions.remove(index);
    if (positions.isEmpty()) routes.remove(normalize(name));
    save();
    return true;
  }

  boolean delete(String name) {
    boolean removed = routes.remove(normalize(name)) != null;
    if (removed) save();
    return removed;
  }

  boolean clear(String name) {
    List<Location> removed = routes.remove(normalize(name));
    if (removed == null || removed.isEmpty()) return false;
    save();
    return true;
  }

  boolean hasRoute(String name) {
    return routes.containsKey(normalize(name));
  }

  List<Location> getRoute(String name) {
    List<Location> route = routes.get(normalize(name));
    if (route == null || route.isEmpty()) return null;
    return Collections.unmodifiableList(new ArrayList<>(route));
  }

  Set<String> getNames() {
    return Collections.unmodifiableSet(new TreeSet<>(routes.keySet()));
  }

  int getPositionCount(String name) {
    List<Location> route = routes.get(normalize(name));
    return route != null ? route.size() : 0;
  }

  private String normalize(String name) {
    return name.toLowerCase();
  }
}
