package com.example.flights.service;

import com.example.flights.api.Requests;
import com.example.flights.exception.IdempotencyKeyConflictException;

import java.time.Duration;
import java.time.Instant;

public final class ReservationRules {
    private ReservationRules() {
    }

    public static boolean isBookingWindowClosed(Instant departureAt, Instant now) {
        return !now.isBefore(departureAt.minus(Duration.ofMinutes(45)));
    }

    public static boolean isHoldExpired(Instant heldUntil, Instant now) {
        return !now.isBefore(heldUntil);
    }

    public static void validateIdempotencyRequest(Long existingFlightId, String existingPassengerName,
                                                  Long flightId, Requests.CreateBooking request) {
        if (!existingFlightId.equals(flightId) || !existingPassengerName.equals(request.passengerName())) {
            throw new IdempotencyKeyConflictException("Idempotency key was already used for a different booking request");
        }
    }
}
