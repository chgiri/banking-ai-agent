package com.giri.ai.bankingfaq.controller;

import com.giri.ai.bankingfaq.service.RagChatService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final RagChatService ragChatService;

    public ChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    record ChatRequest(String conversationId, String message) {}
    record ChatResponse(String answer) {}

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String answer = ragChatService.chat(request.conversationId(), request.message());
        return new ChatResponse(answer);
    }
}