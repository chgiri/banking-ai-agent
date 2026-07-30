package com.giri.ai.bankingfaq.banking;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BankingActionsService {

    private final ChatClient.Builder chatClientBuilder;
    private final AccountService accountService;
    private final ChatMemory chatMemory;

    public BankingActionsService(ChatClient.Builder chatClientBuilder,
                                 AccountService accountService,
                                 ChatMemory chatMemory) {
        this.chatClientBuilder = chatClientBuilder;
        this.accountService = accountService;
        this.chatMemory = chatMemory;
    }

    public String chat(String accountId, String conversationId, String userMessage) {
        AccountTools tools = new AccountTools(accountService, accountId);

        ChatClient chatClient = chatClientBuilder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();

        String answer = chatClient.prompt()
                .system("""
                    You are a banking actions assistant. You can check balance, list transactions,
                    and transfer funds for the CURRENT user's account only. Before executing a transfer,
                    always propose it first and ask the user to explicitly confirm using the confirmation
                    code. Never assume confirmation — the user must state it clearly.
                    """)
                .user(userMessage)
                .tools(tools)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        logInteraction(userMessage, answer);
        return answer;
    }

    private void logInteraction(String userMessage, String response) {
        System.out.printf("""
            Question: %s
            Answer: %s
            ---
            """,
                userMessage, response);
    }
}