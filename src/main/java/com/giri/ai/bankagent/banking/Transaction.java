package com.giri.ai.bankagent.banking;

import java.time.LocalDate;

public record Transaction(String accountId, LocalDate date, String description, double amount) {}