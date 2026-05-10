package me.bill.fppaichat.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class AnthropicProvider implements AIProvider {

  private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
  private static final String DEFAULT_MODEL = "claude-3-haiku-20240307";

  private static final String[] AVAILABLE_MODELS = {
    "claude-3-opus-20240229", "claude-3-sonnet-20240229", "claude-3-haiku-20240307"
  };

  private final String apiKey;
  private final String endpoint;
  private final String configuredModel;

  public AnthropicProvider(String apiKey, String endpoint, String model) {
    this.apiKey = apiKey;
    this.endpoint = endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint;
    this.configuredModel = model;
  }

  @Override
  public String getName() {
    return "Anthropic (Claude)";
  }

  @Override
  public boolean isAvailable() {
    return apiKey != null && !apiKey.isBlank();
  }

  private String selectModel() {
    if (configuredModel != null && !configuredModel.isBlank()) {
      return configuredModel;
    }

    int index = ThreadLocalRandom.current().nextInt(AVAILABLE_MODELS.length);
    return AVAILABLE_MODELS[index];
  }

  @Override
  public CompletableFuture<String> generateResponse(
      List<ChatMessage> messages, String botName, String personality) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            String model = selectModel();

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", model);
            requestBody.addProperty("max_tokens", 150);

            if (personality != null && !personality.isBlank()) {
              requestBody.addProperty("system", personality);
            }

            JsonArray messagesArray = new JsonArray();
            for (ChatMessage msg : messages) {
              if (!msg.role().equals("system")) {
                JsonObject msgObj = new JsonObject();
                msgObj.addProperty("role", msg.role());
                msgObj.addProperty("content", msg.content());
                messagesArray.add(msgObj);
              }
            }

            requestBody.add("messages", messagesArray);

            URL url = URI.create(endpoint).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
              os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
              throw new RuntimeException("Anthropic API error: HTTP " + responseCode);
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader br =
                new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
              String line;
              while ((line = br.readLine()) != null) {
                response.append(line);
              }
            }

            JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
            return json.getAsJsonArray("content")
                .get(0)
                .getAsJsonObject()
                .get("text")
                .getAsString()
                .trim();

          } catch (Exception e) {
            throw new RuntimeException("Anthropic generation failed: " + e.getMessage(), e);
          }
        });
  }
}
