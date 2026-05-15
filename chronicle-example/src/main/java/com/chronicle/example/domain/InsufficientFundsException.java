package com.chronicle.example.domain;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

    private final UUID accountId;
    private final long currentBalance;
    private final long requestedAmount;

    public InsufficientFundsException(UUID accountId, long currentBalance, long requestedAmount) {
        super(String.format("Insufficient funds for account %s: balance=%d, requested=%d",
                accountId, currentBalance, requestedAmount));
        this.accountId = accountId;
        this.currentBalance = currentBalance;
        this.requestedAmount = requestedAmount;
    }

    public UUID getAccountId() { return accountId; }
    public long getCurrentBalance() { return currentBalance; }
    public long getRequestedAmount() { return requestedAmount; }
}
