package com.chronicle.example.domain;

import java.util.UUID;

/**
 * Immutable state snapshot of a bank account aggregate.
 * // [SECURITY] Balance in cents (long) — NEVER use float/double for monetary values
 * float/double cause rounding errors: 0.1 + 0.2 != 0.3, which is catastrophic in financial systems.
 */
public record BankAccountState(UUID id, String ownerName, long balanceCents, boolean active) {}
