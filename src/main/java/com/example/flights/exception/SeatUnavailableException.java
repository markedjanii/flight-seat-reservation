package com.example.flights.exception;

public class SeatUnavailableException extends BusinessException {
    public SeatUnavailableException(String message) {
        super(message);
    }
}
