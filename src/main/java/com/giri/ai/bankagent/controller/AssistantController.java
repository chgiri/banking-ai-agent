package com.giri.ai.bankagent.controller;

import com.giri.ai.bankagent.orchestrator.OrchestratorService;
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