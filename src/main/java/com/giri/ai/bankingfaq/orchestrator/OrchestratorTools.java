package com.giri.ai.bankingfaq.orchestrator;

import com.giri.ai.bankingfaq.banking.BankingActionsService;
import com.giri.ai.bankingfaq.service.ChatResult;
import com.giri.ai.bankingfaq.service.DocumentQnaService;
import com.giri.ai.bankingfaq.service.RagChatService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class OrchestratorTools {

    private final RagChatService ragChatService;
    private final DocumentQnaService documentQnaService;
    private final BankingActionsService bankingActionsService;
    private final String accountId;       // bound once, never model-controlled — same principle as AccountTools
    private final String conversationId;

    public OrchestratorTools(RagChatService ragChatService,
                             DocumentQnaService documentQnaService,
                             BankingActionsService bankingActionsService,
                             String accountId,
                             String conversationId) {
        this.ragChatService = ragChatService;
        this.documentQnaService = documentQnaService;
        this.bankingActionsService = bankingActionsService;
        this.accountId = accountId;
        this.conversationId = conversationId;
    }

    @Tool(description = "Answer a general banking policy or FAQ question — fees, FD withdrawal rules, " +
            "loan terms. Grounded only in official bank documents.")
    public String answerBankingFaq(
            @ToolParam(description = "The customer's question about banking policies or fees") String question) {

        System.out.println("[Agent] answerBankingFaq called: " + question);
        ChatResult result = ragChatService.chat(conversationId, question);
        return result.answer();
    }

    @Tool(description = "Answer a question about a specific previously-uploaded document (loan agreement, " +
            "T&C PDF, etc.). Requires the document's ID. If the user hasn't provided a documentId, ask them " +
            "for it rather than guessing or making one up.")
    public String answerDocumentQuestion(
            @ToolParam(description = "The documentId of the uploaded document") String documentId,
            @ToolParam(description = "The question to ask about that document") String question) {

        System.out.println("[Agent] answerDocumentQuestion called: documentId=" + documentId + ", question=" + question);
        ChatResult result = documentQnaService.ask(documentId, question);
        return result.answer();
    }

    @Tool(description = "Perform a banking action on the CURRENT user's own account: check balance, list " +
            "recent transactions, or transfer funds (requires proposing then confirming). Cannot act on any " +
            "other account.")
    public String performBankingAction(
            @ToolParam(description = "What the user wants to do, in their own words") String message) {

        System.out.println("[Agent] performBankingAction called: " + message);

        if (accountId == null || accountId.isBlank()) {
            return "No account context is available for this request — the user needs to be authenticated " +
                    "with a valid account before banking actions can be performed.";
        }

        return bankingActionsService.chat(accountId, conversationId, message);
    }
}