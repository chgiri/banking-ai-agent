package com.giri.ai.bankingfaq.service;

import com.giri.ai.bankingfaq.service.ChatResult.SourceReference;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class DocumentQnaService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    private static final String SYSTEM_TEMPLATE = """
        You are a document analysis assistant. Answer the question using ONLY
        the context provided below, which comes from a specific uploaded document.
        If the answer is not in the context, say "This document doesn't appear
        to cover that" rather than guessing.

        Context:
        {context}
        """;

    public DocumentQnaService(ChatClient.Builder builder, VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.chatClient = builder.build();
    }

    public ChatResult ask(String documentId, String question) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();

        List<Document> relevantChunks = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(4)
                        .filterExpression(b.eq("documentId", documentId).build())
                        .build()
        );

        if (relevantChunks.isEmpty()) {
            return new ChatResult(
                    "No content found for that document ID. Check that the ID is correct and the document was ingested successfully.",
                    List.of()
            );
        }

        String context = relevantChunks.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = SYSTEM_TEMPLATE.replace("{context}", context);

        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .call()
                .content();

        List<SourceReference> sources = relevantChunks.stream()
                .map(d -> new SourceReference(
                        (String) d.getMetadata().get("filename"),
                        "uploaded-pdf",
                        (Integer) d.getMetadata().get("chunk_index")
                ))
                .distinct()
                .toList();

        logInteraction(question, relevantChunks, answer);

        return new ChatResult(answer, sources);
    }

    private void logInteraction(String userMessage,
                                List<Document> retrieved, String response) {
        System.out.printf("""
            Question: %s
            Answer: %s
            ---
            """,
                userMessage, response);
    }
}