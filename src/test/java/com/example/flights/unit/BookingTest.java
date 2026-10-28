package com.example.flights.unit;

import com.example.flights.model.Booking;
import com.example.flights.model.BookingStatus;
import com.example.flights.model.Flight;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BookingTest {
    private static final Instant DEPARTURE_AT = Instant.parse("2027-01-15T12:00:00Z");
    private static final Instant HELD_UNTIL = Instant.parse("2027-01-15T10:10:00Z");
    private static final String PASSENGER_NAME = "Avery Passenger";
    private static final String IDEMPOTENCY_KEY = "reservation-key-123";

    @Test
    void newBookingStartsHeld() {
        Booking booking = createBooking();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void confirmChangesHeldBookingToConfirmed() {
        Booking booking = createBooking();

        booking.confirm();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void cancelChangesHeldBookingToCancelled() {
        Booking booking = createBooking();

        booking.cancel();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cancelChangesConfirmedBookingToCancelled() {
        Booking booking = createBooking();
        booking.confirm();

        booking.cancel();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void expireChangesHeldBookingToExpired() {
        Booking booking = createBooking();

        booking.expire();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
    }

    @Test
    void constructorInitializesBookingFields() {
        Flight flight = createFlight();

        Booking booking = new Booking(
                flight, PASSENGER_NAME, 17, HELD_UNTIL, IDEMPOTENCY_KEY);

        assertThat(booking.getPassengerName()).isEqualTo(PASSENGER_NAME);
        assertThat(booking.getSeatNumber()).isEqualTo(17);
        assertThat(booking.getFlight()).isSameAs(flight);
        assertThat(booking.getHeldUntil()).isEqualTo(HELD_UNTIL);
        assertThat(booking.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    }

    @Test
    void stateTransitionsPreserveUnrelatedBookingFields() {
        Flight flight = createFlight();
        Booking booking = new Booking(flight, PASSENGER_NAME, 17, HELD_UNTIL, IDEMPOTENCY_KEY);

        booking.confirm();
        booking.cancel();
        booking.expire();

        assertThat(booking.getPassengerName()).isEqualTo(PASSENGER_NAME);
        assertThat(booking.getSeatNumber()).isEqualTo(17);
        assertThat(booking.getFlight()).isSameAs(flight);
        assertThat(booking.getHeldUntil()).isEqualTo(HELD_UNTIL);
        assertThat(booking.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    }

    private Booking createBooking() {
        return new Booking(createFlight(), PASSENGER_NAME, 17, HELD_UNTIL, IDEMPOTENCY_KEY);
    }

    private Flight createFlight() {
        return new Flight(
                "DUB", "Dublin", "LHR", "London", "UTC", DEPARTURE_AT, 180);
    }
}
