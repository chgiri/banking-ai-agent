package com.giri.ai.bankagent.service;

import com.giri.ai.bankagent.service.ChatResult.SourceReference;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
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

    private static final String SYSTEM_TEMPLATE = """
        You are a banking customer support assistant. Answer the customer's question
        using ONLY the context provided below. If the answer is not in the context,
        say "I don't have that information — let me connect you with a support agent"
        rather than guessing.

        Context:
        {context}
        """;

    public RagChatService(ChatClient.Builder builder, VectorStore vectorStore, ChatMemory chatMemory) {
        this.vectorStore = vectorStore;
        this.chatClient = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    public ChatResult chat(String conversationId, String userMessage) {
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

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        List<SourceReference> sources = relevantDocs.stream()
                .map(d -> new SourceReference(
                        (String) d.getMetadata().get("source"),
                        (String) d.getMetadata().get("docType"),
                        (Integer) d.getMetadata().get("chunk_index")
                ))
                .distinct()
                .toList();

        logInteraction(conversationId, userMessage, relevantDocs, response);

        return new ChatResult(response, sources);
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