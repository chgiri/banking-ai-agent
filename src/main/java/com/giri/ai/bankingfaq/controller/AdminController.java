package com.giri.ai.bankingfaq.controller;

import com.giri.ai.bankingfaq.service.DocumentIngestionService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final DocumentIngestionService ingestionService;

    public AdminController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    record IngestResponse(String message, String filename, String docType) {}

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> ingest(
            @RequestParam("file") MultipartFile file,
            @RequestParam("docType") String docType) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("File must not be empty.");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.endsWith(".txt")) {
            return ResponseEntity.badRequest()
                    .body("Only .txt files are supported right now.");
        }

        try {
            Resource resource = toResource(file);
            ingestionService.ingest(resource, docType);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new IngestResponse("Document ingested successfully", filename, docType));

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