package com.giri.ai.bankingfaq.controller;

import com.giri.ai.bankingfaq.orchestrator.OrchestratorService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final OrchestratorService orchestratorService;

    public AssistantController(OrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    @PostMapping("/chat")
    public OrchestratorService.OrchestratorResponse chat(@RequestBody OrchestratorService.OrchestratorRequest request) {
        return orchestratorService.chat(request);
    }
}