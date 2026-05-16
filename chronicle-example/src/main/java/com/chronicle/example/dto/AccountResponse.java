package com.chronicle.example.dto;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID id, String ownerName, long balanceCents, Instant createdAt) {}
