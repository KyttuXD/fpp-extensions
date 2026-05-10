package me.bill.fppgroups;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppBot;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class GroupStore {
  static final String DEFAULT_GROUP = "default";

  private final FppApi api;
  private final Plugin plugin;
  private final File file;
  private YamlConfiguration yaml;

  GroupStore(FppApi api, File dataFolder) {
    this.api = api;
    this.plugin = api.getPlugin();
    this.file = new File(dataFolder, "bot-groups.yml");
  }

  void load(boolean importCoreGroups) {
    File parent = file.getParentFile();
    if (parent != null && !parent.exists()) parent.mkdirs();
    if (importCoreGroups && !file.exists()) {
      File oldFile = new File(plugin.getDataFolder(), "data/bot-groups.yml");
      if (oldFile.isFile()) {
        try {
          Files.copy(oldFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
          plugin.getLogger().info("[FPP-Groups] Imported core bot-groups.yml.");
        } catch (IOException e) {
          plugin.getLogger().warning("[FPP-Groups] Failed to import core groups: " + e.getMessage());
        }
      }
    }
    yaml = YamlConfiguration.loadConfiguration(file);
  }

  void save() {
    try {
      yaml.save(file);
    } catch (IOException e) {
      plugin.getLogger().warning("[FPP-Groups] Save failed: " + e.getMessage());
    }
  }

  List<String> getGroups(Player owner) {
    if (owner == null) return List.of();
    Set<String> names = new LinkedHashSet<>();
    names.add(DEFAULT_GROUP);
    ConfigurationSection sec = yaml.getConfigurationSection(path(owner.getUniqueId()));
    if (sec != null) names.addAll(sec.getKeys(false));
    List<String> out = new ArrayList<>(names);
    out.sort(String.CASE_INSENSITIVE_ORDER);
    return out;
  }

  boolean create(Player owner, String group) {
    String key = normalize(group);
    if (owner == null || key == null || DEFAULT_GROUP.equals(key)) return false;
    String path = path(owner.getUniqueId()) + "." + key;
    if (yaml.isList(path)) return false;
    yaml.set(path, new ArrayList<String>());
    save();
    return true;
  }

  boolean delete(Player owner, String group) {
    String key = normalize(group);
    if (owner == null || key == null || DEFAULT_GROUP.equals(key)) return false;
    String path = path(owner.getUniqueId()) + "." + key;
    if (!yaml.contains(path)) return false;
    yaml.set(path, null);
    save();
    return true;
  }

  boolean add(Player owner, String group, FppBot bot) {
    String key = normalize(group);
    if (owner == null || key == null || bot == null || DEFAULT_GROUP.equals(key)) return false;
    if (!api.canControlBot(owner, bot)) return false;
    String path = path(owner.getUniqueId()) + "." + key;
    List<String> names = new ArrayList<>(yaml.getStringList(path));
    if (names.stream().anyMatch(n -> n.equalsIgnoreCase(bot.getName()))) return false;
    names.add(bot.getName());
    names.sort(String.CASE_INSENSITIVE_ORDER);
    yaml.set(path, names);
    save();
    return true;
  }

  boolean remove(Player owner, String group, String botName) {
    String key = normalize(group);
    if (owner == null || key == null || botName == null || DEFAULT_GROUP.equals(key)) return false;
    String path = path(owner.getUniqueId()) + "." + key;
    List<String> names = new ArrayList<>(yaml.getStringList(path));
    boolean removed = names.removeIf(n -> n.equalsIgnoreCase(botName));
    if (!removed) return false;
    yaml.set(path, names);
    save();
    return true;
  }

  List<FppBot> resolve(Player owner, String group) {
    if (owner == null) return List.of();
    String key = normalize(group);
    if (key == null) return List.of();
    List<FppBot> out = new ArrayList<>();
    if (DEFAULT_GROUP.equals(key) || "owned".equals(key)) {
      for (FppBot bot : api.getBots()) {
        if (bot.isOwnedBy(owner.getUniqueId())) out.add(bot);
      }
    } else {
      for (String botName : yaml.getStringList(path(owner.getUniqueId()) + "." + key)) {
        api.getBot(botName).ifPresent(bot -> {
          if (api.canControlBot(owner, bot)) out.add(bot);
        });
      }
    }
    out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()));
    return out;
  }

  static String normalize(String group) {
    if (group == null) return null;
    String key = group.trim().toLowerCase(Locale.ROOT);
    if (!key.matches("[a-z0-9_-]{1,32}")) return null;
    return key;
  }

  private static String path(UUID owner) {
    return "players." + owner;
  }
}
