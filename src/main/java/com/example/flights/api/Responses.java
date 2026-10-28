package com.example.flights.api;

import com.example.flights.model.*;

import java.time.*;

public final class Responses {
    private Responses() {
    }

    public record FlightView(Long id, String originAirport, String originCity, String destinationAirport,
                             String destinationCity, String departureZone, Instant departureAt, int capacity,
                             int availableSeats) {
        public static FlightView fromEntity(Flight f, int available) {
            return new FlightView(f.getId(), f.getOriginAirport(), f.getOriginCity(), f.getDestinationAirport(), f.getDestinationCity(),
                             f.getDepartureZone(), f.getDepartureAt(), f.getCapacity(), available);
        }
    }

    public record BookingView(Long id, Long flightId, String passengerName, int seatNumber, BookingStatus status,
                              Instant heldUntil) {
        public static BookingView fromEntity(Booking b) {
            return new BookingView(b.getId(), b.getFlight().getId(), b.getPassengerName(), b.getSeatNumber(), b.getStatus(), b.getHeldUntil());
        }
    }
}
