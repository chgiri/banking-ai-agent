package com.giri.ai.bankagent.controller;

import com.giri.ai.bankagent.service.ChatResult;
import com.giri.ai.bankagent.service.RagChatService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final RagChatService ragChatService;

    public ChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    record ChatRequest(String conversationId, String message) {}

    @PostMapping
    public ChatResult chat(@RequestBody ChatRequest request) {
        return ragChatService.chat(request.conversationId(), request.message());
    }
}