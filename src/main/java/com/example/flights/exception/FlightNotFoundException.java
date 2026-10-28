package com.example.flights.exception;

public class FlightNotFoundException extends BusinessException {
    public FlightNotFoundException(String message) {
        super(message);
    }
}
