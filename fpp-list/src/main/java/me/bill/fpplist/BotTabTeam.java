package me.bill.fpplist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import me.bill.fakePlayerPlugin.config.Config;
import me.bill.fakePlayerPlugin.fakeplayer.FakePlayer;
import me.bill.fakePlayerPlugin.util.FppLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

final class BotTabTeam {

  private static final String TEAM_NAME = "~fpp";
  private static final String TEAM_PREFIX = "fpp";
  private static final int MAX_TEAM_NAME_LENGTH = 16;
  private static final Pattern SAFE_TEAM_CHARS = Pattern.compile("[^a-z0-9_]");

  private final Set<String> botEntries = new HashSet<>();
  private final Map<String, String> entryTeams = new HashMap<>();
  private boolean lastPushable = Config.bodyPushable();

  void init() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      ensureLegacyTeamExists(player);
    }
    applyCollisionRule();
    FppLogger.debug(
        "[FPP-List] Bot tab teams initialized on "
            + Bukkit.getOnlinePlayers().size()
            + " player scoreboard(s).");
  }

  void applyCollisionRule() {
    lastPushable = Config.bodyPushable();
    for (Player player : Bukkit.getOnlinePlayers()) {
      for (Team team : player.getScoreboard().getTeams()) {
        if (isFppTeam(team.getName())) applyCollisionRuleToTeam(team);
      }
    }
  }

  void addBot(FakePlayer fp) {
    if (!Config.tabListEnabled()) return;

    String entry = fp.getPacketProfileName();
    String teamName = teamNameFor(fp);
    botEntries.add(entry);
    entryTeams.put(entry, teamName);

    for (Player player : Bukkit.getOnlinePlayers()) {
      removeEntryFromManagedTeams(player.getScoreboard(), entry, teamName);
      Team team = ensureTeamExists(player, teamName);
      if (team != null && !team.hasEntry(entry)) team.addEntry(entry);
    }
  }

  void removeBot(FakePlayer fp) {
    if (fp != null) removeEntry(fp.getPacketProfileName());
  }

  void removeEntry(String entry) {
    if (entry == null) return;
    botEntries.remove(entry);
    entryTeams.remove(entry);
    for (Player player : Bukkit.getOnlinePlayers()) {
      removeEntryFromManagedTeams(player.getScoreboard(), entry, null);
    }
  }

  void syncIncremental(Collection<FakePlayer> activeBots) {
    if (lastPushable != Config.bodyPushable()) applyCollisionRule();

    if (!Config.tabListEnabled()) {
      clearAll();
      return;
    }

    Set<String> desiredEntries = new HashSet<>();
    Map<String, String> desiredTeams = new HashMap<>();
    for (FakePlayer fp : activeBots) {
      String entry = fp.getPacketProfileName();
      desiredEntries.add(entry);
      desiredTeams.put(entry, teamNameFor(fp));
    }

    botEntries.clear();
    botEntries.addAll(desiredEntries);
    entryTeams.clear();
    entryTeams.putAll(desiredTeams);

    for (Player player : Bukkit.getOnlinePlayers()) {
      Scoreboard board = player.getScoreboard();
      for (Team team : new ArrayList<>(board.getTeams())) {
        if (!isFppTeam(team.getName())) continue;
        for (String existing : new ArrayList<>(team.getEntries())) {
          String desiredTeam = desiredTeams.get(existing);
          if (desiredTeam == null || !desiredTeam.equals(team.getName())) {
            team.removeEntry(existing);
          }
        }
      }
      for (String entry : desiredEntries) {
        String teamName = desiredTeams.get(entry);
        removeEntryFromManagedTeams(board, entry, teamName);
        Team team = ensureTeamExists(player, teamName);
        if (team != null && !team.hasEntry(entry)) team.addEntry(entry);
      }
    }
  }

  void clearAll() {
    botEntries.clear();
    entryTeams.clear();
    for (Player player : Bukkit.getOnlinePlayers()) {
      clearManagedTeams(player.getScoreboard());
    }
  }

  void destroy() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      for (Team team : new ArrayList<>(player.getScoreboard().getTeams())) {
        if (!isFppTeam(team.getName())) continue;
        try {
          team.unregister();
        } catch (Exception ignored) {
        }
      }
    }
    botEntries.clear();
    entryTeams.clear();
  }

  void syncToPlayer(Player player) {
    if (!Config.tabListEnabled() || botEntries.isEmpty()) return;
    Scoreboard board = player.getScoreboard();
    for (String entry : botEntries) {
      String teamName = entryTeams.getOrDefault(entry, TEAM_NAME);
      removeEntryFromManagedTeams(board, entry, teamName);
      Team team = ensureTeamExists(player, teamName);
      if (team != null && !team.hasEntry(entry)) team.addEntry(entry);
    }
  }

  private void ensureLegacyTeamExists(Player player) {
    if (player == null) return;
    try {
      Scoreboard board = player.getScoreboard();
      if (board.getTeam(TEAM_NAME) == null) {
        Team team = board.registerNewTeam(TEAM_NAME);
        applyCollisionRuleToTeam(team);
      }
    } catch (Exception ignored) {
    }
  }

  private Team ensureTeamExists(Player player, String teamName) {
    if (player == null || teamName == null || teamName.isBlank()) return null;
    try {
      Scoreboard board = player.getScoreboard();
      Team team = board.getTeam(teamName);
      if (team == null) team = board.registerNewTeam(teamName);
      applyCollisionRuleToTeam(team);
      return team;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static void applyCollisionRuleToTeam(Team team) {
    team.setOption(
        Team.Option.COLLISION_RULE,
        Config.bodyPushable() ? Team.OptionStatus.ALWAYS : Team.OptionStatus.NEVER);
  }

  private static String teamNameFor(FakePlayer fp) {
    return TEAM_NAME;
  }

  private static boolean isFppTeam(String teamName) {
    return TEAM_NAME.equals(teamName)
        || (teamName != null && teamName.matches("^" + TEAM_PREFIX + "\\d{5}_.+"));
  }

  private static void removeEntryFromManagedTeams(
      Scoreboard board, String entry, String exceptTeamName) {
    if (board == null || entry == null) return;
    for (Team team : new ArrayList<>(board.getTeams())) {
      if (!isFppTeam(team.getName())) continue;
      if (exceptTeamName != null && exceptTeamName.equals(team.getName())) continue;
      if (team.hasEntry(entry)) team.removeEntry(entry);
    }
  }

  private static void clearManagedTeams(Scoreboard board) {
    if (board == null) return;
    for (Team team : new ArrayList<>(board.getTeams())) {
      if (!isFppTeam(team.getName())) continue;
      for (String entry : new ArrayList<>(team.getEntries())) {
        team.removeEntry(entry);
      }
    }
  }
}
