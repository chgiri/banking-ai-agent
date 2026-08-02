package com.giri.ai.bankagent.config;

import com.giri.ai.bankagent.service.DocumentIngestionService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DataLoaderConfig {

    @Bean
    CommandLineRunner loadData(
            DocumentIngestionService ingestionService,
            JdbcTemplate jdbcTemplate,
            @Value("classpath:data/fd-policy.txt") Resource fdPolicy,
            @Value("classpath:data/account-fees.txt") Resource fees,
            @Value("classpath:data/loan-faqs.txt") Resource loans) {

        return args -> {
            Integer existingCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM vector_store", Integer.class);

            if (existingCount != null && existingCount > 0) {
                System.out.println("Vector store already has " + existingCount
                        + " chunks — skipping ingestion.");
                return;
            }

            ingestionService.ingest(fdPolicy, "fd-policy");
            ingestionService.ingest(fees, "account-fees");
            ingestionService.ingest(loans, "loan-faq");
            System.out.println("Banking documents ingested into vector store.");
        };
    }
}