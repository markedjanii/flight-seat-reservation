package com.example.flights.service;

import com.example.flights.api.*;
import com.example.flights.api.Responses.*;
import com.example.flights.exception.*;
import com.example.flights.model.*;
import com.example.flights.repo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@Slf4j
public class ReservationService {
    private final FlightRepository flightRepository;
    private final BookingRepository bookingRepository;
    private final SeatReservationTransactionService seatReservationTransactionService;
    private final BookingExpirationService bookingExpirationService;

    public ReservationService(FlightRepository flightRepository, BookingRepository bookingRepository,
                              SeatReservationTransactionService seatReservationTransactionService,
                              BookingExpirationService bookingExpirationService) {
        this.flightRepository = flightRepository;
        this.bookingRepository = bookingRepository;
        this.seatReservationTransactionService = seatReservationTransactionService;
        this.bookingExpirationService = bookingExpirationService;
    }

    @Transactional
    public FlightView createFlight(Requests.CreateFlight request) {
        ZoneId zone;
        try {
            zone = ZoneId.of(request.departureZone());
        } catch (DateTimeException e) {
            throw new InvalidBusinessRequestException("Invalid IANA time zone");
        }
        ZonedDateTime departure;
        try {
            departure = ZonedDateTime.ofStrict(request.departureLocal(), zone.getRules().getOffset(request.departureLocal()), zone);
        } catch (DateTimeException e) {
            throw new InvalidBusinessRequestException("Invalid local departure time (DST gap)");
        }
        if (!departure.toInstant().isAfter(Instant.now())) {
            throw new InvalidBusinessRequestException("Departure must be in the future");
        }
        Flight flight = new Flight(
                request.originAirport(),
                request.originCity(),
                request.destinationAirport(),
                request.destinationCity(),
                zone.getId(),
                departure.toInstant(),
                request.capacity()
        );
        flight = flightRepository.save(flight);
        return FlightView.fromEntity(flight, flight.getCapacity());
    }

    @Transactional(readOnly = true)
    public List<FlightView> listFlights(LocalDate date, String origin, String destination) {
        Instant now = Instant.now();
        List<Flight> matchingFlights = flightRepository.findAll().stream()
                .filter(f -> f.getDepartureAt().isAfter(now))
                .filter(f -> origin == null || f.getOriginAirport().equalsIgnoreCase(origin))
                .filter(f -> destination == null || f.getDestinationAirport().equalsIgnoreCase(destination))
                .filter(f -> date == null || f.getDepartureAt().atZone(ZoneId.of(f.getDepartureZone())).toLocalDate().equals(date))
                .toList();

        if (matchingFlights.isEmpty()) {
            return List.of();
        }

        List<Long> flightIds = matchingFlights.stream().map(Flight::getId).toList();
        Map<Long, Long> occupiedSeatsByFlightId = new HashMap<>();
        List<BookingRepository.OccupiedSeatCount> occupiedSeatCounts = bookingRepository.countOccupiedSeatsByFlightIds(flightIds, now);
        for (BookingRepository.OccupiedSeatCount count : occupiedSeatCounts) {
            occupiedSeatsByFlightId.put(count.getFlightId(), count.getOccupiedSeats());
        }

        return matchingFlights.stream()
                .map(f -> {
                    long availableSeats = Math.max(0L, f.getCapacity() - occupiedSeatsByFlightId.getOrDefault(f.getId(), 0L));
                    return FlightView.fromEntity(f, Math.toIntExact(availableSeats));
                })
                .filter(f -> f.availableSeats() > 0)
                .toList();
    }

    public BookingView reserveSeat(Long flightId, Requests.CreateBooking request, String idempotencyKey) {
        try {
            return seatReservationTransactionService.reserveSeat(flightId, request, idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            return seatReservationTransactionService.findByIdempotencyKey(idempotencyKey)
                    .map(booking -> {
                        ReservationRules.validateIdempotencyRequest(booking.flightId(), booking.passengerName(), flightId, request);
                        return booking;
                    }).orElseThrow(() -> e);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingView confirm(Long bookingId) {
        Long flightId = bookingRepository.findFlightIdByBookingId(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found"));
        flightRepository.findByIdForUpdate(flightId)
                .orElseThrow(() -> new FlightNotFoundException("Flight not found"));
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found"));
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            return BookingView.fromEntity(booking);
        }
        if (booking.getStatus() != BookingStatus.HELD) {
            throw new BookingNotAllowedException("Booking cannot be confirmed");
        }
        Instant now = Instant.now();
        if (ReservationRules.isHoldExpired(booking.getHeldUntil(), now)) {
            throw new BookingExpiredException("Hold expired");
        }
        booking.confirm();
        return BookingView.fromEntity(booking);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancel(Long bookingId) {
        Long flightId = bookingRepository.findFlightIdByBookingId(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found"));
        flightRepository.findByIdForUpdate(flightId)
                .orElseThrow(() -> new FlightNotFoundException("Flight not found"));
        Booking b = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Booking not found"));
        if (b.getStatus() == BookingStatus.CANCELLED) {
            return;
        }
        if (b.getStatus() == BookingStatus.EXPIRED ||
                (b.getStatus() == BookingStatus.HELD && ReservationRules.isHoldExpired(b.getHeldUntil(), Instant.now()))) {
            throw new BookingExpiredException("Booking already expired");
        }
        b.cancel();
    }

    @Transactional
    public void deleteFlight(Long id) {
        flightRepository.findByIdForUpdate(id).orElseThrow(() -> new FlightNotFoundException("Flight not found"));
        if (bookingRepository.countByFlightId(id) > 0)
            throw new BookingNotAllowedException("Flight has booking history; deletion refused");
        flightRepository.deleteById(id);
    }

    @Scheduled(fixedDelayString = "${booking.expiry-check-ms:30000}")
    public void expireHolds() {
        Instant now = Instant.now();
        for (Long flightId : bookingRepository.findFlightIdsWithExpiredHolds(now)) {
            try {
                bookingExpirationService.expireHoldsForFlight(flightId);
            } catch (RuntimeException e) {
                log.error("Failed to expire holds for flight {}", flightId, e);
            }
        }
    }
}
