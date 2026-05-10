package me.bill.fpppeaks;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import me.bill.fakePlayerPlugin.FakePlayerPlugin;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.database.DatabaseManager;
import me.bill.fakePlayerPlugin.fakeplayer.BotSwapController;
import me.bill.fakePlayerPlugin.fakeplayer.BotType;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayerManager;
import me.bill.fakePlayerPlugin.lang.Lang;
import me.bill.fakePlayerPlugin.util.FppLogger;
import me.bill.fakePlayerPlugin.util.FppScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

public final class PeakHoursManager {

  private final FakePlayerPlugin plugin;
  private final FakePlayerManager manager;

  private DatabaseManager db = null;

  private final Deque<SleepingBot> sleepingBots = new ArrayDeque<>();

  private int tickTaskId = -1;

  private volatile boolean adjusting = false;

  private double lastFraction = -1.0;

  public PeakHoursManager(FakePlayerPlugin plugin, FakePlayerManager manager) {
    this.plugin = plugin;
    this.manager = manager;
  }

  public void setDatabaseManager(DatabaseManager db) {
    this.db = db;
  }

  public void start() {
    if (tickTaskId != -1) return;
    tickTaskId = FppScheduler.runSyncRepeatingWithId(plugin, this::tick, 40L, 1200L);
    Config.debugChat("[PeakHours] Scheduler started (60-second tick).");
  }

  public void shutdown() {
    stopTick();
    wakeAll();
    Config.debugChat("[PeakHours] Shutdown complete - all sleeping bots woken.");
  }

  public void reload() {
    stopTick();
    adjusting = false;
    lastFraction = -1.0;
    if (!sleepingBots.isEmpty()) {
      int count = sleepingBots.size();
      wakeAll();
      Config.debugChat("[PeakHours] Reload: woke " + count + " sleeping bot(s) for re-evaluation.");
    }
    if (Config.peakHoursEnabled() && Config.swapEnabled()) {
      start();
      FppScheduler.runSyncLater(plugin, this::tick, 20L);
    }
  }

  private void tick() {
    if (!Config.peakHoursEnabled()) return;

    if (!Config.swapEnabled()) {
      if (!sleepingBots.isEmpty()) {
        FppLogger.warn(
            "[PeakHours] Swap disabled while "
                + sleepingBots.size()
                + " bot(s) sleeping - waking all to prevent data loss.");
        wakeAll();
      }
      Config.debugChat("[PeakHours] Swap is off - tick paused.");
      return;
    }

    if (adjusting) {
      Config.debugChat("[PeakHours] Tick skipped - previous stagger still in-flight.");
      return;
    }

    double fraction = computeTargetFraction();
    int onlineAFK = countOnlineAFKBots();
    int sleeping = sleepingBots.size();

    BotSwapController swapAI = manager.getBotSwapAI();
    int swappedOut = (swapAI != null) ? swapAI.getSwappedOutCount() : 0;
    int total = onlineAFK + sleeping + swappedOut;

    if (total == 0) return;

    int minOnline = Math.max(0, Config.peakHoursMinOnline());
    int target = (int) Math.round(fraction * total);
    target = Math.max(minOnline, Math.min(total, target));

    checkAndAnnounceTransition(fraction);

    int effectivelyOnline = onlineAFK + swappedOut;
    int toSleep = Math.max(0, effectivelyOnline - target);
    int toWake = Math.max(0, target - effectivelyOnline);
    toSleep = Math.min(toSleep, onlineAFK);
    toWake = Math.min(toWake, sleeping);

    if (toSleep == 0 && toWake == 0) {
      Config.debugChat(
          "[PeakHours] OK - fraction="
              + String.format("%.0f%%", fraction * 100)
              + " online="
              + onlineAFK
              + " swapping="
              + swappedOut
              + " sleeping="
              + sleeping
              + " total="
              + total
              + " target="
              + target
              + ".");
      return;
    }

    int stagger = Math.max(1, Config.peakHoursStaggerSeconds());
    Config.debugChat(
        "[PeakHours] Adjusting - fraction="
            + String.format("%.0f%%", fraction * 100)
            + " online="
            + onlineAFK
            + " swapping="
            + swappedOut
            + " sleeping="
            + sleeping
            + " total="
            + total
            + " target="
            + target
            + " toSleep="
            + toSleep
            + " toWake="
            + toWake);

    adjusting = true;

    if (toSleep > 0) {
      List<FakePlayer> candidates = pickSleepCandidates(toSleep);
      List<Runnable> actions = new ArrayList<>(candidates.size());
      for (FakePlayer fp : candidates) {
        actions.add(() -> putToSleep(fp));
      }
      scheduleStaggered(actions, stagger, () -> adjusting = false);
    } else {
      List<Runnable> actions = new ArrayList<>(toWake);
      for (int i = 0; i < toWake; i++) {
        actions.add(this::wakeBotFromQueue);
      }
      scheduleStaggered(actions, stagger, () -> adjusting = false);
    }
  }

  private void putToSleep(FakePlayer fp) {
    if (fp == null) return;
    if (manager.getByUuid(fp.getUuid()) == null) {
      Config.debugChat("[PeakHours] putToSleep: '" + fp.getName() + "' already gone.");
      return;
    }

    BotSwapController swapAI = manager.getBotSwapAI();
    if (swapAI != null) swapAI.cancel(fp.getUuid());

    Location loc = fp.getLiveLocation();
    String name = fp.getName();
    sleepingBots.addLast(new SleepingBot(name, loc));
    manager.delete(name);
    persistSleepingBots();
    Config.debugChat("[PeakHours] '" + name + "' → sleep (pool: " + sleepingBots.size() + ").");
  }

  public boolean putBotToSleepByName(String name) {
    FakePlayer fp = manager.getByName(name);
    if (fp == null) return false;
    putToSleep(fp);
    return true;
  }

  private void wakeBotFromQueue() {
    SleepingBot sb = sleepingBots.pollFirst();
    if (sb != null) {
      wakeEntry(sb);
      persistSleepingBots();
    }
  }

  public boolean wakeBotByName(String name) {
    SleepingBot target = null;
    for (SleepingBot sb : sleepingBots) {
      if (sb.name().equalsIgnoreCase(name)) {
        target = sb;
        break;
      }
    }
    if (target == null) return false;
    sleepingBots.remove(target);
    wakeEntry(target);
    persistSleepingBots();
    return true;
  }

  private void wakeEntry(SleepingBot sb) {
    if (sb.loc() == null || sb.loc().getWorld() == null) {
      FppLogger.warn(
          "[PeakHours] Sleeping bot '" + sb.name() + "' had no valid location - discarded.");
      return;
    }
    String customName = null;
    if (Config.swapSameNameOnRejoin() && !manager.isNameUsed(sb.name())) {
      customName = sb.name();
    }
    int result = manager.spawn(sb.loc(), 1, null, customName, true);
    Config.debugChat(
        "[PeakHours] '"
            + sb.name()
            + "' → wake (result="
            + result
            + ", pool: "
            + sleepingBots.size()
            + ").");
  }

  public void wakeAll() {
    int count = sleepingBots.size();

    while (!sleepingBots.isEmpty()) {
      SleepingBot sb = sleepingBots.pollFirst();
      if (sb != null) wakeEntry(sb);
    }
    clearPersistedSleepingBots();
    if (count > 0) Config.debugChat("[PeakHours] wakeAll() - woke " + count + " bot(s).");
  }

  private void persistSleepingBots() {
    if (db == null || !me.bill.fakePlayerPlugin.config.Config.databaseEnabled()) return;
    List<me.bill.fakePlayerPlugin.database.DatabaseManager.SleepingBotRow> rows =
        new ArrayList<>(sleepingBots.size());
    int i = 0;
    for (SleepingBot sb : sleepingBots) {
      Location loc = sb.loc();
      if (loc == null || loc.getWorld() == null) {
        i++;
        continue;
      }
      rows.add(
          new me.bill.fakePlayerPlugin.database.DatabaseManager.SleepingBotRow(
              i++,
              sb.name(),
              loc.getWorld().getName(),
              loc.getX(),
              loc.getY(),
              loc.getZ(),
              loc.getYaw(),
              loc.getPitch()));
    }
    db.saveSleepingBots(rows);
  }

  private void clearPersistedSleepingBots() {
    if (db == null || !me.bill.fakePlayerPlugin.config.Config.databaseEnabled()) return;
    db.clearSleepingBots();
  }

  public void restoreSleepingBotsFromDatabase(
      me.bill.fakePlayerPlugin.database.DatabaseManager database) {
    if (database == null) return;
    List<me.bill.fakePlayerPlugin.database.DatabaseManager.SleepingBotRow> rows =
        database.loadSleepingBots();
    if (rows.isEmpty()) return;

    int restored = 0;
    for (me.bill.fakePlayerPlugin.database.DatabaseManager.SleepingBotRow row : rows) {
      World world = Bukkit.getWorld(row.world());
      if (world == null) {
        FppLogger.warn(
            "[PeakHours] Crash-recovery: cannot restore sleeping bot '"
                + row.botName()
                + "' - world '"
                + row.world()
                + "' not loaded; discarded.");
        continue;
      }
      Location loc = new Location(world, row.x(), row.y(), row.z(), row.yaw(), row.pitch());
      sleepingBots.addLast(new SleepingBot(row.botName(), loc));
      restored++;
    }

    database.clearSleepingBots();
    if (restored > 0) {
      FppLogger.info(
          "[PeakHours] Crash-recovery: restored " + restored + " sleeping bot(s) from database.");
    }
  }

  public void forceCheck() {
    if (adjusting) {
      Config.debugChat("[PeakHours] forceCheck: clearing stalled adjusting flag.");
      adjusting = false;
    }
    tick();
  }

  private void scheduleStaggered(List<Runnable> actions, int staggerSeconds, Runnable onDone) {
    if (actions.isEmpty()) {
      adjusting = false;
      if (onDone != null) onDone.run();
      return;
    }
    int n = actions.size();
    long totalTicks = (long) staggerSeconds * 20L;

    for (int i = 0; i < n; i++) {
      final int idx = i;
      final Runnable action = actions.get(i);
      long base = n == 1 ? 20L : (long) (((double) idx / (n - 1)) * totalTicks) + 20L;
      long jitter = ThreadLocalRandom.current().nextInt(9) - 4L;
      long delay = Math.max(20L, base + jitter);
      FppScheduler.runSyncLater(
          plugin,
          () -> {
            action.run();
            if (idx == n - 1 && onDone != null) onDone.run();
          },
          delay);
    }
  }

  private void checkAndAnnounceTransition(double newFraction) {
    if (lastFraction >= 0.0 && Math.abs(newFraction - lastFraction) > 0.001) {
      String label = String.format("%.0f%%", newFraction * 100);
      String window = getCurrentWindowLabel();
      Config.debugChat("[PeakHours] Transition → " + window + " (" + label + ").");
      if (Config.peakHoursNotifyTransitions()) {
        for (Player p : Bukkit.getOnlinePlayers()) {
          if (p.hasPermission("fpp.peaks")) {
            p.sendMessage(Lang.get("peaks-transition", "window", window, "fraction", label));
          }
        }
      }
    }
    lastFraction = newFraction;
  }

  public double computeTargetFraction() {
    ZoneId zone = safeZone();
    LocalDateTime now = LocalDateTime.now(zone);
    LocalTime time = now.toLocalTime();
    DayOfWeek dow = now.getDayOfWeek();

    for (Map<?, ?> window : resolveSchedule(dow)) {
      String startStr = objectToString(window.get("start"));
      String endStr = objectToString(window.get("end"));
      double frac;
      try {
        frac = Double.parseDouble(objectToString(window.get("fraction")));
      } catch (NumberFormatException e) {
        continue;
      }
      try {
        LocalTime start = LocalTime.parse(startStr);
        LocalTime end = LocalTime.parse(endStr);
        if (timeInWindow(time, start, end)) return Math.max(0.0, Math.min(1.0, frac));
      } catch (DateTimeParseException ignored) {
      }
    }
    return 1.0;
  }

  public double getNextWindowFraction() {
    ZoneId zone = safeZone();
    LocalDateTime now = LocalDateTime.now(zone);
    LocalTime time = now.toLocalTime();
    DayOfWeek dow = now.getDayOfWeek();

    List<Map<?, ?>> schedule = resolveSchedule(dow);
    long shortest = Long.MAX_VALUE;
    double nextFrac = -1.0;
    for (Map<?, ?> window : schedule) {
      try {
        LocalTime wStart = LocalTime.parse(objectToString(window.get("start")));
        long secs = time.until(wStart, ChronoUnit.SECONDS);
        if (secs <= 0) secs += 86400L;
        if (secs > 0 && secs < shortest) {
          shortest = secs;
          nextFrac = Double.parseDouble(objectToString(window.get("fraction")));
          nextFrac = Math.max(0.0, Math.min(1.0, nextFrac));
        }
      } catch (Exception ignored) {
      }
    }
    return nextFrac;
  }

  private List<Map<?, ?>> resolveSchedule(DayOfWeek dow) {
    ConfigurationSection overrides = Config.peakHoursDayOverrides();
    if (overrides != null) {
      String dayKey = dow.name();
      if (overrides.contains(dayKey)) {
        List<Map<?, ?>> daySchedule = overrides.getMapList(dayKey);
        if (!daySchedule.isEmpty()) return daySchedule;
      }
    }
    return Config.peakHoursSchedule();
  }

  private static boolean timeInWindow(LocalTime time, LocalTime start, LocalTime end) {
    if (!end.isBefore(start)) {
      return !time.isBefore(start) && time.isBefore(end);
    } else {
      return !time.isBefore(start) || time.isBefore(end);
    }
  }

  private int countOnlineAFKBots() {
    int c = 0;
    for (FakePlayer fp : manager.getActivePlayers()) {
      if (fp.getBotType() == BotType.AFK) c++;
    }
    return c;
  }

  private List<FakePlayer> pickSleepCandidates(int limit) {
    List<FakePlayer> list = new ArrayList<>();
    for (FakePlayer fp : manager.getActivePlayers()) {
      if (fp.getBotType() == BotType.AFK) list.add(fp);
    }
    Collections.shuffle(list, ThreadLocalRandom.current());
    return list.subList(0, Math.min(limit, list.size()));
  }

  public int getSleepingCount() {
    return sleepingBots.size();
  }

  public boolean isRunning() {
    return tickTaskId != -1;
  }

  public int getTotalPool() {
    BotSwapController s = manager.getBotSwapAI();
    return countOnlineAFKBots() + sleepingBots.size() + (s != null ? s.getSwappedOutCount() : 0);
  }

  public List<String> getSleepingNames() {
    List<String> names = new ArrayList<>(sleepingBots.size());
    for (SleepingBot sb : sleepingBots) names.add(sb.name());
    return Collections.unmodifiableList(names);
  }

  public List<SleepingBot> getSleepingEntries() {
    return List.copyOf(sleepingBots);
  }

  public boolean isSleeping(String name) {
    for (SleepingBot sb : sleepingBots) {
      if (sb.name().equalsIgnoreCase(name)) return true;
    }
    return false;
  }

  public String getCurrentWindowLabel() {
    ZoneId zone = safeZone();
    LocalDateTime now = LocalDateTime.now(zone);
    LocalTime time = now.toLocalTime();
    DayOfWeek dow = now.getDayOfWeek();
    for (Map<?, ?> window : resolveSchedule(dow)) {
      String s = objectToString(window.get("start"));
      String e = objectToString(window.get("end"));
      try {
        if (timeInWindow(time, LocalTime.parse(s), LocalTime.parse(e))) return s + "–" + e;
      } catch (DateTimeParseException ignored) {
      }
    }
    return "none";
  }

  public long getSecondsToNextWindow() {
    ZoneId zone = safeZone();
    LocalDateTime now = LocalDateTime.now(zone);
    LocalTime time = now.toLocalTime();
    DayOfWeek dow = now.getDayOfWeek();
    List<Map<?, ?>> schedule = resolveSchedule(dow);
    if (schedule.isEmpty()) return -1;
    long shortest = Long.MAX_VALUE;
    for (Map<?, ?> window : schedule) {
      try {
        LocalTime wStart = LocalTime.parse(objectToString(window.get("start")));
        long secs = time.until(wStart, ChronoUnit.SECONDS);
        if (secs <= 0) secs += 86400L;
        if (secs > 0 && secs < shortest) shortest = secs;
      } catch (DateTimeParseException ignored) {
      }
    }
    return shortest == Long.MAX_VALUE ? -1 : shortest;
  }

  private void stopTick() {
    if (tickTaskId != -1) {
      FppScheduler.cancelTask(tickTaskId);
      tickTaskId = -1;
    }
  }

  private ZoneId safeZone() {
    try {
      return ZoneId.of(Config.peakHoursTimezone());
    } catch (Exception e) {
      return ZoneId.of("UTC");
    }
  }

  private static String objectToString(Object o) {
    return o == null ? "" : String.valueOf(o);
  }

  public record SleepingBot(String name, Location loc) {}
}
