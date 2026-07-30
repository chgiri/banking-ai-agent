package com.giri.ai.bankingfaq.banking;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

public class AccountTools {

    private final AccountService accountService;
    private final String currentAccountId; // bound once, never exposed to the model as a parameter

    public AccountTools(AccountService accountService, String currentAccountId) {
        this.accountService = accountService;
        this.currentAccountId = currentAccountId;
    }

    @Tool(description = "Get the current user's account balance.")
    public String getBalance() {
        return accountService.getAccount(currentAccountId)
                .map(a -> String.format("Your current balance is %.2f", a.balance()))
                .orElse("Account not found.");
    }

    @Tool(description = "List the current user's recent transactions.")
    public String listRecentTransactions(
            @ToolParam(description = "How many recent transactions to return, e.g. 5") int limit) {

        List<Transaction> txns = accountService.getRecentTransactions(currentAccountId, limit);
        if (txns.isEmpty()) {
            return "No recent transactions found.";
        }

        StringBuilder sb = new StringBuilder();
        for (Transaction t : txns) {
            sb.append(String.format("%s | %s | %.2f%n", t.date(), t.description(), t.amount()));
        }
        return sb.toString();
    }

    @Tool(description = "Propose a fund transfer from the current user's account to another account ID. " +
            "This does NOT move money yet — it only returns a confirmation code that the user must " +
            "explicitly confirm before the transfer executes.")
    public String proposeTransfer(
            @ToolParam(description = "Destination account ID, e.g. ACC1002") String toAccountId,
            @ToolParam(description = "Amount to transfer") double amount) {

        try {
            String code = accountService.proposeTransfer(currentAccountId, toAccountId, amount);
            return "Transfer proposed: " + amount + " to " + toAccountId
                    + ". Ask the user to confirm using confirmation code: " + code;
        } catch (Exception e) {
            return "Could not propose transfer: " + e.getMessage();
        }
    }

    @Tool(description = "Confirm and execute a previously proposed fund transfer, using the confirmation code.")
    public String confirmTransfer(
            @ToolParam(description = "The confirmation code returned by proposeTransfer") String confirmationCode) {

        try {
            return accountService.confirmTransfer(confirmationCode);
        } catch (Exception e) {
            return "Could not confirm transfer: " + e.getMessage();
        }
    }
}