package com.example.flights.service;

import com.example.flights.api.Requests;
import com.example.flights.api.Responses.BookingView;
import com.example.flights.exception.BookingNotAllowedException;
import com.example.flights.exception.FlightNotFoundException;
import com.example.flights.exception.SeatUnavailableException;
import com.example.flights.model.Booking;
import com.example.flights.repo.BookingRepository;
import com.example.flights.repo.FlightRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Service
public class SeatReservationTransactionService {
    private final FlightRepository flightRepository;
    private final BookingRepository bookingRepository;
    private final long holdMinutes;

    public SeatReservationTransactionService(FlightRepository flightRepository, BookingRepository bookingRepository,
                                             @Value("${booking.hold-minutes:10}") long holdMinutes) {
        this.flightRepository = flightRepository;
        this.bookingRepository = bookingRepository;
        this.holdMinutes = holdMinutes;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingView reserveSeat(Long flightId, Requests.CreateBooking request, String idempotencyKey) {
        Optional<Booking> existing = bookingRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existingBooking(existing.get(), flightId, request);
        }

        var flight = flightRepository.findByIdForUpdate(flightId)
                .orElseThrow(() -> new FlightNotFoundException("Flight not found"));
        existing = bookingRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existingBooking(existing.get(), flightId, request);
        }

        Instant now = Instant.now();
        if (ReservationRules.isBookingWindowClosed(flight.getDepartureAt(), now)) {
            throw new BookingNotAllowedException("Booking window closed");
        }

        Set<Integer> occupiedSeats = new HashSet<>(bookingRepository.findOccupiedSeatNumbers(flightId, now));
        int seat = 0;
        for (int i = 1; i <= flight.getCapacity(); i++) {
            if (!occupiedSeats.contains(i)) {
                seat = i;
                break;
            }
        }
        if (seat == 0) {
            throw new SeatUnavailableException("Flight is fully booked");
        }

        Booking booking = new Booking(
                flight,
                request.passengerName(),
                seat,
                now.plus(Duration.ofMinutes(holdMinutes)),
                idempotencyKey
        );
        return BookingView.fromEntity(bookingRepository.save(booking));
    }

    @Transactional(readOnly = true)
    public Optional<BookingView> findByIdempotencyKey(String idempotencyKey) {
        return bookingRepository.findByIdempotencyKey(idempotencyKey)
                .map(BookingView::fromEntity);
    }

    private BookingView existingBooking(Booking booking, Long flightId, Requests.CreateBooking request) {
        ReservationRules.validateIdempotencyRequest(booking.getFlight().getId(), booking.getPassengerName(), flightId, request);
        return BookingView.fromEntity(booking);
    }
}
