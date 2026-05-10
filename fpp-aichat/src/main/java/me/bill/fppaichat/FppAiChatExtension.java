package me.bill.fppaichat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import me.bill.fakePlayerPlugin.api.FppApi;
import me.bill.fakePlayerPlugin.api.FppBot;
import me.bill.fakePlayerPlugin.api.FppAddonCommand;
import me.bill.fakePlayerPlugin.api.FppExtension;
import me.bill.fakePlayerPlugin.api.event.FppBotDespawnEvent;
import me.bill.fakePlayerPlugin.api.event.FppBotSpawnEvent;
import me.bill.fppaichat.ai.AIProviderRegistry;
import me.bill.fppaichat.ai.BotConversationManager;
import me.bill.fppaichat.ai.PersonalityRepository;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public final class FppAiChatExtension implements FppExtension, Listener {

  private FppApi api;
  private Plugin plugin;
  private AIProviderRegistry providerRegistry;
  private BotConversationManager conversationManager;
  private PersonalityRepository personalityRepository;
  private FppAddonCommand personalityCommand;

  @Override
  public @NotNull String getName() {
    return "FPP-AIChat";
  }

  @Override
  public @NotNull String getVersion() {
    return "1.0.0";
  }

  @Override
  public @NotNull String getDescription() {
    return "Provider-backed AI direct messages and public chat reactions for FPP bots.";
  }

  @Override
  public @NotNull List<String> getAuthors() {
    return List.of("Bill");
  }

  @Override
  public void onEnable(@NotNull FppApi api) {
    this.api = api;
    this.plugin = api.getPlugin();

    saveDefaultConfig();
    saveDefaultResources();

    File dataFolder = getDataFolder();
    if (dataFolder == null) {
      plugin.getLogger().warning("[FPP-AIChat] No extension data folder; disabling.");
      return;
    }

    personalityRepository = new PersonalityRepository(dataFolder, plugin.getLogger());
    personalityRepository.init();
    providerRegistry = new AIProviderRegistry(dataFolder, plugin.getLogger());
    conversationManager =
        new BotConversationManager(plugin, getConfig(), providerRegistry, personalityRepository);

    personalityCommand = new PersonalityAddonCommand();
    api.registerCommand(personalityCommand);
    Bukkit.getPluginManager().registerEvents(this, plugin);

    plugin
        .getLogger()
        .info(
            "[FPP-AIChat] Enabled"
                + (providerRegistry.isAvailable()
                    ? " with " + providerRegistry.getActiveProvider().getName()
                    : " without a configured provider"));
  }

  @Override
  public void onDisable() {
    HandlerList.unregisterAll(this);
    if (api != null && personalityCommand != null) {
      api.unregisterCommand(personalityCommand);
    }
    if (conversationManager != null) conversationManager.clearAll();
    personalityCommand = null;
    conversationManager = null;
    providerRegistry = null;
    personalityRepository = null;
    api = null;
    plugin = null;
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
    if (!enabled() || conversationManager == null) return;
    Player sender = event.getPlayer();
    if (api.isBot(sender)) return;

    String[] parts = event.getMessage().split("\\s+", 3);
    if (parts.length < 3) return;
    String command = parts[0].toLowerCase().substring(1);
    if (!isDirectMessageCommand(command)) return;

    api.getBot(parts[1])
        .ifPresent(
            bot -> {
              event.setCancelled(true);
              conversationManager.handleDirectMessage(bot, sender, parts[2]);
              sender.sendMessage("§7[me -> " + bot.getName() + "] §f" + parts[2]);
            });
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onBotSpawn(FppBotSpawnEvent event) {
    if (!enabled() || !cfg().getBoolean("personality.auto-assign-on-spawn", true)) return;
    FppBot bot = event.getBot();
    if (bot.getAiPersonality() != null || personalityRepository == null || personalityRepository.size() == 0) {
      return;
    }
    List<String> names = personalityRepository.getNames();
    bot.setAiPersonality(names.get(ThreadLocalRandom.current().nextInt(names.size())));
    api.persistBotSettings(bot);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onBotDespawn(FppBotDespawnEvent event) {
    if (conversationManager != null) conversationManager.clearBotConversations(event.getBot().getUuid());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onAsyncChat(AsyncChatEvent event) {
    if (!enabled() || conversationManager == null || !cfg().getBoolean("public-chat.enabled", false)) {
      return;
    }
    Player sender = event.getPlayer();
    if (api.isBot(sender)) return;
    String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
    if (cfg().getBoolean("public-chat.ignore-short", true) && message.length() < 3) return;

    double chance = cfg().getDouble("public-chat.chance", 0.25);
    int maxBots = Math.max(1, cfg().getInt("public-chat.max-bots", 1));
    List<FppBot> eligible = new ArrayList<>();
    for (FppBot bot : api.getBots()) {
      if (bot.isChatEnabled()) eligible.add(bot);
    }
    java.util.Collections.shuffle(eligible);

    int scheduled = 0;
    for (FppBot bot : eligible) {
      if (scheduled >= maxBots) break;
      if (ThreadLocalRandom.current().nextDouble() > chance) continue;
      UUID botId = bot.getUuid();
      long delay = publicChatDelayTicks(scheduled);
      Bukkit.getScheduler()
          .runTaskLater(
              plugin,
              () ->
                  api.getBot(botId)
                      .ifPresent(
                          current ->
                              conversationManager
                                  .generatePublicChatReaction(current, sender.getName(), message)
                                  .thenAccept(
                                      response -> {
                                        String clean = cleanChatResponse(response);
                                        if (clean.isBlank()) return;
                                        Bukkit.getScheduler()
                                            .runTask(plugin, () -> api.sayAsBot(current, clean));
                                      })),
              delay);
      scheduled++;
    }
  }

  private long publicChatDelayTicks(int index) {
    int min = Math.max(1, cfg().getInt("public-chat.delay.min", 2)) * 20;
    int max = Math.max(min, cfg().getInt("public-chat.delay.max", 8) * 20);
    return ThreadLocalRandom.current().nextInt(min, max + 1) + index * 10L;
  }

  private String cleanChatResponse(String response) {
    return response.trim().replaceAll("^\"|\"$", "").replaceAll("^'|'$", "").trim();
  }

  private boolean isDirectMessageCommand(String command) {
    return switch (command) {
      case "msg", "tell", "whisper", "w", "m", "t", "pm", "dm", "message", "emsg", "etell",
          "ewhisper" -> true;
      default -> false;
    };
  }

  private boolean enabled() {
    return cfg().getBoolean("enabled", true);
  }

  private YamlConfiguration cfg() {
    return getConfig();
  }

  private final class PersonalityAddonCommand implements FppAddonCommand {
    @Override
    public @NotNull String getName() {
      return "personality";
    }

    @Override
    public @NotNull List<String> getAliases() {
      return List.of("persona", "aipersonality");
    }

    @Override
    public @NotNull String getDescription() {
      return "Manage AI personalities for bots.";
    }

    @Override
    public @NotNull String getUsage() {
      return "<list|reload|providers> | <bot> <set <name>|reset|show>";
    }

    @Override
    public @NotNull String getPermission() {
      return "fpp.aichat.personality";
    }

    @Override
    public boolean execute(@NotNull CommandSender sender, @NotNull String[] args) {
      if (args.length == 0) {
        sendUsage(sender);
        return true;
      }

      String sub = args[0].toLowerCase();
      if (sub.equals("list")) {
        sender.sendMessage("§7AI personalities (§f" + personalityRepository.size() + "§7):");
        for (String name : personalityRepository.getNames()) sender.sendMessage("§8- §e" + name);
        return true;
      }
      if (sub.equals("reload")) {
        reloadConfig();
        personalityRepository.reload();
        providerRegistry.reload();
        conversationManager =
            new BotConversationManager(plugin, getConfig(), providerRegistry, personalityRepository);
        sender.sendMessage("§aAI chat reloaded.");
        return true;
      }
      if (sub.equals("providers")) {
        sender.sendMessage(
            providerRegistry.isAvailable()
                ? "§7AI provider: §f" + providerRegistry.getActiveProvider().getName()
                : "§cNo AI provider configured.");
        return true;
      }
      if (args.length < 2) {
        sendUsage(sender);
        return true;
      }

      String botName = args[0];
      FppBot bot = api.getBot(botName).orElse(null);
      if (bot == null) {
        sender.sendMessage("§cNo active bot named " + botName + ".");
        return true;
      }

      String action = args[1].toLowerCase();
      if (action.equals("show")) {
        String runtime = conversationManager.getBotPersonalityName(bot.getUuid());
        String stored = bot.getAiPersonality();
        sender.sendMessage("§7" + bot.getName() + " personality: §f" + (runtime != null ? runtime : stored != null ? stored : "default"));
        return true;
      }
      if (action.equals("reset")) {
        conversationManager.setBotPersonality(bot.getUuid(), null);
        bot.setAiPersonality(null);
        api.persistBotSettings(bot);
        sender.sendMessage("§aReset AI personality for " + bot.getName() + ".");
        return true;
      }
      if (action.equals("set")) {
        if (args.length < 3) {
          sender.sendMessage("§cUsage: /fpp personality <bot> set <name>");
          return true;
        }
        String personalityName = args[2].toLowerCase();
        if (!personalityRepository.has(personalityName)) {
          sender.sendMessage("§cPersonality not found: " + personalityName);
          return true;
        }
        conversationManager.setBotPersonalityByName(bot.getUuid(), personalityName);
        bot.setAiPersonality(personalityName);
        api.persistBotSettings(bot);
        sender.sendMessage("§aSet AI personality for " + bot.getName() + " to " + personalityName + ".");
        return true;
      }

      sendUsage(sender);
      return true;
    }

    @Override
    public @NotNull List<String> tabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
      if (args.length == 1) {
        List<String> opts = new ArrayList<>(List.of("list", "reload", "providers"));
        for (FppBot bot : api.getBots()) opts.add(bot.getName());
        String partial = args[0].toLowerCase();
        return opts.stream().filter(opt -> opt.toLowerCase().startsWith(partial)).toList();
      }
      if (args.length == 2 && api.getBot(args[0]).isPresent()) {
        String partial = args[1].toLowerCase();
        return List.of("set", "reset", "show").stream()
            .filter(opt -> opt.startsWith(partial))
            .toList();
      }
      if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
        String partial = args[2].toLowerCase();
        return personalityRepository.getNames().stream()
            .filter(name -> name.startsWith(partial))
            .toList();
      }
      return List.of();
    }

    private void sendUsage(CommandSender sender) {
      sender.sendMessage("§7Usage: §e/fpp personality <list|reload|providers>");
      sender.sendMessage("§7       §e/fpp personality <bot> <set <name>|reset|show>");
    }
  }
}
