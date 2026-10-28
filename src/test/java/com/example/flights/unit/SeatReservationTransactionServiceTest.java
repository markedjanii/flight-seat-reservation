package com.example.flights.unit;

import com.example.flights.api.Requests;
import com.example.flights.api.Responses.BookingView;
import com.example.flights.exception.BookingNotAllowedException;
import com.example.flights.exception.FlightNotFoundException;
import com.example.flights.exception.IdempotencyKeyConflictException;
import com.example.flights.exception.SeatUnavailableException;
import com.example.flights.model.Booking;
import com.example.flights.model.BookingStatus;
import com.example.flights.model.Flight;
import com.example.flights.repo.BookingRepository;
import com.example.flights.repo.FlightRepository;
import com.example.flights.service.SeatReservationTransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeatReservationTransactionServiceTest {
    private static final Long FLIGHT_ID = 23L;
    private static final Long EXISTING_FLIGHT_ID = 23L;
    private static final String IDEMPOTENCY_KEY = "reservation-key";
    private static final String PASSENGER_NAME = "Avery Passenger";
    private static final Instant FUTURE_DEPARTURE = Instant.parse("2099-01-15T12:00:00Z");

    @Mock
    private FlightRepository flightRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private Flight flight;

    private SeatReservationTransactionService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new SeatReservationTransactionService(flightRepository, bookingRepository, 10);
        lenient().when(flight.getId()).thenReturn(FLIGHT_ID);
    }

    @Test
    void reservesFirstAvailableSeat() {
        stubNewReservation(3, FUTURE_DEPARTURE, List.of());

        BookingView result = reservationService.reserveSeat(
                FLIGHT_ID, request(PASSENGER_NAME), IDEMPOTENCY_KEY);

        assertThat(result.seatNumber()).isEqualTo(1);
        assertSavedBooking(1);
    }

    @Test
    void selectsNextSeatWhenEarlierSeatsAreOccupied() {
        stubNewReservation(4, FUTURE_DEPARTURE, List.of(1, 2));

        BookingView result = reservationService.reserveSeat(
                FLIGHT_ID, request(PASSENGER_NAME), IDEMPOTENCY_KEY);

        assertThat(result.seatNumber()).isEqualTo(3);
        assertSavedBooking(3);
    }

    @Test
    void reusesGapInOccupiedSeatNumbers() {
        stubNewReservation(4, FUTURE_DEPARTURE, List.of(1, 3));

        BookingView result = reservationService.reserveSeat(
                FLIGHT_ID, request(PASSENGER_NAME), IDEMPOTENCY_KEY);

        assertThat(result.seatNumber()).isEqualTo(2);
        assertSavedBooking(2);
    }

    @Test
    void rejectsReservationWhenAllSeatsAreOccupied() {
        stubFlight(FUTURE_DEPARTURE);
        when(flight.getCapacity()).thenReturn(2);
        when(bookingRepository.findOccupiedSeatNumbers(eq(FLIGHT_ID), any(Instant.class)))
                .thenReturn(List.of(1, 2));

        assertThatThrownBy(() -> reservationService.reserveSeat(
                FLIGHT_ID, request(PASSENGER_NAME), IDEMPOTENCY_KEY))
                .isInstanceOf(SeatUnavailableException.class);

        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void rejectsReservationWhenFlightDoesNotExist() {
        when(bookingRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(flightRepository.findByIdForUpdate(FLIGHT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.reserveSeat(
                FLIGHT_ID, request(PASSENGER_NAME), IDEMPOTENCY_KEY))
                .isInstanceOf(FlightNotFoundException.class);

        verify(bookingRepository, never()).findOccupiedSeatNumbers(anyLong(), any(Instant.class));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void rejectsReservationInsideBookingCutoff() {
        Instant departureInsideCutoff = Instant.now().plusSeconds(30 * 60);
        stubFlight(departureInsideCutoff);

        assertThatThrownBy(() -> reservationService.reserveSeat(
                FLIGHT_ID, request(PASSENGER_NAME), IDEMPOTENCY_KEY))
                .isInstanceOf(BookingNotAllowedException.class)
                .hasMessage("Booking window closed");

        verify(bookingRepository, never()).findOccupiedSeatNumbers(anyLong(), any(Instant.class));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void returnsExistingBookingForMatchingIdempotentRetryBeforeLockingFlight() {
        Booking existing = existingBooking(FLIGHT_ID, PASSENGER_NAME);
        when(bookingRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));

        BookingView result = reservationService.reserveSeat(
                FLIGHT_ID, request(PASSENGER_NAME), IDEMPOTENCY_KEY);

        assertThat(result.status()).isEqualTo(BookingStatus.HELD);
        assertThat(result.passengerName()).isEqualTo(PASSENGER_NAME);
        verify(flightRepository, never()).findByIdForUpdate(anyLong());
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void rejectsIdempotentRetryWithConflictingFlightOrPassenger() {
        Booking existing = existingBooking(FLIGHT_ID, PASSENGER_NAME);
        when(bookingRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> reservationService.reserveSeat(
                FLIGHT_ID + 1, request(PASSENGER_NAME), IDEMPOTENCY_KEY))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        assertThatThrownBy(() -> reservationService.reserveSeat(
                FLIGHT_ID, request("Different Passenger"), IDEMPOTENCY_KEY))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void returnsExistingBookingIfRetryAppearsAfterFlightLock() {
        Booking existing = existingBooking(FLIGHT_ID, PASSENGER_NAME);
        when(bookingRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(flightRepository.findByIdForUpdate(FLIGHT_ID)).thenReturn(Optional.of(flight));

        BookingView result = reservationService.reserveSeat(
                FLIGHT_ID, request(PASSENGER_NAME), IDEMPOTENCY_KEY);

        assertThat(result.status()).isEqualTo(BookingStatus.HELD);
        verify(flightRepository).findByIdForUpdate(FLIGHT_ID);
        verify(bookingRepository, never()).findOccupiedSeatNumbers(anyLong(), any(Instant.class));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void savesBookingWithExpectedFieldsAndConfiguredHoldDuration() {
        stubNewReservation(3, FUTURE_DEPARTURE, List.of(1));
        Instant callStarted = Instant.now();

        BookingView result = reservationService.reserveSeat(
                FLIGHT_ID, request(PASSENGER_NAME), IDEMPOTENCY_KEY);
        Instant callCompleted = Instant.now();

        assertThat(result.status()).isEqualTo(BookingStatus.HELD);
        assertThat(result.passengerName()).isEqualTo(PASSENGER_NAME);
        assertThat(result.flightId()).isEqualTo(FLIGHT_ID);
        assertThat(result.seatNumber()).isEqualTo(2);
        assertThat(result.heldUntil()).isBetween(
                callStarted.plusSeconds(600), callCompleted.plusSeconds(600));
        assertSavedBooking(2);
    }

    private void stubNewReservation(int capacity, Instant departureAt, List<Integer> occupiedSeats) {
        stubFlight(departureAt);
        when(flight.getCapacity()).thenReturn(capacity);
        when(bookingRepository.findOccupiedSeatNumbers(eq(FLIGHT_ID), any(Instant.class)))
                .thenReturn(occupiedSeats);
        when(bookingRepository.save(any(Booking.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, Booking.class));
    }

    private void stubFlight(Instant departureAt) {
        when(bookingRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty(), Optional.empty());
        when(flightRepository.findByIdForUpdate(FLIGHT_ID)).thenReturn(Optional.of(flight));
        when(flight.getDepartureAt()).thenReturn(departureAt);
    }

    private void assertSavedBooking(int expectedSeatNumber) {
        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(bookingCaptor.capture());
        Booking saved = bookingCaptor.getValue();

        assertThat(saved.getPassengerName()).isEqualTo(PASSENGER_NAME);
        assertThat(saved.getFlight()).isSameAs(flight);
        assertThat(saved.getSeatNumber()).isEqualTo(expectedSeatNumber);
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(saved.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(saved.getHeldUntil()).isAfter(Instant.now());
    }

    private Booking existingBooking(Long flightId, String passengerName) {
        Flight existingFlight = mock(Flight.class);
        when(existingFlight.getId()).thenReturn(flightId);
        return new Booking(existingFlight, passengerName, 1, FUTURE_DEPARTURE, IDEMPOTENCY_KEY);
    }

    private Requests.CreateBooking request(String passengerName) {
        return new Requests.CreateBooking(passengerName);
    }
}
