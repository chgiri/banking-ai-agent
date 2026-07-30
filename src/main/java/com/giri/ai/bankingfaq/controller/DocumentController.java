package com.giri.ai.bankingfaq.controller;

import com.giri.ai.bankingfaq.service.ChatResult;
import com.giri.ai.bankingfaq.service.DocumentQnaService;
import com.giri.ai.bankingfaq.service.PdfIngestionService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final PdfIngestionService pdfIngestionService;
    private final DocumentQnaService documentQnaService;

    public DocumentController(PdfIngestionService pdfIngestionService, DocumentQnaService documentQnaService) {
        this.pdfIngestionService = pdfIngestionService;
        this.documentQnaService = documentQnaService;
    }

    record AskRequest(String question) {}
    record AskResponse(String answer) {}
    record UploadResponse(String documentId, String filename, String message) {}

    @PostMapping("/{documentId}/ask")
    public ChatResult ask(@PathVariable String documentId, @RequestBody AskRequest request) {
        return documentQnaService.ask(documentId, request.question());
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("File must not be empty.");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body("Only PDF files are supported.");
        }

        try {
            Resource resource = toResource(file);
            String documentId = pdfIngestionService.ingest(resource, filename);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new UploadResponse(documentId, filename,
                            "Document uploaded and ingested. Use this documentId to ask questions about it."));

        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body("Failed to read uploaded file: " + e.getMessage());
        }
    }

    private Resource toResource(MultipartFile file) throws IOException {
        return new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };
    }
}