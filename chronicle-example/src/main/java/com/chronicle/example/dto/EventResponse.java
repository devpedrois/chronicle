package com.chronicle.example.dto;

import java.time.Instant;
import java.util.Map;

// [SECURITY] Filtered view of event payload — no raw JSONB, no internal engine metadata exposed
public record EventResponse(String eventType, int version, Instant timestamp, Map<String, Object> summary) {}
