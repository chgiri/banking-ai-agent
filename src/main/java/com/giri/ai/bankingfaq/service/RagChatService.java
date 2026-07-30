package com.giri.ai.bankingfaq.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(50)
            .build();

    private static final String SYSTEM_TEMPLATE = """
        You are a banking customer support assistant. Answer the customer's question
        using ONLY the context provided below. If the answer is not in the context,
        say "I don't have that information — let me connect you with a support agent"
        rather than guessing.

        Context:
        {context}
        """;

    public RagChatService(ChatClient.Builder builder, VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.chatClient = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public String chat(String conversationId, String userMessage) {
        // 1. Retrieve relevant chunks
        List<Document> relevantDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userMessage)
                        .topK(4)
                        .build()
        );

        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = SYSTEM_TEMPLATE.replace("{context}", context);

        // 2. Call the model with system context + conversation history
        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        // 3. Log for observability
        logInteraction(conversationId, userMessage, relevantDocs, response);

        return response;
    }

    private void logInteraction(String conversationId, String userMessage,
                                List<Document> retrieved, String response) {
        System.out.printf("""
            [Conversation: %s]
            Question: %s
            Retrieved chunks: %d
            Sources: %s
            Answer: %s
            ---
            """,
                conversationId, userMessage, retrieved.size(),
                retrieved.stream().map(d -> d.getMetadata().get("source")).toList(),
                response);
    }
}