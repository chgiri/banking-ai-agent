package com.giri.ai.bankingfaq.orchestrator;

import com.giri.ai.bankingfaq.banking.BankingActionsService;
import com.giri.ai.bankingfaq.service.ChatResult;
import com.giri.ai.bankingfaq.service.DocumentQnaService;
import com.giri.ai.bankingfaq.service.RagChatService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrchestratorService {

    private final ChatClient.Builder chatClientBuilder;
    private final RagChatService ragChatService;
    private final DocumentQnaService documentQnaService;
    private final BankingActionsService bankingActionsService;

    public OrchestratorService(ChatClient.Builder chatClientBuilder,
                               RagChatService ragChatService,
                               DocumentQnaService documentQnaService,
                               BankingActionsService bankingActionsService) {
        this.chatClientBuilder = chatClientBuilder;
        this.ragChatService = ragChatService;
        this.documentQnaService = documentQnaService;
        this.bankingActionsService = bankingActionsService;
    }

    public record OrchestratorRequest(String conversationId, String message, String accountId, String documentId) {}
    public record OrchestratorResponse(String answer, String routedTo, List<ChatResult.SourceReference> sources) {}

    public OrchestratorResponse chat(OrchestratorRequest request) {
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? java.util.UUID.randomUUID().toString()
                : request.conversationId();

        Intent intent = classify(request.message());

        return switch (intent) {
            case BANKING -> handleBanking(request, conversationId);
            case DOCUMENT -> handleDocument(request);
            case FAQ -> handleFaq(request, conversationId);
        };
    }

    private Intent classify(String message) {
        ChatClient chatClient = chatClientBuilder.build();

        String raw = chatClient.prompt()
                .system("""
                    Classify the user's banking-related message into exactly ONE of these categories.
                    Respond with only the single word — no punctuation, no explanation.

                    FAQ — general questions about bank policies, fees, FD withdrawal rules, loan terms
                    DOCUMENT — questions about a specific uploaded document (loan agreement, T&C PDF, etc.)
                    BANKING — checking balance, listing transactions, or transferring funds on the user's own account
                    """)
                .user(message)
                .call()
                .content();

        String cleaned = raw == null ? "" : raw.trim().toUpperCase();

        try {
            return Intent.valueOf(cleaned);
        } catch (IllegalArgumentException e) {
            System.out.println("Unrecognized/malformed classification — default to the safest, least-privileged path");
            return Intent.FAQ;
        }
    }

    private OrchestratorResponse handleFaq(OrchestratorRequest request, String conversationId) {
        ChatResult result = ragChatService.chat(conversationId, request.message());
        return new OrchestratorResponse(result.answer(), "FAQ", result.sources());
    }

    private OrchestratorResponse handleDocument(OrchestratorRequest request) {
        if (request.documentId() == null || request.documentId().isBlank()) {
            return new OrchestratorResponse(
                    "This looks like a question about a specific document, but no documentId was provided. " +
                            "Please upload the document first and include its documentId with your question.",
                    "DOCUMENT",
                    List.of());
        }

        ChatResult result = documentQnaService.ask(request.documentId(), request.message());
        return new OrchestratorResponse(result.answer(), "DOCUMENT", result.sources());
    }

    private OrchestratorResponse handleBanking(OrchestratorRequest request, String conversationId) {
        if (request.accountId() == null || request.accountId().isBlank()) {
            return new OrchestratorResponse(
                    "This looks like an account action (balance, transactions, or transfer), but no accountId " +
                            "was provided. Please provide your account context to proceed.",
                    "BANKING",
                    List.of());
        }

        String answer = bankingActionsService.chat(request.accountId(), conversationId, request.message());
        return new OrchestratorResponse(answer, "BANKING", List.of());
    }
}