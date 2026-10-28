package com.example.flights.exception;

public class InvalidBusinessRequestException extends BusinessException {
    public InvalidBusinessRequestException(String message) {
        super(message);
    }
}
