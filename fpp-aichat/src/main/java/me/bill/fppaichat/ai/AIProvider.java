package me.bill.fppaichat.ai;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface AIProvider {

  String getName();

  boolean isAvailable();

  CompletableFuture<String> generateResponse(
      List<ChatMessage> messages, String botName, String personality);

  record ChatMessage(String role, String content) {}
}
