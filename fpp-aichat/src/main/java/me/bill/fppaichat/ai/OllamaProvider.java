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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class OllamaProvider implements AIProvider {

  private final String endpoint;
  private final String configuredModel;
  private List<String> availableModels = null;

  public OllamaProvider(String endpoint, String model) {
    this.endpoint = endpoint.endsWith("/") ? endpoint : endpoint + "/";
    this.configuredModel = model;
  }

  @Override
  public String getName() {
    return "Ollama (Local)";
  }

  @Override
  public boolean isAvailable() {
    return endpoint != null && !endpoint.isBlank();
  }

  private List<String> fetchAvailableModels() {
    try {
      URL url = URI.create(endpoint + "api/tags").toURL();
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(3000);
      conn.setReadTimeout(5000);

      int responseCode = conn.getResponseCode();
      if (responseCode != 200) {
        return List.of("llama2");
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
      JsonArray models = json.getAsJsonArray("models");

      List<String> modelNames = new ArrayList<>();
      if (models != null) {
        for (int i = 0; i < models.size(); i++) {
          JsonObject modelObj = models.get(i).getAsJsonObject();
          String name = modelObj.get("name").getAsString();
          modelNames.add(name);
        }
      }

      return modelNames.isEmpty() ? List.of("llama2") : modelNames;
    } catch (Exception e) {
      return List.of("llama2");
    }
  }

  private String selectModel() {
    if (configuredModel != null && !configuredModel.isBlank()) {
      return configuredModel;
    }

    if (availableModels == null) {
      availableModels = fetchAvailableModels();
    }

    if (availableModels.isEmpty()) {
      return "llama2";
    }
    int index = ThreadLocalRandom.current().nextInt(availableModels.size());
    return availableModels.get(index);
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
            requestBody.addProperty("stream", false);

            JsonArray messagesArray = new JsonArray();

            if (personality != null && !personality.isBlank()) {
              JsonObject systemMsg = new JsonObject();
              systemMsg.addProperty("role", "system");
              systemMsg.addProperty("content", personality);
              messagesArray.add(systemMsg);
            }

            for (ChatMessage msg : messages) {
              JsonObject msgObj = new JsonObject();
              msgObj.addProperty("role", msg.role());
              msgObj.addProperty("content", msg.content());
              messagesArray.add(msgObj);
            }

            requestBody.add("messages", messagesArray);

            JsonObject options = new JsonObject();
            options.addProperty("temperature", 0.9);
            options.addProperty("num_predict", 150);
            requestBody.add("options", options);

            URL url = URI.create(endpoint + "api/chat").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
              os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
              throw new RuntimeException("Ollama API error: HTTP " + responseCode);
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
            return json.getAsJsonObject("message").get("content").getAsString().trim();

          } catch (Exception e) {
            throw new RuntimeException("Ollama generation failed: " + e.getMessage(), e);
          }
        });
  }
}
