package me.bill.fppaichat.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;
import me.bill.fakePlayerPlugin.api.FppBot;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class BotConversationManager {

  private static final int MAX_CONCURRENT_REQUESTS = 3;
  private static final long PER_PLAYER_FLOOD_INTERVAL_MS = 1500;

  private final Plugin plugin;
  private final Logger logger;
  private final YamlConfiguration config;
  private final AIProviderRegistry aiRegistry;
  private final PersonalityRepository personalities;
  private final Semaphore requestSemaphore = new Semaphore(MAX_CONCURRENT_REQUESTS);
  private final Map<UUID, Map<UUID, Deque<AIProvider.ChatMessage>>> conversations =
      new ConcurrentHashMap<>();
  private final Map<UUID, String> botPersonalityText = new ConcurrentHashMap<>();
  private final Map<UUID, String> botPersonalityNames = new ConcurrentHashMap<>();
  private final Map<String, Long> lastResponseTimes = new ConcurrentHashMap<>();
  private final Set<String> inFlightRequests = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Long> playerLastMessageTime = new ConcurrentHashMap<>();

  public BotConversationManager(
      Plugin plugin,
      YamlConfiguration config,
      AIProviderRegistry aiRegistry,
      PersonalityRepository personalities) {
    this.plugin = plugin;
    this.logger = plugin.getLogger();
    this.config = config;
    this.aiRegistry = aiRegistry;
    this.personalities = personalities;
  }

  public void handleDirectMessage(FppBot bot, Player sender, String message) {
    if (!config.getBoolean("direct-messages.enabled", true)) return;
    if (!aiRegistry.isAvailable()) {
      debug("No provider available; cannot respond to DM.");
      return;
    }

    String key = bot.getUuid() + ":" + sender.getUniqueId();
    long now = System.currentTimeMillis();
    long cooldown = config.getInt("direct-messages.cooldown", 3) * 1000L;
    Long lastResponse = lastResponseTimes.get(key);
    if (lastResponse != null && now - lastResponse < cooldown) return;

    Long lastPlayerMessage = playerLastMessageTime.get(sender.getUniqueId());
    if (lastPlayerMessage != null && now - lastPlayerMessage < PER_PLAYER_FLOOD_INTERVAL_MS) {
      debug("Flood guard: ignoring rapid DM from " + sender.getName() + ".");
      return;
    }

    if (!inFlightRequests.add(key)) return;
    if (!requestSemaphore.tryAcquire()) {
      inFlightRequests.remove(key);
      debug("Max concurrent requests reached; dropping DM from " + sender.getName() + ".");
      return;
    }

    lastResponseTimes.put(key, now);
    playerLastMessageTime.put(sender.getUniqueId(), now);

    Map<UUID, Deque<AIProvider.ChatMessage>> botConversations =
        conversations.computeIfAbsent(bot.getUuid(), ignored -> new ConcurrentHashMap<>());
    Deque<AIProvider.ChatMessage> history =
        botConversations.computeIfAbsent(sender.getUniqueId(), ignored -> new ArrayDeque<>());
    history.addLast(new AIProvider.ChatMessage("user", message));

    int maxHistory = config.getInt("direct-messages.max-history", 10);
    while (history.size() > maxHistory * 2) history.pollFirst();

    String personality = resolvePersonality(bot);
    aiRegistry
        .generateResponse(new ArrayList<>(history), bot.getName(), personality)
        .thenAccept(
            response -> {
              long delayTicks = typingDelayTicks(response);
              Bukkit.getScheduler()
                  .runTaskLater(
                      plugin,
                      () -> {
                        if (!sender.isOnline()) return;
                        history.addLast(new AIProvider.ChatMessage("assistant", response));
                        sendBotReply(bot, sender, response);
                        lastResponseTimes.put(key, System.currentTimeMillis());
                        debug(bot.getName() + " -> " + sender.getName() + ": " + response);
                      },
                      delayTicks);
            })
        .exceptionally(
            ex -> {
              logger.warning(
                  "[FPP-AIChat] Failed to generate response for "
                      + bot.getName()
                      + ": "
                      + ex.getMessage());
              return null;
            })
        .whenComplete(
            (ignored, ex) -> {
              inFlightRequests.remove(key);
              requestSemaphore.release();
            });
  }

  public CompletableFuture<String> generatePublicChatReaction(
      FppBot bot, String playerName, String playerMessage) {
    if (!config.getBoolean("public-chat.enabled", false) || !aiRegistry.isAvailable()) {
      return CompletableFuture.failedFuture(new IllegalStateException("AI public chat disabled"));
    }

    String rateKey = "pc-react:" + bot.getUuid();
    long now = System.currentTimeMillis();
    int cooldownSec = config.getInt("public-chat.ai-cooldown", 30);
    Long lastTime = lastResponseTimes.get(rateKey);
    if (lastTime != null && now - lastTime < cooldownSec * 1000L) {
      return CompletableFuture.failedFuture(new IllegalStateException("Rate limited"));
    }
    if (!inFlightRequests.add(rateKey)) {
      return CompletableFuture.failedFuture(new IllegalStateException("Request already in-flight"));
    }
    if (!requestSemaphore.tryAcquire()) {
      inFlightRequests.remove(rateKey);
      return CompletableFuture.failedFuture(new IllegalStateException("Max concurrent requests"));
    }

    lastResponseTimes.put(rateKey, now);
    String personality =
        resolvePersonality(bot)
            + "\n\nCURRENT CONTEXT: You are in a Minecraft server's PUBLIC CHAT. Player "
            + playerName
            + " just said: \""
            + playerMessage
            + "\". React naturally as another player would in game chat. STRICT: 1-8 words max. "
            + "No full sentences. No quotes around your response. Sound like a casual Minecraft "
            + "player, not an AI assistant. Lowercase preferred. Optional: 1 typo.";

    List<AIProvider.ChatMessage> messages =
        List.of(new AIProvider.ChatMessage("user", playerName + ": " + playerMessage));

    return aiRegistry
        .generateResponse(messages, bot.getName(), personality)
        .whenComplete(
            (result, err) -> {
              inFlightRequests.remove(rateKey);
              requestSemaphore.release();
              if (err != null) lastResponseTimes.remove(rateKey);
            });
  }

  public void setBotPersonality(UUID botUuid, String personality) {
    if (personality == null || personality.isBlank()) {
      botPersonalityText.remove(botUuid);
      botPersonalityNames.remove(botUuid);
    } else {
      botPersonalityText.put(botUuid, personality);
      botPersonalityNames.remove(botUuid);
    }
  }

  public boolean setBotPersonalityByName(UUID botUuid, String personalityName) {
    String text = personalities.get(personalityName);
    if (text == null) return false;
    botPersonalityText.put(botUuid, text);
    botPersonalityNames.put(botUuid, personalityName.toLowerCase(Locale.ROOT));
    return true;
  }

  public String getBotPersonalityName(UUID botUuid) {
    return botPersonalityNames.get(botUuid);
  }

  public void clearBotConversations(UUID botUuid) {
    conversations.remove(botUuid);
    botPersonalityText.remove(botUuid);
    botPersonalityNames.remove(botUuid);
    lastResponseTimes.keySet().removeIf(key -> key.startsWith(botUuid.toString()));
  }

  public void clearAll() {
    conversations.clear();
    botPersonalityText.clear();
    botPersonalityNames.clear();
    lastResponseTimes.clear();
    inFlightRequests.clear();
    playerLastMessageTime.clear();
  }

  private String resolvePersonality(FppBot bot) {
    String personality = botPersonalityText.get(bot.getUuid());
    if (personality == null && bot.getAiPersonality() != null) {
      personality = personalities.get(bot.getAiPersonality());
    }
    if (personality == null) {
      String configured = config.getString("personality.default", "default");
      personality = personalities.get(configured);
    }
    if (personality == null || personality.isBlank()) {
      personality =
          "You are {bot_name}, a real Minecraft player chatting on a survival server. "
              + "Reply in 2-6 words max. Lowercase only. Make 1-2 typos per message.";
    }
    return personality.replace("{bot_name}", bot.getName());
  }

  private long typingDelayTicks(String response) {
    if (!config.getBoolean("typing-delay.enabled", true)) return 0L;
    double delaySecs =
        config.getDouble("typing-delay.base", 1.0)
            + response.length() * config.getDouble("typing-delay.per-char", 0.07);
    delaySecs = Math.min(delaySecs, config.getDouble("typing-delay.max", 5.0));
    return Math.max(1L, Math.round(delaySecs * 20.0));
  }

  private void sendBotReply(FppBot bot, Player recipient, String message) {
    Bukkit.dispatchCommand(
        Bukkit.getConsoleSender(),
        "minecraft:tellraw "
            + recipient.getName()
            + " [{\"text\":\"["
            + bot.getName()
            + " -> me] \",\"color\":\"gray\"},{\"text\":\""
            + escapeJson(message)
            + "\",\"color\":\"white\"}]");
  }

  private void debug(String message) {
    if (config.getBoolean("debug", false)) {
      logger.info("[FPP-AIChat] " + message);
    }
  }

  private String escapeJson(String text) {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
