package com.example.flights.integration;

import com.example.flights.api.Requests.CreateBooking;
import com.example.flights.api.Responses.BookingView;
import com.example.flights.exception.BookingExpiredException;
import com.example.flights.exception.BookingNotFoundException;
import com.example.flights.model.Booking;
import com.example.flights.model.BookingStatus;
import com.example.flights.model.Flight;
import com.example.flights.repo.BookingRepository;
import com.example.flights.repo.FlightRepository;
import com.example.flights.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking-cancellation-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "booking.expiry-check-ms=600000"
})
class BookingCancellationIntegrationTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private FlightRepository flightRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void cancelsActiveHeldBooking() {
        Booking booking = createBooking(createFlight(1), Instant.now().plusSeconds(300));

        reservationService.cancel(booking.getId());

        assertStatus(booking, BookingStatus.CANCELLED);
    }

    @Test
    void cancelsConfirmedBooking() {
        Booking booking = createBooking(createFlight(1), Instant.now().plusSeconds(300));
        reservationService.confirm(booking.getId());

        reservationService.cancel(booking.getId());

        assertStatus(booking, BookingStatus.CANCELLED);
    }

    @Test
    void cancellingAlreadyCancelledBookingIsIdempotent() {
        Booking booking = createBooking(createFlight(1), Instant.now().plusSeconds(300));
        reservationService.cancel(booking.getId());

        assertThatCode(() -> reservationService.cancel(booking.getId())).doesNotThrowAnyException();

        assertStatus(booking, BookingStatus.CANCELLED);
    }

    @Test
    void rejectsLogicallyExpiredHeldBookingAndLeavesItHeld() {
        Booking booking = createBooking(createFlight(1), Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> reservationService.cancel(booking.getId()))
                .isInstanceOf(BookingExpiredException.class);

        assertStatus(booking, BookingStatus.HELD);
    }

    @Test
    void rejectsExpiredBooking() {
        Booking booking = createBooking(createFlight(1), Instant.now().plusSeconds(300));
        booking.expire();
        bookingRepository.saveAndFlush(booking);

        assertThatThrownBy(() -> reservationService.cancel(booking.getId()))
                .isInstanceOf(BookingExpiredException.class);

        assertStatus(booking, BookingStatus.EXPIRED);
    }

    @Test
    void nonexistentBookingThrowsBookingNotFoundException() {
        assertThatThrownBy(() -> reservationService.cancel(Long.MAX_VALUE))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void cancellingHeldBookingMakesItsSeatAvailable() {
        Flight flight = createFlight(1);
        BookingView held = reserveSeat(flight, "Held passenger");

        reservationService.cancel(held.id());

        BookingView replacement = reserveSeat(flight, "Replacement passenger");

        assertThat(replacement.seatNumber()).isEqualTo(held.seatNumber());
        assertStatus(bookingRepository.findById(held.id()).orElseThrow(), BookingStatus.CANCELLED);
        assertStatus(bookingRepository.findById(replacement.id()).orElseThrow(), BookingStatus.HELD);
    }

    @Test
    void cancellingConfirmedBookingMakesItsSeatAvailable() {
        Flight flight = createFlight(1);
        BookingView held = reserveSeat(flight, "Confirmed passenger");
        reservationService.confirm(held.id());

        reservationService.cancel(held.id());

        BookingView replacement = reserveSeat(flight, "Replacement passenger");

        assertThat(replacement.seatNumber()).isEqualTo(held.seatNumber());
        assertStatus(bookingRepository.findById(held.id()).orElseThrow(), BookingStatus.CANCELLED);
        assertStatus(bookingRepository.findById(replacement.id()).orElseThrow(), BookingStatus.HELD);
    }

    @Test
    void cancellingOneBookingDoesNotAffectOtherBookingsOnFlight() {
        Flight flight = createFlight(3);
        Booking first = createBooking(flight, Instant.now().plusSeconds(300), 1);
        Booking second = createBooking(flight, Instant.now().plusSeconds(300), 2);
        Booking third = createBooking(flight, Instant.now().plusSeconds(300), 3);

        reservationService.cancel(second.getId());

        assertStatus(first, BookingStatus.HELD);
        assertStatus(second, BookingStatus.CANCELLED);
        assertStatus(third, BookingStatus.HELD);
    }

    private Flight createFlight(int capacity) {
        return flightRepository.saveAndFlush(new Flight(
                "DUB", "Dublin", "LHR", "London", "UTC",
                Instant.now().plusSeconds(4 * 60 * 60), capacity));
    }

    private Booking createBooking(Flight flight, Instant heldUntil) {
        return createBooking(flight, heldUntil, 1);
    }

    private Booking createBooking(Flight flight, Instant heldUntil, int seatNumber) {
        return bookingRepository.saveAndFlush(new Booking(
                flight, "Passenger", seatNumber, heldUntil, null));
    }

    private BookingView reserveSeat(Flight flight, String passengerName) {
        return reservationService.reserveSeat(
                flight.getId(),
                new CreateBooking(passengerName),
                UUID.randomUUID().toString());
    }

    private void assertStatus(Booking booking, BookingStatus expectedStatus) {
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(expectedStatus);
    }
}
