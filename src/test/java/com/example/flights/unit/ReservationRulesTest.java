package com.example.flights.unit;

import com.example.flights.api.Requests;
import com.example.flights.exception.IdempotencyKeyConflictException;
import com.example.flights.service.ReservationRules;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final Long FLIGHT_ID = 42L;
    private static final String PASSENGER_NAME = "Avery Passenger";

    @Test
    void bookingCutoffBeginsAt45MinutesBeforeDeparture() {
        Instant cutoff = NOW.plus(Duration.ofMinutes(45));

        assertThat(ReservationRules.isBookingWindowClosed(cutoff.plusNanos(1), NOW)).isFalse();
        assertThat(ReservationRules.isBookingWindowClosed(cutoff, NOW)).isTrue();
        assertThat(ReservationRules.isBookingWindowClosed(cutoff.minusNanos(1), NOW)).isTrue();
    }

    @Test
    void holdExpiresAtItsExpiryInstant() {
        Instant heldUntil = NOW;

        assertThat(ReservationRules.isHoldExpired(heldUntil, NOW.minusNanos(1))).isFalse();
        assertThat(ReservationRules.isHoldExpired(heldUntil, NOW)).isTrue();
        assertThat(ReservationRules.isHoldExpired(heldUntil, NOW.plusNanos(1))).isTrue();
    }

    @Test
    void acceptsMatchingIdempotencyRequest() {
        assertThatCode(() -> ReservationRules.validateIdempotencyRequest(
                FLIGHT_ID, PASSENGER_NAME, FLIGHT_ID, new Requests.CreateBooking(PASSENGER_NAME)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsIdempotencyRequestForDifferentFlight() {
        assertThatThrownBy(() -> ReservationRules.validateIdempotencyRequest(
                FLIGHT_ID, PASSENGER_NAME, FLIGHT_ID + 1,
                new Requests.CreateBooking(PASSENGER_NAME)))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void rejectsIdempotencyRequestForDifferentPassenger() {
        assertThatThrownBy(() -> ReservationRules.validateIdempotencyRequest(
                FLIGHT_ID, PASSENGER_NAME, FLIGHT_ID,
                new Requests.CreateBooking("Different Passenger")))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }
}
