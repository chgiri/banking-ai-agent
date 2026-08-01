package com.giri.ai.bankingfaq.orchestrator;

import com.giri.ai.bankingfaq.banking.BankingActionsService;
import com.giri.ai.bankingfaq.service.DocumentQnaService;
import com.giri.ai.bankingfaq.service.RagChatService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AgenticOrchestratorService {

    private final ChatClient.Builder chatClientBuilder;
    private final RagChatService ragChatService;
    private final DocumentQnaService documentQnaService;
    private final BankingActionsService bankingActionsService;
    private final ChatMemory chatMemory;

    public AgenticOrchestratorService(ChatClient.Builder chatClientBuilder,
                                      RagChatService ragChatService,
                                      DocumentQnaService documentQnaService,
                                      BankingActionsService bankingActionsService,
                                      ChatMemory chatMemory) {
        this.chatClientBuilder = chatClientBuilder;
        this.ragChatService = ragChatService;
        this.documentQnaService = documentQnaService;
        this.bankingActionsService = bankingActionsService;
        this.chatMemory = chatMemory;
    }

    public record AgentRequest(String conversationId, String message, String accountId) {}

    public String chat(AgentRequest request) {
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? UUID.randomUUID().toString()
                : request.conversationId();

        OrchestratorTools tools = new OrchestratorTools(
                ragChatService, documentQnaService, bankingActionsService,
                request.accountId(), conversationId);

        ChatClient chatClient = chatClientBuilder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();

        return chatClient.prompt()
                .system("""
                    You are a banking assistant with access to three tools: general FAQ/policy questions,
                    questions about a specific uploaded document, and the current user's own banking actions
                    (balance, transactions, transfers).

                    Decide which tool(s) to use based on what the user actually asks. You may call more than
                    one tool if a single message needs it — for example, a question that touches both a policy
                    question and a balance check.

                    Never fabricate information a tool didn't actually return. If a document question lacks a
                    documentId, ask the user for it rather than guessing.
                    """)
                .user(request.message())
                .tools(tools)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}