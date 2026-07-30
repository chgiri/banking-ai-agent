package com.giri.ai.bankingfaq.service;

import java.util.List;

public record ChatResult(String answer, List<SourceReference> sources) {

    public record SourceReference(String source, String docType, Integer chunkIndex) {}
}