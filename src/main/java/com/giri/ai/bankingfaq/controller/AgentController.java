package com.giri.ai.bankingfaq.controller;

import com.giri.ai.bankingfaq.orchestrator.AgenticOrchestratorService;
import com.giri.ai.bankingfaq.orchestrator.AgenticOrchestratorService.AgentRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgenticOrchestratorService agenticOrchestratorService;

    public AgentController(AgenticOrchestratorService agenticOrchestratorService) {
        this.agenticOrchestratorService = agenticOrchestratorService;
    }

    record AgentResponse(String answer) {}

    @PostMapping("/chat")
    public AgentResponse chat(@RequestBody AgentRequest request) {
        String answer = agenticOrchestratorService.chat(request);
        return new AgentResponse(answer);
    }
}