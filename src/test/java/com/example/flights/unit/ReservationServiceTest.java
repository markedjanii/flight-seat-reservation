package com.example.flights.unit;

import com.example.flights.api.Requests;
import com.example.flights.api.Responses.BookingView;
import com.example.flights.exception.*;
import com.example.flights.model.Booking;
import com.example.flights.model.BookingStatus;
import com.example.flights.model.Flight;
import com.example.flights.repo.BookingRepository;
import com.example.flights.repo.FlightRepository;
import com.example.flights.service.BookingExpirationService;
import com.example.flights.service.ReservationService;
import com.example.flights.service.SeatReservationTransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataIntegrityViolationException;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    private static final Long BOOKING_ID = 17L;
    private static final Long FLIGHT_ID = 23L;
    private static final Instant ACTIVE_HELD_UNTIL = Instant.parse("2099-01-15T12:00:00Z");
    private static final Instant EXPIRED_HELD_UNTIL = Instant.parse("2000-01-15T12:00:00Z");

    @Mock
    private FlightRepository flightRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private SeatReservationTransactionService seatReservationTransactionService;

    @Mock
    private BookingExpirationService bookingExpirationService;

    @InjectMocks
    private ReservationService reservationService;

    private Flight flight;

    @BeforeEach
    void setUp() {
        flight = mock(Flight.class);
        lenient().when(flight.getId()).thenReturn(FLIGHT_ID);
        lenient().when(bookingRepository.findFlightIdByBookingId(BOOKING_ID)).thenReturn(Optional.of(FLIGHT_ID));
        lenient().when(flightRepository.findByIdForUpdate(FLIGHT_ID)).thenReturn(Optional.of(flight));
    }

    @Test
    void confirmsActiveHeldBooking() {
        Booking booking = booking(BookingStatus.HELD, ACTIVE_HELD_UNTIL);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        BookingView result = reservationService.confirm(BOOKING_ID);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
        verify(flightRepository).findByIdForUpdate(FLIGHT_ID);
    }

    @Test
    void confirmationOfConfirmedBookingIsIdempotent() {
        Booking booking = booking(BookingStatus.CONFIRMED, ACTIVE_HELD_UNTIL);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        BookingView result = reservationService.confirm(BOOKING_ID);

        assertThat(result.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void confirmationRejectsLogicallyExpiredHeldBooking() {
        Booking booking = booking(BookingStatus.HELD, EXPIRED_HELD_UNTIL);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> reservationService.confirm(BOOKING_ID))
                .isInstanceOf(BookingExpiredException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void confirmationRejectsExpiredBooking() {
        Booking booking = booking(BookingStatus.EXPIRED, EXPIRED_HELD_UNTIL);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> reservationService.confirm(BOOKING_ID))
                .isInstanceOf(BookingNotAllowedException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
    }

    @Test
    void confirmationRejectsCancelledBooking() {
        Booking booking = booking(BookingStatus.CANCELLED, ACTIVE_HELD_UNTIL);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> reservationService.confirm(BOOKING_ID))
                .isInstanceOf(BookingNotAllowedException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void confirmationRejectsMissingBookingBeforeLockingFlight() {
        when(bookingRepository.findFlightIdByBookingId(BOOKING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.confirm(BOOKING_ID))
                .isInstanceOf(BookingNotFoundException.class);

        verifyNoInteractions(flightRepository);
    }

    @Test
    void confirmationRejectsBookingMissingAfterFlightLock() {
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.confirm(BOOKING_ID))
                .isInstanceOf(BookingNotFoundException.class);
        verify(flightRepository).findByIdForUpdate(FLIGHT_ID);
    }

    @Test
    void cancellationChangesHeldBookingToCancelled() {
        Booking booking = booking(BookingStatus.HELD, ACTIVE_HELD_UNTIL);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        reservationService.cancel(BOOKING_ID);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cancellationChangesConfirmedBookingToCancelled() {
        Booking booking = booking(BookingStatus.CONFIRMED, ACTIVE_HELD_UNTIL);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        reservationService.cancel(BOOKING_ID);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cancellationOfCancelledBookingIsIdempotent() {
        Booking booking = booking(BookingStatus.CANCELLED, ACTIVE_HELD_UNTIL);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatCode(() -> reservationService.cancel(BOOKING_ID)).doesNotThrowAnyException();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void cancellationRejectsLogicallyExpiredHeldBooking() {
        Booking booking = booking(BookingStatus.HELD, EXPIRED_HELD_UNTIL);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> reservationService.cancel(BOOKING_ID))
                .isInstanceOf(BookingExpiredException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void cancellationRejectsExpiredBooking() {
        Booking booking = booking(BookingStatus.EXPIRED, EXPIRED_HELD_UNTIL);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> reservationService.cancel(BOOKING_ID))
                .isInstanceOf(BookingExpiredException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
    }

    @Test
    void cancellationRejectsMissingBooking() {
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.cancel(BOOKING_ID))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void deleteFlightRejectsFlightWithBookingHistory() {
        when(flightRepository.findByIdForUpdate(FLIGHT_ID)).thenReturn(Optional.of(flight));
        when(bookingRepository.countByFlightId(FLIGHT_ID)).thenReturn(1L);

        assertThatThrownBy(() -> reservationService.deleteFlight(FLIGHT_ID))
                .isInstanceOf(BookingNotAllowedException.class);

        verify(flightRepository, never()).deleteById(FLIGHT_ID);
    }

    @Test
    void deleteFlightDeletesFlightWithoutBookingHistory() {
        when(flightRepository.findByIdForUpdate(FLIGHT_ID)).thenReturn(Optional.of(flight));
        when(bookingRepository.countByFlightId(FLIGHT_ID)).thenReturn(0L);

        reservationService.deleteFlight(FLIGHT_ID);

        verify(flightRepository).deleteById(FLIGHT_ID);
    }

    @Test
    void deleteFlightRejectsMissingFlight() {
        when(flightRepository.findByIdForUpdate(FLIGHT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.deleteFlight(FLIGHT_ID))
                .isInstanceOf(FlightNotFoundException.class);

        verify(bookingRepository, never()).countByFlightId(FLIGHT_ID);
    }

    @Test
    void reserveSeatDelegatesToTransactionalReservationService() {
        Requests.CreateBooking request = new Requests.CreateBooking("Passenger");
        BookingView expected = new BookingView(BOOKING_ID, FLIGHT_ID, "Passenger", 1,
                BookingStatus.HELD, ACTIVE_HELD_UNTIL);
        when(seatReservationTransactionService.reserveSeat(FLIGHT_ID, request, "key"))
                .thenReturn(expected);

        assertThat(reservationService.reserveSeat(FLIGHT_ID, request, "key")).isEqualTo(expected);
    }

    @Test
    void reserveSeatRecoversMatchingRequestAfterDuplicateKeyViolation() {
        Requests.CreateBooking request = new Requests.CreateBooking("Passenger");
        Booking existing = booking(BookingStatus.HELD, ACTIVE_HELD_UNTIL);
        BookingView existingView = BookingView.fromEntity(existing);
        when(seatReservationTransactionService.reserveSeat(FLIGHT_ID, request, "key"))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(seatReservationTransactionService.findByIdempotencyKey("key"))
                .thenReturn(Optional.of(existingView));

        BookingView result = reservationService.reserveSeat(FLIGHT_ID, request, "key");

        assertThat(result.id()).isNull();
        assertThat(result.passengerName()).isEqualTo("Passenger");
    }

    @Test
    void reserveSeatRejectsDuplicateKeyWithDifferentRequest() {
        Requests.CreateBooking request = new Requests.CreateBooking("Different Passenger");
        Booking existing = booking(BookingStatus.HELD, ACTIVE_HELD_UNTIL);
        BookingView existingView = BookingView.fromEntity(existing);
        when(seatReservationTransactionService.reserveSeat(FLIGHT_ID, request, "key"))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(seatReservationTransactionService.findByIdempotencyKey("key"))
                .thenReturn(Optional.of(existingView));

        assertThatThrownBy(() -> reservationService.reserveSeat(FLIGHT_ID, request, "key"))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void reserveSeatRethrowsDuplicateKeyWhenNoExistingBookingIsFound() {
        Requests.CreateBooking request = new Requests.CreateBooking("Passenger");
        DataIntegrityViolationException failure = new DataIntegrityViolationException("duplicate key");
        when(seatReservationTransactionService.reserveSeat(FLIGHT_ID, request, "key"))
                .thenThrow(failure);
        when(seatReservationTransactionService.findByIdempotencyKey("key"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.reserveSeat(FLIGHT_ID, request, "key"))
                .isSameAs(failure);
    }

    @Test
    void expirationSchedulerContinuesAfterFlightProcessingFailure() {
        when(bookingRepository.findFlightIdsWithExpiredHolds(any(Instant.class)))
                .thenReturn(List.of(FLIGHT_ID, FLIGHT_ID + 1));
        doThrow(new IllegalStateException("flight processing failed"))
                .when(bookingExpirationService).expireHoldsForFlight(FLIGHT_ID);

        assertThatCode(reservationService::expireHolds).doesNotThrowAnyException();

        verify(bookingExpirationService).expireHoldsForFlight(FLIGHT_ID);
        verify(bookingExpirationService).expireHoldsForFlight(FLIGHT_ID + 1);
    }

    @Test
    void createFlightSavesValidFlightAndReturnsItsView() {
        Requests.CreateFlight request = flightRequest("UTC", LocalDateTime.of(2099, 1, 15, 12, 0), 120);
        when(flightRepository.save(any(Flight.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = reservationService.createFlight(request);

        assertThat(result.originAirport()).isEqualTo("DUB");
        assertThat(result.destinationAirport()).isEqualTo("LHR");
        assertThat(result.capacity()).isEqualTo(120);
        assertThat(result.departureZone()).isEqualTo("UTC");
        assertThat(result.departureAt()).isEqualTo(Instant.parse("2099-01-15T12:00:00Z"));
    }

    @Test
    void createFlightRejectsInvalidTimeZone() {
        Requests.CreateFlight request = flightRequest(
                "Not/A-Time-Zone", LocalDateTime.of(2099, 1, 15, 12, 0), 120);

        assertThatThrownBy(() -> reservationService.createFlight(request))
                .isInstanceOf(InvalidBusinessRequestException.class)
                .hasMessage("Invalid IANA time zone");

        verify(flightRepository, never()).save(any(Flight.class));
    }

    @Test
    void createFlightRejectsLocalTimeInDaylightSavingGap() {
        Requests.CreateFlight request = flightRequest(
                "Europe/Dublin", LocalDateTime.of(2027, 3, 28, 1, 30), 120);

        assertThatThrownBy(() -> reservationService.createFlight(request))
                .isInstanceOf(InvalidBusinessRequestException.class)
                .hasMessage("Invalid local departure time (DST gap)");

        verify(flightRepository, never()).save(any(Flight.class));
    }

    @Test
    void createFlightRejectsDepartureInPast() {
        Requests.CreateFlight request = flightRequest(
                "UTC", LocalDateTime.of(2000, 1, 15, 12, 0), 120);

        assertThatThrownBy(() -> reservationService.createFlight(request))
                .isInstanceOf(InvalidBusinessRequestException.class)
                .hasMessage("Departure must be in the future");

        verify(flightRepository, never()).save(any(Flight.class));
    }

    private Booking booking(BookingStatus status, Instant heldUntil) {
        Booking booking = new Booking(flight, "Passenger", 1, heldUntil, "key");
        switch (status) {
            case CONFIRMED -> booking.confirm();
            case CANCELLED -> booking.cancel();
            case EXPIRED -> booking.expire();
            case HELD -> {
            }
        }
        return booking;
    }

    private Requests.CreateFlight flightRequest(String zone, LocalDateTime departure, int capacity) {
        return new Requests.CreateFlight(
                "DUB", "Dublin", "LHR", "London", zone, departure, capacity);
    }
}
