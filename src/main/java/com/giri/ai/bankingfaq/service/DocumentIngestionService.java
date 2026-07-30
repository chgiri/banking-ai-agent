package com.giri.ai.bankingfaq.service;

import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DocumentIngestionService {

    private final VectorStore vectorStore;

    public DocumentIngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void ingest(Resource resource, String docType) {
        TextReader textReader = new TextReader(resource);
        textReader.getCustomMetadata().put("docType", docType);
        textReader.getCustomMetadata().put("source", resource.getFilename());

        List<Document> documents = textReader.get();

        // Split into chunks - important for retrieval quality
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withEncodingType(EncodingType.CL100K_BASE)
                .withChunkSize(800)
                .withMinChunkSizeChars(350)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = splitter.apply(documents);

        vectorStore.add(chunks);
    }
}