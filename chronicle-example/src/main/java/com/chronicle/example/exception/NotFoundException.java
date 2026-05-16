package com.chronicle.example.exception;

import java.util.UUID;

public class NotFoundException extends RuntimeException {
    public NotFoundException(UUID id) {
        super("Account not found: " + id);
    }
}
