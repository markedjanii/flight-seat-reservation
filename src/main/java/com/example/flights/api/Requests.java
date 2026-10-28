package com.example.flights.api;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

public final class Requests {
    private Requests() {
    }

    public record CreateFlight(@NotBlank String originAirport, @NotBlank String originCity,
                               @NotBlank String destinationAirport, @NotBlank String destinationCity,
                               @NotBlank String departureZone, @NotNull LocalDateTime departureLocal,
                               @Min(1) int capacity) {
    }

    public record CreateBooking(@NotBlank String passengerName) {
    }
}
