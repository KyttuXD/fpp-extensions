package me.bill.fppaichat.ai;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class PersonalityRepository {

  private final File dataFolder;
  private final Logger logger;
  private final Map<String, String> personalities = new ConcurrentHashMap<>();

  public PersonalityRepository(File dataFolder, Logger logger) {
    this.dataFolder = dataFolder;
    this.logger = logger;
  }

  public void init() {
    getFolder().mkdirs();
    reload();
  }

  public void reload() {
    personalities.clear();
    File folder = getFolder();
    File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt"));
    if (files == null || files.length == 0) {
      logger.info("[FPP-AIChat] No personality files found in " + folder.getPath() + ".");
      return;
    }

    int loaded = 0;
    for (File file : files) {
      String rawName = file.getName();
      String name = rawName.substring(0, rawName.length() - 4).toLowerCase(Locale.ROOT);
      try {
        String content = Files.readString(file.toPath()).trim();
        if (content.isEmpty()) continue;
        personalities.put(name, content);
        loaded++;
      } catch (IOException e) {
        logger.warning("[FPP-AIChat] Could not read personality " + rawName + ": " + e.getMessage());
      }
    }
    logger.info("[FPP-AIChat] Loaded " + loaded + " personality file(s).");
  }

  public String get(String name) {
    if (name == null) return null;
    return personalities.get(name.toLowerCase(Locale.ROOT));
  }

  public boolean has(String name) {
    return get(name) != null;
  }

  public List<String> getNames() {
    List<String> names = new ArrayList<>(personalities.keySet());
    Collections.sort(names);
    return Collections.unmodifiableList(names);
  }

  public int size() {
    return personalities.size();
  }

  private File getFolder() {
    return new File(dataFolder, "personalities");
  }
}
