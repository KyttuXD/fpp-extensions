package me.bill.fppchat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import me.bill.fakePlayerPlugin.config.Config;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

final class BotMessageConfig implements Config.ChatMessageProvider {

  private static final String FILE_NAME = "bot-messages.yml";

  private final FppChatExtension extension;
  private FileConfiguration cfg;

  BotMessageConfig(FppChatExtension extension) {
    this.extension = extension;
  }

  @Override
  public void reload() {
    File dataFolder = extension.getDataFolder();
    if (dataFolder == null) {
      cfg = null;
      return;
    }

    File file = new File(dataFolder, FILE_NAME);
    if (!file.exists()) {
      saveDefault(file);
    } else {
      syncMissingKeys(file);
    }

    FileConfiguration disk = YamlConfiguration.loadConfiguration(file);
    disk.options().copyDefaults(true);

    try (InputStream defaults = resourceStream()) {
      if (defaults != null) {
        YamlConfiguration jarDefaults =
            YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaults, StandardCharsets.UTF_8));
        disk.setDefaults(jarDefaults);
      }
    } catch (IOException e) {
      extension.warn("Failed to read bot message defaults: " + e.getMessage());
    }

    cfg = disk;
    Config.debug(
        "[FPP-Chat] Bot message pool loaded: "
            + file.getPath()
            + " ("
            + getMessages().size()
            + " messages)");
  }

  @Override
  public List<String> getMessages() {
    return listOr("messages", fallbackMessages());
  }

  @Override
  public List<String> getReplyMessages() {
    return listOr("replies", fallbackReplies());
  }

  @Override
  public List<String> getBurstMessages() {
    return listOr("burst-followups", fallbackBursts());
  }

  @Override
  public List<String> getJoinReactionMessages() {
    return list("join-reactions");
  }

  @Override
  public List<String> getDeathReactionMessages() {
    return list("death-reactions");
  }

  @Override
  public List<String> getLeaveReactionMessages() {
    return list("leave-reactions");
  }

  @Override
  public List<String> getKeywordReactionMessages(String key) {
    if (key == null) return List.of();
    return list("keyword-reactions." + key.toLowerCase());
  }

  @Override
  public List<String> getBotToBotReplyMessages() {
    List<String> msgs = list("bot-to-bot-replies");
    return msgs.isEmpty() ? getReplyMessages() : msgs;
  }

  @Override
  public List<String> getAdvancementReactionMessages() {
    return list("advancement-reactions");
  }

  @Override
  public List<String> getFirstJoinReactionMessages() {
    List<String> msgs = list("first-join-reactions");
    return msgs.isEmpty() ? getJoinReactionMessages() : msgs;
  }

  @Override
  public List<String> getKillReactionMessages() {
    return list("kill-reactions");
  }

  @Override
  public List<String> getHighLevelReactionMessages() {
    return list("high-level-reactions");
  }

  @Override
  public List<String> getPlayerChatReactionMessages() {
    List<String> msgs = list("player-chat-reactions");
    return msgs.isEmpty() ? getReplyMessages() : msgs;
  }

  private List<String> listOr(String path, List<String> fallback) {
    List<String> msgs = list(path);
    return msgs.isEmpty() ? fallback : msgs;
  }

  private List<String> list(String path) {
    if (cfg == null) return List.of();
    return cfg.getStringList(path);
  }

  private void saveDefault(File file) {
    file.getParentFile().mkdirs();
    try (InputStream in = resourceStream()) {
      if (in == null) {
        extension.warn("Default " + FILE_NAME + " is missing from the extension jar.");
        return;
      }
      Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      extension.warn("Failed to save default " + FILE_NAME + ": " + e.getMessage());
    }
  }

  private void syncMissingKeys(File file) {
    try (InputStream in = resourceStream()) {
      if (in == null) return;

      YamlConfiguration jarCfg =
          YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
      YamlConfiguration diskCfg = YamlConfiguration.loadConfiguration(file);

      List<String> missing = new ArrayList<>();
      for (String key : jarCfg.getKeys(true)) {
        if (jarCfg.isConfigurationSection(key)) continue;
        if (!diskCfg.contains(key)) missing.add(key);
      }
      if (missing.isEmpty()) return;

      for (String key : missing) {
        diskCfg.set(key, jarCfg.get(key));
      }
      diskCfg.save(file);
      extension.info(
          "Synced "
              + FILE_NAME
              + " with "
              + missing.size()
              + " missing key(s): "
              + String.join(", ", missing));
    } catch (IOException | RuntimeException e) {
      extension.warn("Failed to sync " + FILE_NAME + ": " + e.getMessage());
    }
  }

  private InputStream resourceStream() {
    return extension.getClass().getClassLoader().getResourceAsStream(FILE_NAME);
  }

  private static List<String> fallbackMessages() {
    return Collections.unmodifiableList(
        List.of("gg", "let's go!", "hey everyone", "what's up", "nice server"));
  }

  private static List<String> fallbackReplies() {
    return Collections.unmodifiableList(List.of("yeah?", "sup", "what?", "hm?", "here!"));
  }

  private static List<String> fallbackBursts() {
    return Collections.unmodifiableList(List.of("lol", "fr", "ngl", "no cap", "lmao"));
  }
}
