package com.chronicle.example.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// [SECURITY] Record = explicit allowlist. No version, aggregateType, eventId fields.
public record CreateAccountRequest(
        @NotBlank @Size(min = 2, max = 100) String ownerName
) {}
