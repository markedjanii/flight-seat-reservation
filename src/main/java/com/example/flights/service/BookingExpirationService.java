package com.example.flights.service;

import com.example.flights.exception.FlightNotFoundException;
import com.example.flights.model.Booking;
import com.example.flights.repo.BookingRepository;
import com.example.flights.repo.FlightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class BookingExpirationService {
    private final FlightRepository flightRepository;
    private final BookingRepository bookingRepository;

    public BookingExpirationService(FlightRepository flightRepository, BookingRepository bookingRepository) {
        this.flightRepository = flightRepository;
        this.bookingRepository = bookingRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void expireHoldsForFlight(Long flightId) {
        flightRepository.findByIdForUpdate(flightId)
                .orElseThrow(() -> new FlightNotFoundException("Flight not found"));

        Instant now = Instant.now();
        for (Booking booking : bookingRepository.findExpiredHoldsByFlightId(flightId, now)) {
            booking.expire();
        }
    }
}
