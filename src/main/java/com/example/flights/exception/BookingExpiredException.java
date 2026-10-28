package com.example.flights.exception;

public class BookingExpiredException extends BusinessException {
    public BookingExpiredException(String message) {
        super(message);
    }
}
