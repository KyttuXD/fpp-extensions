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

public final class GoogleGeminiProvider implements AIProvider {

  private static final String DEFAULT_ENDPOINT_BASE =
      "https://generativelanguage.googleapis.com/v1beta/models";
  private static final String DEFAULT_MODEL = "gemini-pro";

  private static final String[] AVAILABLE_MODELS = {"gemini-pro", "gemini-pro-vision"};

  private final String apiKey;
  private final String endpointBase;
  private final String configuredModel;

  public GoogleGeminiProvider(String apiKey, String endpoint, String model) {
    this.apiKey = apiKey;
    this.endpointBase = endpoint.isBlank() ? DEFAULT_ENDPOINT_BASE : endpoint;
    this.configuredModel = model;
  }

  @Override
  public String getName() {
    return "Google (Gemini)";
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
            String endpoint = endpointBase + "/" + model + ":generateContent";

            JsonObject requestBody = new JsonObject();
            JsonArray contentsArray = new JsonArray();

            StringBuilder combinedPrompt = new StringBuilder();
            if (personality != null && !personality.isBlank()) {
              combinedPrompt.append("System: ").append(personality).append("\n\n");
            }
            for (ChatMessage msg : messages) {
              String role = msg.role().equals("assistant") ? "Model" : "User";
              combinedPrompt.append(role).append(": ").append(msg.content()).append("\n");
            }

            JsonObject content = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", combinedPrompt.toString());
            parts.add(textPart);
            content.add("parts", parts);
            contentsArray.add(content);

            requestBody.add("contents", contentsArray);

            JsonObject genConfig = new JsonObject();
            genConfig.addProperty("temperature", 0.9);
            genConfig.addProperty("maxOutputTokens", 150);
            requestBody.add("generationConfig", genConfig);

            String urlWithKey = endpoint + "?key=" + apiKey;
            URL url = URI.create(urlWithKey).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
              os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
              throw new RuntimeException("Google Gemini API error: HTTP " + responseCode);
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
            return json.getAsJsonArray("candidates")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("content")
                .getAsJsonArray("parts")
                .get(0)
                .getAsJsonObject()
                .get("text")
                .getAsString()
                .trim();

          } catch (Exception e) {
            throw new RuntimeException("Google Gemini generation failed: " + e.getMessage(), e);
          }
        });
  }
}
