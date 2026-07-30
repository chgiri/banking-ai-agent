package com.giri.ai.bankingfaq.config;

import com.giri.ai.bankingfaq.service.DocumentIngestionService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class DataLoaderConfig {

    @Bean
    CommandLineRunner loadData(
            DocumentIngestionService ingestionService,
            @Value("classpath:data/fd-policy.txt") Resource fdPolicy,
            @Value("classpath:data/account-fees.txt") Resource fees,
            @Value("classpath:data/loan-faqs.txt") Resource loans) {

        return args -> {
            // In a real app you'd guard this so it doesn't re-ingest every restart — a simple check like
            // "if vector store is empty" works fine for a portfolio project.
            ingestionService.ingest(fdPolicy, "fd-policy");
            ingestionService.ingest(fees, "account-fees");
            ingestionService.ingest(loans, "loan-faq");
            System.out.println("Banking documents ingested into vector store.");
        };
    }
}