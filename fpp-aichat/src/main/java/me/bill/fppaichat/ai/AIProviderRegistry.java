package me.bill.fppaichat.ai;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public final class AIProviderRegistry {

  private final File dataFolder;
  private final Logger logger;
  private final List<AIProvider> providers = new ArrayList<>();
  private AIProvider activeProvider;

  public AIProviderRegistry(File dataFolder, Logger logger) {
    this.dataFolder = dataFolder;
    this.logger = logger;
    reload();
  }

  public void reload() {
    providers.clear();
    activeProvider = null;
    loadProviders();
  }

  private void loadProviders() {
    File secretsFile = new File(dataFolder, "secrets.yml");
    FileConfiguration secrets = YamlConfiguration.loadConfiguration(secretsFile);

    String openaiKey = secrets.getString("openai.api-key", "");
    String openaiEndpoint = secrets.getString("openai.endpoint", "");
    String openaiModel = secrets.getString("openai.model", "");
    if (!openaiKey.isBlank()) {
      providers.add(new OpenAIProvider(openaiKey, openaiEndpoint, openaiModel));
    }

    String groqKey = secrets.getString("groq.api-key", "");
    String groqEndpoint = secrets.getString("groq.endpoint", "");
    String groqModel = secrets.getString("groq.model", "");
    if (!groqKey.isBlank()) {
      providers.add(new GroqProvider(groqKey, groqEndpoint, groqModel));
    }

    String anthropicKey = secrets.getString("anthropic.api-key", "");
    String anthropicEndpoint = secrets.getString("anthropic.endpoint", "");
    String anthropicModel = secrets.getString("anthropic.model", "");
    if (!anthropicKey.isBlank()) {
      providers.add(new AnthropicProvider(anthropicKey, anthropicEndpoint, anthropicModel));
    }

    String googleKey = secrets.getString("google.api-key", "");
    String googleEndpoint = secrets.getString("google.endpoint", "");
    String googleModel = secrets.getString("google.model", "");
    if (!googleKey.isBlank()) {
      providers.add(new GoogleGeminiProvider(googleKey, googleEndpoint, googleModel));
    }

    boolean ollamaEnabled = secrets.getBoolean("ollama.enabled", false);
    String ollamaEndpoint = secrets.getString("ollama.endpoint", "http://localhost:11434");
    String ollamaModel = secrets.getString("ollama.model", "");
    if (ollamaEnabled) {
      providers.add(new OllamaProvider(ollamaEndpoint, ollamaModel));
    }

    String copilotKey = secrets.getString("copilot.api-key", "");
    String copilotEndpoint = secrets.getString("copilot.endpoint", "");
    String copilotDeployment = secrets.getString("copilot.deployment-name", "");
    String copilotModel = secrets.getString("copilot.model", "");
    if (!copilotKey.isBlank() && !copilotEndpoint.isBlank()) {
      providers.add(
          new CopilotProvider(copilotKey, copilotEndpoint, copilotDeployment, copilotModel));
    }

    boolean customEnabled = secrets.getBoolean("custom.enabled", false);
    String customKey = secrets.getString("custom.api-key", "");
    String customEndpoint = secrets.getString("custom.endpoint", "");
    String customModel = secrets.getString("custom.model", "");
    if (customEnabled && !customEndpoint.isBlank()) {
      providers.add(new CustomOpenAIProvider(customKey, customEndpoint, customModel));
    }

    for (AIProvider provider : providers) {
      if (provider.isAvailable()) {
        activeProvider = provider;
        logger.info("[FPP-AIChat] Using provider: " + provider.getName());
        break;
      }
    }

    if (activeProvider == null) {
      logger.warning(
          "[FPP-AIChat] No provider configured. Add an API key to "
              + new File(dataFolder, "secrets.yml").getPath()
              + ".");
    }
  }

  public AIProvider getActiveProvider() {
    return activeProvider;
  }

  public boolean isAvailable() {
    return activeProvider != null && activeProvider.isAvailable();
  }

  public CompletableFuture<String> generateResponse(
      List<AIProvider.ChatMessage> messages, String botName, String personality) {
    if (activeProvider == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("No AI provider configured"));
    }
    return activeProvider.generateResponse(messages, botName, personality);
  }
}
