package com.giri.ai.bankagent.controller;

import com.giri.ai.bankagent.banking.BankingActionsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/banking-actions")
public class BankingActionsController {

    private final BankingActionsService bankingActionsService;

    public BankingActionsController(BankingActionsService bankingActionsService) {
        this.bankingActionsService = bankingActionsService;
    }

    record ChatRequest(String accountId, String conversationId, String message) {}
    record ChatResponse(String answer) {}

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String answer = bankingActionsService.chat(
                request.accountId(), request.conversationId(), request.message());
        return new ChatResponse(answer);
    }
}