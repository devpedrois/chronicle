package com.chronicle.example.domain.event;

import com.chronicle.core.event.DomainEvent;

import java.util.UUID;

public record AccountCreated(UUID accountId, String ownerName) implements DomainEvent {}
