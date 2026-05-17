package com.chronicle.example.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// [SECURITY] Record = explicit allowlist. No version, aggregateType, eventId fields.
public record CreateAccountRequest(
        // [SECURITY] @Pattern rejects Unicode control chars (U+0000–U+001F, U+007F–U+009F).
        // Null bytes truncate C-strings in downstream consumers; newlines enable log injection;
        // C1 controls cause display anomalies and break some HTTP parsers.
        @NotBlank @Size(min = 2, max = 100)
        @Pattern(regexp = "^[^\\p{Cc}]+$", message = "must not contain control characters")
        String ownerName
) {}
