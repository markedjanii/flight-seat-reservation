package com.example.flights.exception;

public class BookingNotAllowedException extends BusinessException {
    public BookingNotAllowedException(String message) {
        super(message);
    }
}
