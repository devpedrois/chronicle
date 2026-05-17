package com.chronicle.example.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// [SECURITY] Record = explicit allowlist. No version, eventType fields. toAccountId from client is validated against existing accounts in the service.
public record TransferRequest(
        @NotNull UUID toAccountId,
        @NotNull @Min(1) @Max(100_000_000) Long amountCents,
        // [SECURITY] @Pattern rejects control characters — prevents log injection via description field
        @NotBlank @Size(max = 255)
        @Pattern(regexp = "^[^\\p{Cc}]+$", message = "must not contain control characters")
        String description
) {}
