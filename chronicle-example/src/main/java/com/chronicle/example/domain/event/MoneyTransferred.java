package com.chronicle.example.domain.event;

import com.chronicle.core.event.DomainEvent;

import java.util.UUID;

// [SECURITY] Balance in cents (long) — NEVER use float/double for monetary values
public record MoneyTransferred(UUID toAccountId, long amountCents, String description) implements DomainEvent {}
