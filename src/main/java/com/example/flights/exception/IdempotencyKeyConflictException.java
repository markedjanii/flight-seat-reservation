package com.example.flights.exception;

public class IdempotencyKeyConflictException extends BusinessException {
    public IdempotencyKeyConflictException(String message) {
        super(message);
    }
}
