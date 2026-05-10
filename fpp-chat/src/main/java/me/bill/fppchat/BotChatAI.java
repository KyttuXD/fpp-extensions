package me.bill.fppchat;

import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.BotChatController;
import me.bill.fakePlayerPlugin.fakeplayer.BotBroadcast;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import me.bill.fakePlayerPlugin.util.TextUtil;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class BotChatAI implements Listener, BotChatController {

  private final FakePlayerPlugin plugin;
  private final FakePlayerManager manager;

  private static final ThreadLocal<Boolean> isRemoteBroadcast =
      ThreadLocal.withInitial(() -> false);

  private final Map<UUID, Deque<String>> messageHistory = new ConcurrentHashMap<>();

  private final Map<UUID, Integer> taskIds = new ConcurrentHashMap<>();

  private final Map<UUID, Double> activityMultipliers = new ConcurrentHashMap<>();

  private final Map<UUID, Integer> pendingReplyTasks = new ConcurrentHashMap<>();

  private final Map<UUID, Integer> pendingEventTasks = new ConcurrentHashMap<>();

  private final Map<UUID, Integer> muteTaskIds = new ConcurrentHashMap<>();

  private final AtomicLong lastAnyChatMs = new AtomicLong(0L);

  private final AtomicLong lastBotToBotMs = new AtomicLong(0L);

  public BotChatAI(FakePlayerPlugin plugin, FakePlayerManager manager) {
    this.plugin = plugin;
    this.manager = manager;

    me.bill.fakePlayerPlugin.util.AttributionManager.quickAuthorCheck();

    FppScheduler.runSyncRepeating(plugin, this::syncBotLoops, 20L, 20L);
    Bukkit.getPluginManager().registerEvents(this, plugin);
  }

  private void syncBotLoops() {
    if (!Config.fakeChatEnabled()) {
      for (Integer taskId : taskIds.values()) {
        FppScheduler.cancelTask(taskId);
      }
      taskIds.clear();
      for (Integer taskId : pendingReplyTasks.values()) {
        FppScheduler.cancelTask(taskId);
      }
      pendingReplyTasks.clear();
      for (Integer taskId : pendingEventTasks.values()) {
        FppScheduler.cancelTask(taskId);
      }
      pendingEventTasks.clear();
      for (Integer taskId : muteTaskIds.values()) {
        FppScheduler.cancelTask(taskId);
      }
      muteTaskIds.clear();
      return;
    }

    for (FakePlayer fp : manager.getActivePlayers()) {
      if (!taskIds.containsKey(fp.getUuid())) {
        assignActivityMultiplier(fp.getUuid());
        scheduleNext(fp.getUuid());
      }
    }
    taskIds
        .entrySet()
        .removeIf(
            e -> {
              if (manager.getByUuid(e.getKey()) == null) {
                FppScheduler.cancelTask(e.getValue());
                messageHistory.remove(e.getKey());
                activityMultipliers.remove(e.getKey());
                cancelPendingReply(e.getKey());
                cancelPendingEvent(e.getKey());
                cancelMuteTask(e.getKey());
                return true;
              }
              return false;
            });
  }

  private void assignActivityMultiplier(UUID botUuid) {
    FakePlayer fp = manager.getByUuid(botUuid);
    if (fp != null && fp.getChatTier() != null) {
      activityMultipliers.put(botUuid, tierToMultiplier(fp.getChatTier()));
      return;
    }
    if (!Config.fakeChatActivityVariation()) {
      activityMultipliers.put(botUuid, 1.0);
      return;
    }
    double r = ThreadLocalRandom.current().nextDouble();
    double mult;
    if (r < 0.15) mult = 2.0;
    else if (r < 0.40) mult = 1.4;
    else if (r < 0.70) mult = 1.0;
    else if (r < 0.88) mult = 0.7;
    else mult = 0.5;
    activityMultipliers.put(botUuid, mult);
  }

  private static double tierToMultiplier(String tier) {
    return switch (tier.toLowerCase(Locale.ROOT)) {
      case "quiet" -> 2.0;
      case "passive" -> 1.4;
      case "active" -> 0.7;
      case "chatty" -> 0.5;
      default -> 1.0;
    };
  }

  public void setActivityTier(UUID botUuid, String tier) {
    FakePlayer fp = manager.getByUuid(botUuid);
    if (fp == null) return;
    fp.setChatTier(tier);
    if (tier == null) {
      activityMultipliers.remove(botUuid);
      assignActivityMultiplier(botUuid);
    } else {
      activityMultipliers.put(botUuid, tierToMultiplier(tier));
    }
    Config.debugChat(
        "Activity tier for "
            + fp.getName()
            + " set to "
            + (tier != null ? tier : "random")
            + " (mult="
            + activityMultipliers.getOrDefault(botUuid, 1.0)
            + ")");
  }

  private void scheduleNext(UUID botUuid) {
    if (!Config.fakeChatEnabled()) {
      cancelPendingReply(botUuid);
      cancelPendingEvent(botUuid);
      Integer oldTask = taskIds.remove(botUuid);
      if (oldTask != null) FppScheduler.cancelTask(oldTask);
      return;
    }

    double mult = activityMultipliers.getOrDefault(botUuid, 1.0);
    int minTicks = Math.max(20, (int) (Config.fakeChatIntervalMin() * 20 * mult));
    int maxTicks = Math.max(minTicks, (int) (Config.fakeChatIntervalMax() * 20 * mult));

    long delay =
        minTicks == maxTicks
            ? minTicks
            : minTicks + ThreadLocalRandom.current().nextInt(maxTicks - minTicks + 1);

    int id =
        FppScheduler.runSyncLaterWithId(
            plugin,
            () -> {
              fireChat(botUuid);
              if (manager.getByUuid(botUuid) != null) {
                scheduleNext(botUuid);
              } else {
                taskIds.remove(botUuid);
                messageHistory.remove(botUuid);
                activityMultipliers.remove(botUuid);
              }
            },
            delay);

    taskIds.put(botUuid, id);
  }

  private void fireChat(UUID botUuid) {
    if (!Config.fakeChatEnabled()) return;

    FakePlayer bot = manager.getByUuid(botUuid);
    if (bot == null || !bot.isChatEnabled()) return;

    if (Config.fakeChatRequirePlayer() && !hasRealPlayerOnline()) return;
    if (ThreadLocalRandom.current().nextDouble() > Config.fakeChatChance()) return;

    List<String> messages = Config.fakeChatMessages();
    if (messages.isEmpty()) return;

    String message = pickMessage(botUuid, messages, bot);

    int delayTicks = computePreSendDelay();
    if (delayTicks > 0) {
      FppScheduler.runSyncLater(
          plugin,
          () -> {
            sendMessage(bot, message, true);

            triggerBotToBotReaction(bot, 0);
          },
          delayTicks);
    } else {
      sendMessage(bot, message, true);
      triggerBotToBotReaction(bot, 0);
    }
  }

  private void triggerBotToBotReaction(FakePlayer speaker, int chainDepth) {
    if (!Config.fakeChatBotToBotEnabled()) return;
    if (!Config.fakeChatEnabled()) return;
    if (chainDepth >= Config.fakeChatBotToBotMaxChain()) return;

    long cooldownMs = Config.fakeChatBotToBotCooldown() * 1000L;
    long now = System.currentTimeMillis();
    if (cooldownMs > 0 && (now - lastBotToBotMs.get()) < cooldownMs) return;

    double chance =
        (chainDepth == 0)
            ? Config.fakeChatBotToBotReplyChance()
            : Config.fakeChatBotToBotChainChance();
    if (ThreadLocalRandom.current().nextDouble() > chance) return;

    List<FakePlayer> eligible = new ArrayList<>();
    UUID speakerUuidEarly = speaker.getUuid();
    for (FakePlayer fp : manager.getActivePlayers()) {
      if (!fp.getUuid().equals(speakerUuidEarly) && fp.isChatEnabled()) {
        eligible.add(fp);
      }
    }
    if (eligible.isEmpty()) return;
    if (Config.fakeChatRequirePlayer() && !hasRealPlayerOnline()) return;

    FakePlayer replier = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));

    lastBotToBotMs.set(now);

    int minTicks = Math.max(20, Config.fakeChatBotToBotDelayMin() * 20);
    int maxTicks = Math.max(minTicks, Config.fakeChatBotToBotDelayMax() * 20);
    long delay =
        minTicks + ThreadLocalRandom.current().nextInt(Math.max(1, maxTicks - minTicks + 1));

    final UUID speakerUuid = speaker.getUuid();
    final UUID replierUuid = replier.getUuid();
    final int nextDepth = chainDepth + 1;

    FppScheduler.runSyncLater(
        plugin,
        () -> {
          if (!Config.fakeChatEnabled() || !Config.fakeChatBotToBotEnabled()) return;
          FakePlayer s = manager.getByUuid(speakerUuid);
          FakePlayer b = manager.getByUuid(replierUuid);
          if (b == null || !b.isChatEnabled()) return;
          if (Config.fakeChatRequirePlayer() && !hasRealPlayerOnline()) return;

          List<String> pool = Config.chatBotToBotReplyMessages();
          if (pool.isEmpty()) pool = Config.chatReplyMessages();
          if (pool.isEmpty()) pool = Config.fakeChatMessages();
          if (pool.isEmpty()) return;

          String speakerName = (s != null) ? s.getName() : b.getName();
          String raw = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));

          String msg = resolvePlaceholders(raw.replace("{random_player}", speakerName), b);

          lastBotToBotMs.set(System.currentTimeMillis());
          sendMessage(b, msg, false);
          Config.debugChat(
              "[b2b chain="
                  + nextDepth
                  + "] "
                  + b.getName()
                  + " → "
                  + speakerName
                  + ": "
                  + msg);

          triggerBotToBotReaction(b, nextDepth);
        },
        delay);
  }

  private String pickMessage(UUID botUuid, List<String> pool, FakePlayer bot) {
    Deque<String> history = messageHistory.computeIfAbsent(botUuid, k -> new ArrayDeque<>());
    int historySize = Math.max(1, Config.fakeChatHistorySize());

    List<String> available = new ArrayList<>(pool);
    available.removeAll(history);
    if (available.isEmpty()) available = new ArrayList<>(pool);

    String raw = available.get(ThreadLocalRandom.current().nextInt(available.size()));

    history.addLast(raw);
    while (history.size() > historySize) history.pollFirst();

    return resolvePlaceholders(raw, bot);
  }

  private String resolvePlaceholders(String raw, FakePlayer bot) {
    String s =
        raw.replace("{name}", bot.getName()).replace("{random_player}", resolveRandomPlayer(bot));

    if (s.contains("{bot_name}")) {
      s = s.replace("{bot_name}", resolveRandomBotName(bot));
    }

    if (s.contains("{online}")) {
      int realCount = 0;
      for (Player p : Bukkit.getOnlinePlayers()) {
        if (manager.getByUuid(p.getUniqueId()) == null) realCount++;
      }
      s = s.replace("{online}", String.valueOf(realCount));
    }

    if (s.contains("{server}")) {
      s = s.replace("{server}", Config.serverId());
    }

    if (s.contains("{date}") || s.contains("{day}")) {
      LocalDate today = LocalDate.now();
      if (s.contains("{date}")) s = s.replace("{date}", today.toString());
      if (s.contains("{day}"))
        s = s.replace("{day}", today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ROOT));
    }

    if (s.contains("{world}")
        || s.contains("{time}")
        || s.contains("{biome}")
        || s.contains("{x}")
        || s.contains("{y}")
        || s.contains("{z}")) {
      Location loc = bot.getLiveLocation();
      if (loc != null && loc.getWorld() != null) {
        if (s.contains("{world}")) s = s.replace("{world}", loc.getWorld().getName());
        if (s.contains("{time}")) {
          String timeLabel = (loc.getWorld().getTime() < 12300) ? "day" : "night";
          s = s.replace("{time}", timeLabel);
        }
        if (s.contains("{biome}")) {
          try {
            String biome = loc.getBlock().getBiome().getKey().getKey().replace('_', ' ');
            s = s.replace("{biome}", biome);
          } catch (Throwable t) {
            s = s.replace("{biome}", "unknown");
          }
        }
        if (s.contains("{x}")) s = s.replace("{x}", String.valueOf((int) loc.getX()));
        if (s.contains("{y}")) s = s.replace("{y}", String.valueOf((int) loc.getY()));
        if (s.contains("{z}")) s = s.replace("{z}", String.valueOf((int) loc.getZ()));
      } else {
        s =
            s.replace("{world}", "unknown")
                .replace("{time}", "day")
                .replace("{biome}", "unknown")
                .replace("{x}", "?")
                .replace("{y}", "?")
                .replace("{z}", "?");
      }
    }
    return s;
  }

  private int computePreSendDelay() {
    int staggerTicks = 0;
    int staggerSec = Config.fakeChatStaggerInterval();
    if (staggerSec > 0) {
      long now = System.currentTimeMillis();
      long minGapMs = staggerSec * 1000L;
      long nextSend;
      while (true) {
        long last = lastAnyChatMs.get();
        nextSend = Math.max(now, last + minGapMs);
        if (lastAnyChatMs.compareAndSet(last, nextSend)) break;
      }
      long extraMs = nextSend - now;
      if (extraMs > 0) staggerTicks = (int) (extraMs / 50L) + 1;
    }

    int typingTicks = 0;
    if (Config.fakeChatTypingDelay()) {
      typingTicks = ThreadLocalRandom.current().nextInt(50);
    }

    return staggerTicks + typingTicks;
  }

  private void sendMessage(FakePlayer bot, String message, boolean allowBurst) {
    if (manager.getByUuid(bot.getUuid()) == null) return;
    if (!Config.fakeChatEnabled()) return;
    if (!bot.isChatEnabled()) return;
    sendMessageForced(bot, message, allowBurst);
  }

  private void sendMessageForced(FakePlayer bot, String message, boolean allowBurst) {
    if (manager.getByUuid(bot.getUuid()) == null) return;

    int latencyDelayTicks = 0;
    if (Config.pingLatencyEffect() && Config.pingEnabled()) {
      int pingMs = bot.getEffectivePing();
      if (pingMs > 0) {
        latencyDelayTicks = Math.max(1, pingMs / 50);
      }
    }

    if (latencyDelayTicks > 0) {
      String prefix = "";
      String suffix = "";
      final UUID botUuid = bot.getUuid();
      final String fPrefix = prefix;
      final String fSuffix = suffix;
      FppScheduler.runSyncLater(plugin, () -> {
        FakePlayer fp = manager.getByUuid(botUuid);
        if (fp == null) return;
        doSendMessage(fp, message, fPrefix, fSuffix);
      }, latencyDelayTicks);
    } else {
      String prefix = "";
      String suffix = "";
      doSendMessage(bot, message, prefix, suffix);
    }

    if (allowBurst) {
      double burstChance = Config.fakeChatBurstChance();
      if (burstChance > 0 && ThreadLocalRandom.current().nextDouble() < burstChance) {
        scheduleBurst(bot);
      }
    }
  }

  private void doSendMessage(FakePlayer bot, String message, String prefix, String suffix) {
    if (manager.getByUuid(bot.getUuid()) == null) return;

    boolean sentViaPlayerChat = false;
    Player playerEntity = bot.getPlayer();
    if (playerEntity != null && !bot.isBodyless()) {
      BotBroadcast.dispatchChat(playerEntity, message);
      sentViaPlayerChat = true;
    }
    if (!sentViaPlayerChat) {
      BotBroadcast.broadcastFormatted(bot.getDisplayName(), message);
    }
    Config.debugChat(bot.getName() + " said: " + message);

    var vc = plugin.getVelocityChannel();
    if (vc != null) {
      vc.sendChatToNetwork(bot.getName(), bot.getDisplayName(), message, prefix, suffix);
    }
  }

  private static void broadcastFormatted(String displayName, String message) {
    try {
      String format =
          Config.fakeChatRemoteFormat()
              .replace("{name}", displayName)
              .replace("{message}", message);
      Bukkit.getServer().broadcast(MiniMessage.miniMessage().deserialize(format));
    } catch (Throwable t) {
      Bukkit.getServer()
          .broadcast(
              Component.text("<")
                  .append(TextUtil.colorize(displayName))
                  .append(Component.text("> "))
                  .append(Component.text(message)));
    }
  }

  private void scheduleBurst(FakePlayer bot) {
    List<String> pool = Config.chatBurstMessages();
    if (pool.isEmpty()) return;

    int minTicks = Math.max(20, Config.fakeChatBurstDelayMin() * 20);
    int maxTicks = Math.max(minTicks, Config.fakeChatBurstDelayMax() * 20);
    long delay =
        minTicks == maxTicks
            ? minTicks
            : minTicks + ThreadLocalRandom.current().nextInt(maxTicks - minTicks + 1);

    String raw = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    String msg = resolvePlaceholders(raw, bot);

    FppScheduler.runSyncLater(plugin, () -> sendMessage(bot, msg, false), delay);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlayerChat(AsyncChatEvent event) {
    if (!Config.fakeChatEnabled()) return;

    if (manager.getByUuid(event.getPlayer().getUniqueId()) != null) return;

    final String plainText;
    try {
      plainText =
          PlainTextComponentSerializer.plainText()
              .serialize(event.message())
              .toLowerCase(Locale.ROOT);
    } catch (Throwable t) {
      return;
    }

    if (Config.fakeChatReplyToMentions()) {
      List<UUID> candidates = new ArrayList<>();
      for (FakePlayer fp : manager.getActivePlayers()) {
        if (fp.isChatEnabled() && plainText.contains(fp.getName().toLowerCase(Locale.ROOT))) {
          candidates.add(fp.getUuid());
        }
      }
      if (!candidates.isEmpty()) {
        FppScheduler.runSync(
            plugin,
            () -> {
              for (UUID uuid : candidates) {
                if (!pendingReplyTasks.containsKey(uuid)) {
                  scheduleMentionReply(uuid);
                }
              }
            });
      }
    }

    if (Config.fakeChatKeywordReactionsEnabled()) {
      Map<String, String> keywordMap = Config.fakeChatKeywordMap();
      if (!keywordMap.isEmpty()) {
        List<String> matchedPools = new ArrayList<>();
        for (Map.Entry<String, String> entry : keywordMap.entrySet()) {
          if (plainText.contains(entry.getKey())) matchedPools.add(entry.getValue());
        }
        if (!matchedPools.isEmpty()) {
          String poolKey =
              matchedPools.get(ThreadLocalRandom.current().nextInt(matchedPools.size()));
          FppScheduler.runSync(plugin, () -> scheduleKeywordReaction(poolKey));
        }
      }
    }

    if (Config.fakeChatOnPlayerChatEnabled() && Config.fakeChatEventTriggersEnabled()) {

      if (Config.fakeChatOnPlayerChatIgnoreShort() && plainText.length() < 3) return;

      if (Config.fakeChatOnPlayerChatIgnoreCommands() && plainText.startsWith("/")) return;

      final String senderName = event.getPlayer().getName();

      final String originalMessage;
      try {
        originalMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
      } catch (Throwable t) {
        return;
      }

      FppScheduler.runSync(plugin, () -> schedulePlayerChatReaction(senderName, originalMessage));
    }
  }

  private void scheduleMentionReply(UUID botUuid) {
    FakePlayer bot = manager.getByUuid(botUuid);
    if (bot == null || !bot.isChatEnabled()) return;
    if (ThreadLocalRandom.current().nextDouble() > Config.fakeChatMentionReplyChance()) return;

    int minTicks = Math.max(20, Config.fakeChatReplyDelayMin() * 20);
    int maxTicks = Math.max(minTicks, Config.fakeChatReplyDelayMax() * 20);
    long delay =
        minTicks == maxTicks
            ? minTicks
            : minTicks + ThreadLocalRandom.current().nextInt(maxTicks - minTicks + 1);

    int taskId =
        FppScheduler.runSyncLaterWithId(
            plugin,
            () -> {
              pendingReplyTasks.remove(botUuid);
              FakePlayer b = manager.getByUuid(botUuid);
              if (b == null || !b.isChatEnabled() || !hasRealPlayerOnline()) return;

              List<String> pool = Config.chatReplyMessages();
              if (pool.isEmpty()) pool = Config.fakeChatMessages();
              if (pool.isEmpty()) return;

              String raw = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
              String reply = resolvePlaceholders(raw, b);
              sendMessage(b, reply, false);
              Config.debugChat(b.getName() + " replied to mention → " + reply);
            },
            delay);

    pendingReplyTasks.put(botUuid, taskId);
  }

  private void scheduleKeywordReaction(String poolKey) {
    if (!Config.fakeChatEnabled()) return;
    List<String> pool = Config.chatKeywordReactionMessages(poolKey);
    if (pool.isEmpty()) return;

    List<FakePlayer> eligible = new ArrayList<>();
    for (FakePlayer fp : manager.getActivePlayers()) {
      if (fp.isChatEnabled()) eligible.add(fp);
    }
    if (eligible.isEmpty()) return;
    if (Config.fakeChatRequirePlayer() && !hasRealPlayerOnline()) return;

    FakePlayer bot = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
    int delayTicks = ThreadLocalRandom.current().nextInt(60) + 20;
    FppScheduler.runSyncLater(
        plugin,
        () -> {
          FakePlayer b = manager.getByUuid(bot.getUuid());
          if (b == null || !b.isChatEnabled()) return;
          String raw = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
          sendMessage(b, resolvePlaceholders(raw, b), false);
          Config.debugChat(b.getName() + " keyword-reaction [" + poolKey + "]");
        },
        delayTicks);
  }

  private void schedulePlayerChatReaction(String senderName, String originalMessage) {
    if (!Config.fakeChatEnabled()) return;

    List<FakePlayer> eligible = new ArrayList<>();
    for (FakePlayer fp : manager.getActivePlayers()) {
      if (fp.isChatEnabled() && !pendingReplyTasks.containsKey(fp.getUuid())) {
        eligible.add(fp);
      }
    }
    if (eligible.isEmpty()) return;

    int maxBots = Math.max(1, Config.fakeChatOnPlayerChatMaxBots());
    double chance = Config.fakeChatOnPlayerChatChance();
    double mentionChance = Config.fakeChatOnPlayerChatMentionChance();

    List<FakePlayer> shuffled = new ArrayList<>(eligible);
    java.util.Collections.shuffle(shuffled);

    int scheduled = 0;
    for (FakePlayer bot : shuffled) {
      if (scheduled >= maxBots) break;
      if (ThreadLocalRandom.current().nextDouble() > chance) continue;

      final UUID botUuid = bot.getUuid();
      int minTicks = Math.max(20, Config.fakeChatOnPlayerChatDelayMin() * 20);
      int maxTicks = Math.max(minTicks, Config.fakeChatOnPlayerChatDelayMax() * 20);
      long jitter =
          minTicks == maxTicks ? 0 : ThreadLocalRandom.current().nextInt(maxTicks - minTicks + 1);

      long stagger = scheduled * (10L + ThreadLocalRandom.current().nextInt(10));
      long delay = minTicks + jitter + stagger;

      final boolean doMention = ThreadLocalRandom.current().nextDouble() < mentionChance;
      FppScheduler.runSyncLater(
          plugin,
          () -> sendStaticPlayerChatReaction(botUuid, senderName, doMention ? 1.0 : 0.0),
          delay);

      scheduled++;
    }
  }

  private void sendStaticPlayerChatReaction(UUID botUuid, String senderName, double mentionChance) {
    if (!Config.fakeChatEnabled()) return;
    FakePlayer b = manager.getByUuid(botUuid);
    if (b == null || !b.isChatEnabled()) return;
    if (Config.fakeChatRequirePlayer() && !hasRealPlayerOnline()) return;

    List<String> pool = Config.chatPlayerChatReactionMessages();
    if (pool.isEmpty()) return;

    boolean doMention = ThreadLocalRandom.current().nextDouble() < mentionChance;
    List<String> filtered;
    if (doMention) {
      filtered = pool.stream().filter(m -> m.contains("{player_name}")).toList();
      if (filtered.isEmpty()) filtered = pool;
    } else {
      filtered = pool.stream().filter(m -> !m.contains("{player_name}")).toList();
      if (filtered.isEmpty()) filtered = pool;
    }

    String raw = filtered.get(ThreadLocalRandom.current().nextInt(filtered.size()));
    String resolved = resolvePlaceholders(raw.replace("{player_name}", senderName), b);
    sendMessage(b, resolved, false);
    Config.debugChat(
        b.getName() + " static-player-chat-reaction → " + senderName + ": " + resolved);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerJoin(PlayerJoinEvent event) {
    if (!Config.fakeChatEnabled()) return;
    if (!Config.fakeChatEventTriggersEnabled()) return;
    if (manager.getByUuid(event.getPlayer().getUniqueId()) != null) return;

    boolean isFirstJoin = !event.getPlayer().hasPlayedBefore();

    final List<String> pool;
    final double chance;
    if (isFirstJoin && Config.fakeChatOnFirstJoinEnabled()) {
      List<String> fjPool = Config.chatFirstJoinReactionMessages();
      pool = fjPool.isEmpty() ? Config.chatJoinReactionMessages() : fjPool;
      chance = Config.fakeChatOnFirstJoinChance();
    } else {
      if (!Config.fakeChatOnJoinEnabled()) return;
      pool = Config.chatJoinReactionMessages();
      chance = Config.fakeChatOnJoinChance();
    }
    if (pool.isEmpty()) return;
    if (ThreadLocalRandom.current().nextDouble() > chance) return;

    List<FakePlayer> eligible =
        manager.getActivePlayers().stream().filter(FakePlayer::isChatEnabled).toList();
    if (eligible.isEmpty()) return;

    FakePlayer bot = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
    final String joinerName = event.getPlayer().getName();

    int minTicks = Math.max(20, Config.fakeChatOnJoinDelayMin() * 20);
    int maxTicks = Math.max(minTicks, Config.fakeChatOnJoinDelayMax() * 20);
    long delay =
        minTicks + ThreadLocalRandom.current().nextInt(Math.max(1, maxTicks - minTicks + 1));

    int taskId =
        FppScheduler.runSyncLaterWithId(
            plugin,
            () -> {
                  pendingEventTasks.remove(bot.getUuid());
                  FakePlayer b = manager.getByUuid(bot.getUuid());
                  if (b == null || !b.isChatEnabled()) return;
                  if (Config.fakeChatRequirePlayer() && !hasRealPlayerOnline()) return;

                  String raw = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                  String msg = resolvePlaceholders(raw.replace("{random_player}", joinerName), b);
                  sendMessage(b, msg, false);
                  Config.debugChat(
                      b.getName()
                          + (isFirstJoin ? " first-join-reaction" : " join-reaction")
                          + " for "
                          + joinerName
                          + ": "
                          + msg);

                  if (isFirstJoin
                      && manager.getActivePlayers().size() >= 2
                      && ThreadLocalRandom.current().nextDouble() < 0.50) {
                    List<FakePlayer> others =
                        manager.getActivePlayers().stream()
                            .filter(FakePlayer::isChatEnabled)
                            .filter(fp -> !fp.getUuid().equals(b.getUuid()))
                            .toList();
                    if (!others.isEmpty()) {
                      FakePlayer second =
                          others.get(ThreadLocalRandom.current().nextInt(others.size()));
                      long extraDelay = 20L + ThreadLocalRandom.current().nextInt(60);
                      FppScheduler.runSyncLater(
                          plugin,
                          () -> {
                            FakePlayer sb = manager.getByUuid(second.getUuid());
                            if (sb == null || !sb.isChatEnabled()) return;
                            List<String> p2 = Config.chatJoinReactionMessages();
                            if (p2.isEmpty()) return;
                            String r2 = p2.get(ThreadLocalRandom.current().nextInt(p2.size()));
                            String m2 =
                                resolvePlaceholders(r2.replace("{random_player}", joinerName), sb);
                            sendMessage(sb, m2, false);
                          },
                          extraDelay);
                    }
                  }
                },
            delay);

    pendingEventTasks.put(bot.getUuid(), taskId);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityDeath(EntityDeathEvent event) {
    if (!Config.fakeChatEnabled()) return;
    if (!Config.fakeChatEventTriggersEnabled()) return;
    if (!Config.fakeChatOnDeathEnabled()) return;

    Entity deceased = event.getEntity();
    if (Config.fakeChatOnDeathPlayersOnly() && !(deceased instanceof Player)) return;
    if (ThreadLocalRandom.current().nextDouble() > Config.fakeChatOnDeathChance()) return;

    List<String> pool = Config.chatDeathReactionMessages();
    if (pool.isEmpty()) return;

    List<FakePlayer> eligible =
        manager.getActivePlayers().stream()
            .filter(FakePlayer::isChatEnabled)
            .filter(fp -> !pendingEventTasks.containsKey(fp.getUuid()))
            .toList();
    if (eligible.isEmpty()) return;
    if (Config.fakeChatRequirePlayer() && !hasRealPlayerOnline()) return;

    FakePlayer bot = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
    final String deceasedName = deceased.getName();

    int minTicks = Math.max(20, Config.fakeChatOnDeathDelayMin() * 20);
    int maxTicks = Math.max(minTicks, Config.fakeChatOnDeathDelayMax() * 20);
    long delay =
        minTicks + ThreadLocalRandom.current().nextInt(Math.max(1, maxTicks - minTicks + 1));

    int taskId =
        FppScheduler.runSyncLaterWithId(
            plugin,
            () -> {
                  pendingEventTasks.remove(bot.getUuid());
                  FakePlayer b = manager.getByUuid(bot.getUuid());
                  if (b == null || !b.isChatEnabled()) return;
                  String raw = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                  String msg =
                      resolvePlaceholders(
                          raw.replace("{random_player}", deceasedName)
                              .replace("{victim}", deceasedName),
                          b);
                  sendMessage(b, msg, false);
                  Config.debugChat(
                      b.getName() + " death-reaction for " + deceasedName + ": " + msg);
                },
            delay);

    pendingEventTasks.put(bot.getUuid(), taskId);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlayerKill(PlayerDeathEvent event) {
    if (!Config.fakeChatEnabled()) return;
    if (!Config.fakeChatEventTriggersEnabled()) return;
    if (!Config.fakeChatOnKillEnabled()) return;

    Player killer = event.getEntity().getKiller();
    if (killer == null) return;

    if (manager.getByUuid(killer.getUniqueId()) != null) return;
    if (manager.getByUuid(event.getEntity().getUniqueId()) != null) return;

    if (ThreadLocalRandom.current().nextDouble() > Config.fakeChatOnKillChance()) return;

    List<String> pool = Config.chatKillReactionMessages();
    if (pool.isEmpty()) return;

    List<FakePlayer> eligible =
        manager.getActivePlayers().stream()
            .filter(FakePlayer::isChatEnabled)
            .filter(fp -> !pendingEventTasks.containsKey(fp.getUuid()))
            .toList();
    if (eligible.isEmpty()) return;

    FakePlayer bot = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
    final String killerName = killer.getName();
    final String victimName = event.getEntity().getName();

    int minTicks = Math.max(20, Config.fakeChatOnKillDelayMin() * 20);
    int maxTicks = Math.max(minTicks, Config.fakeChatOnKillDelayMax() * 20);
    long delay =
        minTicks + ThreadLocalRandom.current().nextInt(Math.max(1, maxTicks - minTicks + 1));

    int taskId =
        FppScheduler.runSyncLaterWithId(
            plugin,
            () -> {
                  pendingEventTasks.remove(bot.getUuid());
                  FakePlayer b = manager.getByUuid(bot.getUuid());
                  if (b == null || !b.isChatEnabled()) return;
                  if (Config.fakeChatRequirePlayer() && !hasRealPlayerOnline()) return;
                  String raw = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                  String msg =
                      resolvePlaceholders(
                          raw.replace("{random_player}", killerName)
                              .replace("{killer}", killerName)
                              .replace("{victim}", victimName),
                          b);
                  sendMessage(b, msg, false);
                  Config.debugChat(
                      b.getName()
                          + " kill-reaction: "
                          + killerName
                          + " killed "
                          + victimName
                          + ": "
                          + msg);
                },
            delay);

    pendingEventTasks.put(bot.getUuid(), taskId);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
    if (!Config.fakeChatEnabled()) return;
    if (!Config.fakeChatEventTriggersEnabled()) return;
    if (!Config.fakeChatOnAdvancementEnabled()) return;
    if (manager.getByUuid(event.getPlayer().getUniqueId()) != null) return;

    AdvancementDisplay display = event.getAdvancement().getDisplay();
    if (display == null || !display.doesAnnounceToChat()) return;

    if (ThreadLocalRandom.current().nextDouble() > Config.fakeChatOnAdvancementChance()) return;

    List<String> pool = Config.chatAdvancementReactionMessages();
    if (pool.isEmpty()) return;

    List<FakePlayer> eligible =
        manager.getActivePlayers().stream()
            .filter(FakePlayer::isChatEnabled)
            .filter(fp -> !pendingEventTasks.containsKey(fp.getUuid()))
            .toList();
    if (eligible.isEmpty()) return;

    FakePlayer bot = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
    final String playerName = event.getPlayer().getName();
    final String advTitle;
    try {
      advTitle = PlainTextComponentSerializer.plainText().serialize(display.title());
    } catch (Throwable t) {
      return;
    }

    int minTicks = Math.max(20, Config.fakeChatOnAdvancementDelayMin() * 20);
    int maxTicks = Math.max(minTicks, Config.fakeChatOnAdvancementDelayMax() * 20);
    long delay =
        minTicks + ThreadLocalRandom.current().nextInt(Math.max(1, maxTicks - minTicks + 1));

    int taskId =
        FppScheduler.runSyncLaterWithId(
            plugin,
            () -> {
                  pendingEventTasks.remove(bot.getUuid());
                  FakePlayer b = manager.getByUuid(bot.getUuid());
                  if (b == null || !b.isChatEnabled()) return;
                  if (Config.fakeChatRequirePlayer() && !hasRealPlayerOnline()) return;
                  String raw = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                  String msg =
                      resolvePlaceholders(
                          raw.replace("{random_player}", playerName)
                              .replace("{advancement}", advTitle),
                          b);
                  sendMessage(b, msg, false);
                  Config.debugChat(
                      b.getName()
                          + " advancement-reaction ["
                          + advTitle
                          + "] for "
                          + playerName
                          + ": "
                          + msg);
                },
            delay);

    pendingEventTasks.put(bot.getUuid(), taskId);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerLevelChange(PlayerLevelChangeEvent event) {
    if (!Config.fakeChatEnabled()) return;
    if (!Config.fakeChatEventTriggersEnabled()) return;
    if (!Config.fakeChatOnHighLevelEnabled()) return;
    if (manager.getByUuid(event.getPlayer().getUniqueId()) != null) return;

    int newLevel = event.getNewLevel();
    int minLevel = Config.fakeChatOnHighLevelMinLevel();
    if (newLevel < minLevel) return;

    boolean isMilestone = (newLevel == minLevel) || (newLevel >= 50 && newLevel % 25 == 0);
    if (!isMilestone) return;

    if (ThreadLocalRandom.current().nextDouble() > Config.fakeChatOnHighLevelChance()) return;

    List<String> pool = Config.chatHighLevelReactionMessages();
    if (pool.isEmpty()) return;

    List<FakePlayer> eligible =
        manager.getActivePlayers().stream()
            .filter(FakePlayer::isChatEnabled)
            .filter(fp -> !pendingEventTasks.containsKey(fp.getUuid()))
            .toList();
    if (eligible.isEmpty()) return;

    FakePlayer bot = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
    final String playerName = event.getPlayer().getName();
    final String levelStr = String.valueOf(newLevel);

    int minTicks = Math.max(20, Config.fakeChatOnHighLevelDelayMin() * 20);
    int maxTicks = Math.max(minTicks, Config.fakeChatOnHighLevelDelayMax() * 20);
    long delay =
        minTicks + ThreadLocalRandom.current().nextInt(Math.max(1, maxTicks - minTicks + 1));

    int taskId =
        FppScheduler.runSyncLaterWithId(
            plugin,
            () -> {
                  pendingEventTasks.remove(bot.getUuid());
                  FakePlayer b = manager.getByUuid(bot.getUuid());
                  if (b == null || !b.isChatEnabled()) return;
                  if (Config.fakeChatRequirePlayer() && !hasRealPlayerOnline()) return;
                  String raw = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                  String msg =
                      resolvePlaceholders(
                          raw.replace("{random_player}", playerName).replace("{level}", levelStr),
                          b);
                  sendMessage(b, msg, false);
                  Config.debugChat(
                      b.getName()
                          + " level-reaction [lvl "
                          + levelStr
                          + "] for "
                          + playerName
                          + ": "
                          + msg);
                },
            delay);

    pendingEventTasks.put(bot.getUuid(), taskId);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerQuit(PlayerQuitEvent event) {
    if (!Config.fakeChatEnabled()) return;
    if (!Config.fakeChatEventTriggersEnabled()) return;
    if (!Config.fakeChatOnLeaveEnabled()) return;
    if (manager.getByUuid(event.getPlayer().getUniqueId()) != null) return;
    if (ThreadLocalRandom.current().nextDouble() > Config.fakeChatOnLeaveChance()) return;

    List<String> pool = Config.chatLeaveReactionMessages();
    if (pool.isEmpty()) return;

    List<FakePlayer> eligible =
        manager.getActivePlayers().stream().filter(FakePlayer::isChatEnabled).toList();
    if (eligible.isEmpty()) return;

    FakePlayer bot = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
    final String leaverName = event.getPlayer().getName();

    int minTicks = Math.max(20, Config.fakeChatOnLeaveDelayMin() * 20);
    int maxTicks = Math.max(minTicks, Config.fakeChatOnLeaveDelayMax() * 20);
    long delay =
        minTicks + ThreadLocalRandom.current().nextInt(Math.max(1, maxTicks - minTicks + 1));

    int taskId =
        FppScheduler.runSyncLaterWithId(
            plugin,
            () -> {
              pendingEventTasks.remove(bot.getUuid());
              FakePlayer b = manager.getByUuid(bot.getUuid());
              if (b == null || !b.isChatEnabled()) return;
              if (Config.fakeChatRequirePlayer() && !hasRealPlayerOnline()) return;
              String raw = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
              String msg = resolvePlaceholders(raw.replace("{random_player}", leaverName), b);
              sendMessage(b, msg, false);
              Config.debugChat(b.getName() + " leave-reaction for " + leaverName + ": " + msg);
            },
            delay);

    pendingEventTasks.put(bot.getUuid(), taskId);
  }

  private void cancelPendingReply(UUID botUuid) {
    Integer taskId = pendingReplyTasks.remove(botUuid);
    if (taskId != null) FppScheduler.cancelTask(taskId);
  }

  private void cancelPendingEvent(UUID botUuid) {
    Integer taskId = pendingEventTasks.remove(botUuid);
    if (taskId != null) FppScheduler.cancelTask(taskId);
  }

  private void cancelMuteTask(UUID botUuid) {
    Integer taskId = muteTaskIds.remove(botUuid);
    if (taskId != null) FppScheduler.cancelTask(taskId);
  }

  public void restartLoops() {
    taskIds.values().forEach(FppScheduler::cancelTask);
    taskIds.clear();
    for (FakePlayer fp : manager.getActivePlayers()) {
      assignActivityMultiplier(fp.getUuid());
      scheduleNext(fp.getUuid());
    }
    Config.debugChat(
        "BotChatAI loops restarted - interval "
            + Config.fakeChatIntervalMin()
            + "–"
            + Config.fakeChatIntervalMax()
            + "s, "
            + "stagger "
            + Config.fakeChatStaggerInterval()
            + "s, "
            + "b2b "
            + (Config.fakeChatBotToBotEnabled() ? "on" : "off"));
  }

  public void cancelAll() {
    taskIds.values().forEach(FppScheduler::cancelTask);
    pendingReplyTasks.values().forEach(FppScheduler::cancelTask);
    pendingEventTasks.values().forEach(FppScheduler::cancelTask);
    muteTaskIds.values().forEach(FppScheduler::cancelTask);
    taskIds.clear();
    messageHistory.clear();
    activityMultipliers.clear();
    pendingReplyTasks.clear();
    pendingEventTasks.clear();
    muteTaskIds.clear();
  }

  public void stopAllLoopsNow() {
    taskIds.values().forEach(FppScheduler::cancelTask);
    pendingReplyTasks.values().forEach(FppScheduler::cancelTask);
    pendingEventTasks.values().forEach(FppScheduler::cancelTask);
    taskIds.clear();
    pendingReplyTasks.clear();
    pendingEventTasks.clear();
    for (FakePlayer fp : manager.getActivePlayers()) {
      scheduleNext(fp.getUuid());
    }
  }

  public void forceSendMessage(FakePlayer bot, String message) {
    if (manager.getByUuid(bot.getUuid()) == null) return;
    sendMessageForced(bot, message, false);
  }

  public void forceSendMessageResolved(FakePlayer bot, String message) {
    if (manager.getByUuid(bot.getUuid()) == null) return;
    sendMessageForced(bot, resolvePlaceholders(message, bot), false);
  }

  public double getActivityMultiplier(UUID botUuid) {
    return activityMultipliers.getOrDefault(botUuid, 1.0);
  }

  public void timedMute(UUID botUuid, int seconds) {
    FakePlayer fp = manager.getByUuid(botUuid);
    if (fp == null) return;

    Integer prev = muteTaskIds.remove(botUuid);
    if (prev != null) FppScheduler.cancelTask(prev);

    fp.setChatEnabled(false);
    Config.debugChat(
        fp.getName() + " muted" + (seconds > 0 ? " for " + seconds + "s" : " permanently"));

    if (seconds > 0) {
      int taskId =
          FppScheduler.runSyncLaterWithId(
              plugin,
              () -> {
                muteTaskIds.remove(botUuid);
                FakePlayer b = manager.getByUuid(botUuid);
                if (b != null) {
                  b.setChatEnabled(true);
                  Config.debugChat(b.getName() + " mute expired - chat re-enabled");
                }
              },
              (long) seconds * 20L);
      muteTaskIds.put(botUuid, taskId);
    }
  }

  public static void dispatchChat(Player player, String rawMessage) {
    // Fire API chat event — addons can cancel or mutate the message.
    FakePlayerPlugin pluginInst = FakePlayerPlugin.getInstance();
    if (pluginInst != null) {
      var fppApi = pluginInst.getFppApi();
      if (fppApi != null) {
        FakePlayer fp = pluginInst.getFakePlayerManager() != null
            ? pluginInst.getFakePlayerManager().getByUuid(player.getUniqueId())
            : null;
        if (fp != null) {
          me.bill.fakePlayerPlugin.api.event.FppBotChatEvent chatEvt =
              new me.bill.fakePlayerPlugin.api.event.FppBotChatEvent(
                  new me.bill.fakePlayerPlugin.api.impl.FppBotImpl(fp), rawMessage);
          org.bukkit.Bukkit.getPluginManager().callEvent(chatEvt);
          if (chatEvt.isCancelled()) return;
          rawMessage = chatEvt.getMessage();
        }
      }
    }
    try {
      player.chat(rawMessage);
    } catch (Throwable chatError) {
      Config.debugChat(
          "dispatchChat fallback for "
              + player.getName()
              + " ("
              + chatError.getClass().getSimpleName()
              + ": "
              + chatError.getMessage()
              + ")");
      dispatchLegacyChat(player, rawMessage);
    }
  }

  @SuppressWarnings("deprecation")
  private static void dispatchLegacyChat(Player player, String rawMessage) {
    Component message = Component.text(rawMessage);
    Component displayName = player.displayName();
    java.util.Set<Audience> viewers = new java.util.HashSet<>(Bukkit.getOnlinePlayers());
    viewers.add(Bukkit.getConsoleSender());
    net.kyori.adventure.chat.SignedMessage signed =
        net.kyori.adventure.chat.SignedMessage.system(rawMessage, message);
    ChatRenderer renderer =
        ChatRenderer.viewerUnaware(
            (src, dn, msg) -> Component.empty().append(dn).append(Component.text(": ")).append(msg));
    AsyncChatEvent event = new AsyncChatEvent(false, player, viewers, renderer, message, message, signed);
    Bukkit.getPluginManager().callEvent(event);
    if (event.isCancelled()) return;
    for (Audience viewer : event.viewers()) {
      viewer.sendMessage(event.renderer().render(player, displayName, event.message(), viewer));
    }
  }

  public static void broadcastRemote(
      String botName, String botDisplayName, String message, String prefix, String suffix) {
    if (!Config.fakeChatEnabled()) {
      Config.debugChat("Remote message dropped (bot chat disabled).");
      return;
    }
    isRemoteBroadcast.set(true);
    try {
      String decoratedName = (botDisplayName != null) ? botDisplayName : botName;
      if ((prefix != null && !prefix.isBlank()) || (suffix != null && !suffix.isBlank())) {
        decoratedName =
            (prefix != null ? prefix : "")
                + decoratedName
                + (suffix != null ? suffix : "");
      }
      broadcastFormatted(decoratedName, message);
      Config.debugChat("Broadcast remote message from bot '" + botName + "'.");
    } finally {
      isRemoteBroadcast.remove();
    }
  }

  private boolean hasRealPlayerOnline() {
    for (Player p : Bukkit.getOnlinePlayers()) {
      if (manager.getByUuid(p.getUniqueId()) == null) return true;
    }
    return false;
  }

  private String resolveRandomPlayer(FakePlayer self) {
    List<String> real = new ArrayList<>();
    for (Player p : Bukkit.getOnlinePlayers()) {
      if (manager.getByUuid(p.getUniqueId()) == null) real.add(p.getName());
    }
    if (!real.isEmpty()) return real.get(ThreadLocalRandom.current().nextInt(real.size()));

    List<FakePlayer> others = new ArrayList<>(manager.getActivePlayers());
    others.removeIf(fp -> fp.getUuid().equals(self.getUuid()));
    if (!others.isEmpty())
      return others.get(ThreadLocalRandom.current().nextInt(others.size())).getName();

    return self.getName();
  }

  private String resolveRandomBotName(FakePlayer self) {
    List<FakePlayer> others =
        manager.getActivePlayers().stream()
            .filter(fp -> !fp.getUuid().equals(self.getUuid()))
            .toList();
    if (!others.isEmpty())
      return others.get(ThreadLocalRandom.current().nextInt(others.size())).getName();
    return self.getName();
  }
}
