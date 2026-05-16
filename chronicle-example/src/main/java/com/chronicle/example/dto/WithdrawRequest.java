package com.chronicle.example.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// [SECURITY] Record = explicit allowlist. No aggregateType, version, eventType fields.
public record WithdrawRequest(
        @NotNull @Min(1) @Max(100_000_000) Long amountCents,
        @NotBlank @Size(max = 255) String description
) {}
