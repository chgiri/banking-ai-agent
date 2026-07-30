package com.giri.ai.bankingfaq.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class PdfIngestionService {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public PdfIngestionService(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record IngestResult(String documentId, boolean alreadyExisted) {}

    public IngestResult ingest(Resource pdfResource, String originalFilename, byte[] fileBytes) {
        String contentHash = sha256Hex(fileBytes);

        List<String> existingDocumentIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT metadata->>'documentId' FROM vector_store WHERE metadata->>'contentHash' = ?",
                String.class,
                contentHash
        );

        if (!existingDocumentIds.isEmpty()) {
            return new IngestResult(existingDocumentIds.get(0), true);
        }

        String documentId = UUID.randomUUID().toString();

        TikaDocumentReader reader = new TikaDocumentReader(pdfResource);
        List<Document> rawDocuments = reader.get();

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(350)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = splitter.apply(rawDocuments);

        chunks.forEach(chunk -> {
            chunk.getMetadata().put("documentId", documentId);
            chunk.getMetadata().put("filename", originalFilename);
            chunk.getMetadata().put("contentHash", contentHash);
        });

        vectorStore.add(chunks);

        return new IngestResult(documentId, false);
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}