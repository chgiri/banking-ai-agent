package com.giri.ai.bankagent.banking;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AccountService {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, List<Transaction>> transactions = new ConcurrentHashMap<>();
    private final Map<String, PendingTransfer> pendingTransfers = new ConcurrentHashMap<>();

    public record PendingTransfer(String fromAccountId, String toAccountId, double amount) {}

    public AccountService() {
        accounts.put("ACC1001", new Account("ACC1001", "Alice Sharma", 45230.50));
        accounts.put("ACC1002", new Account("ACC1002", "Bob Mehta", 12890.00));
        accounts.put("ACC1003", new Account("ACC1003", "Carol D'Souza", 78120.25));

        transactions.put("ACC1001", List.of(
                new Transaction("ACC1001", LocalDate.of(2026, 7, 28), "Grocery Store", -2450.00),
                new Transaction("ACC1001", LocalDate.of(2026, 7, 25), "Salary Credit", 65000.00),
                new Transaction("ACC1001", LocalDate.of(2026, 7, 20), "Electricity Bill", -1800.00)
        ));
        transactions.put("ACC1002", List.of(
                new Transaction("ACC1002", LocalDate.of(2026, 7, 27), "ATM Withdrawal", -5000.00),
                new Transaction("ACC1002", LocalDate.of(2026, 7, 22), "Freelance Payment", 15000.00)
        ));
        transactions.put("ACC1003", List.of(
                new Transaction("ACC1003", LocalDate.of(2026, 7, 29), "Restaurant", -3200.00),
                new Transaction("ACC1003", LocalDate.of(2026, 7, 26), "Salary Credit", 82000.00)
        ));
    }

    public Optional<Account> getAccount(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public List<Transaction> getRecentTransactions(String accountId, int limit) {
        return transactions.getOrDefault(accountId, List.of())
                .stream()
                .limit(limit)
                .toList();
    }

    /** Step 1 of transfer: propose only, no money moves yet. */
    public String proposeTransfer(String fromAccountId, String toAccountId, double amount) {
        Account from = accounts.get(fromAccountId);
        if (from == null) {
            throw new IllegalStateException("Source account not found.");
        }
        if (!accounts.containsKey(toAccountId)) {
            throw new IllegalArgumentException("Destination account " + toAccountId + " does not exist.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive.");
        }
        if (from.balance() < amount) {
            throw new IllegalStateException("Insufficient funds.");
        }

        String confirmationCode = "CONF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        pendingTransfers.put(confirmationCode, new PendingTransfer(fromAccountId, toAccountId, amount));
        return confirmationCode;
    }

    /** Step 2: actually executes, only if the confirmation code matches a pending proposal. */
    public String confirmTransfer(String confirmationCode) {
        PendingTransfer pending = pendingTransfers.remove(confirmationCode);
        if (pending == null) {
            throw new IllegalStateException("No pending transfer found for that confirmation code, or it has already been used.");
        }

        Account from = accounts.get(pending.fromAccountId());
        Account to = accounts.get(pending.toAccountId());

        accounts.put(from.accountId(), new Account(from.accountId(), from.ownerName(), from.balance() - pending.amount()));
        accounts.put(to.accountId(), new Account(to.accountId(), to.ownerName(), to.balance() + pending.amount()));
        transactions.get(from.accountId()).add(new Transaction(from.accountId(), LocalDate.now(), "Transfer to: " + to.accountId(), -pending.amount));
        transactions.get(to.accountId()).add(new Transaction(to.accountId(), LocalDate.now(), "Transfer from: " + from.accountId(), -pending.amount));

        return String.format("Transferred %.2f from %s to %s successfully.",
                pending.amount(), pending.fromAccountId(), pending.toAccountId());
    }
}