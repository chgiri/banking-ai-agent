package com.giri.ai.bankingfaq.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class PdfIngestionService {

    private final VectorStore vectorStore;

    public PdfIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public String ingest(Resource pdfResource, String originalFilename) {
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

        // Tag every chunk with the documentId so retrieval can be scoped to this document only
        chunks.forEach(chunk -> {
            chunk.getMetadata().put("documentId", documentId);
            chunk.getMetadata().put("filename", originalFilename);
        });

        vectorStore.add(chunks);

        return documentId;
    }
}