package com.giri.ai.bankingfaq.banking;

import java.time.LocalDate;

public record Transaction(String accountId, LocalDate date, String description, double amount) {}