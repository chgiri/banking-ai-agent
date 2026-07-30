package com.giri.ai.bankingfaq.controller;

import com.giri.ai.bankingfaq.service.ChatResult;
import com.giri.ai.bankingfaq.service.DocumentQnaService;
import com.giri.ai.bankingfaq.service.PdfIngestionService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final PdfIngestionService pdfIngestionService;
    private final DocumentQnaService documentQnaService;
    private final JdbcTemplate jdbcTemplate;

    public DocumentController(PdfIngestionService pdfIngestionService,
                              DocumentQnaService documentQnaService,
                              JdbcTemplate jdbcTemplate) {
        this.pdfIngestionService = pdfIngestionService;
        this.documentQnaService = documentQnaService;
        this.jdbcTemplate = jdbcTemplate;
    }
    record AskRequest(String question) {}
    record UploadResponse(String documentId, String filename, String message) {}
    record DocumentStatusResponse(String documentId, String filename, int chunkCount, boolean found) {}


    @PostMapping("/{documentId}/ask")
    public ChatResult ask(@PathVariable String documentId, @RequestBody AskRequest request) {
        return documentQnaService.ask(documentId, request.question());
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentStatusResponse> getStatus(@PathVariable String documentId) {

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT metadata->>'filename' AS filename FROM vector_store WHERE metadata->>'documentId' = ?",
                documentId
        );

        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String filename = (String) rows.get(0).get("filename");

        return ResponseEntity.ok(new DocumentStatusResponse(documentId, filename, rows.size(), true));
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
            byte[] fileBytes = file.getBytes();
            Resource resource = toResource(file, fileBytes);

            PdfIngestionService.IngestResult result =
                    pdfIngestionService.ingest(resource, filename, fileBytes);

            String message = result.alreadyExisted()
                    ? "This exact file was already uploaded — returning the existing documentId."
                    : "Document uploaded and ingested. Use this documentId to ask questions about it.";

            return ResponseEntity.status(result.alreadyExisted() ? HttpStatus.OK : HttpStatus.CREATED)
                    .body(new UploadResponse(result.documentId(), filename, message));

        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body("Failed to read uploaded file: " + e.getMessage());
        }
    }

    private Resource toResource(MultipartFile file, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };
    }
}