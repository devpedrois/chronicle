package com.chronicle.example.domain.event;

import com.chronicle.core.event.DomainEvent;

// [SECURITY] Balance in cents (long) — NEVER use float/double for monetary values
public record MoneyWithdrawn(long amountCents, String description) implements DomainEvent {}
