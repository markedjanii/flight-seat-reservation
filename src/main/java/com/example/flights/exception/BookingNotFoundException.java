package com.example.flights.exception;

public class BookingNotFoundException extends BusinessException {
    public BookingNotFoundException(String message) {
        super(message);
    }
}
