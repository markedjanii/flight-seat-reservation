package com.example.flights.integration;

import com.example.flights.exception.BookingExpiredException;
import com.example.flights.exception.BookingNotAllowedException;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking-confirmation-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "booking.expiry-check-ms=600000"
})
class BookingConfirmationIntegrationTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private FlightRepository flightRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void confirmsActiveHeldBooking() {
        Booking booking = createBooking(Instant.now().plusSeconds(300));

        reservationService.confirm(booking.getId());

        assertStatus(booking, BookingStatus.CONFIRMED);
    }

    @Test
    void confirmingAlreadyConfirmedBookingIsIdempotent() {
        Booking booking = createBooking(Instant.now().plusSeconds(300));
        booking.confirm();
        bookingRepository.saveAndFlush(booking);
        Instant heldUntil = findBooking(booking).getHeldUntil();

        reservationService.confirm(booking.getId());

        Booking persistedBooking = findBooking(booking);
        assertThat(persistedBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(persistedBooking.getHeldUntil()).isEqualTo(heldUntil);
    }

    @Test
    void rejectsLogicallyExpiredHeldBookingBeforeExpirationSchedulerRuns() {
        Booking booking = createBooking(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> reservationService.confirm(booking.getId()))
                .isInstanceOf(BookingExpiredException.class);

        assertStatus(booking, BookingStatus.HELD);
    }

    @Test
    void rejectsExpiredBooking() {
        Booking booking = createBooking(Instant.now().plusSeconds(300));
        booking.expire();
        bookingRepository.saveAndFlush(booking);

        assertThatThrownBy(() -> reservationService.confirm(booking.getId()))
                .isInstanceOf(BookingNotAllowedException.class);

        assertStatus(booking, BookingStatus.EXPIRED);
    }

    @Test
    void rejectsCancelledBooking() {
        Booking booking = createBooking(Instant.now().plusSeconds(300));
        booking.cancel();
        bookingRepository.saveAndFlush(booking);

        assertThatThrownBy(() -> reservationService.confirm(booking.getId()))
                .isInstanceOf(BookingNotAllowedException.class);

        assertStatus(booking, BookingStatus.CANCELLED);
    }

    @Test
    void nonexistentBookingIdThrowsBookingNotFoundException() {
        assertThatThrownBy(() -> reservationService.confirm(Long.MAX_VALUE))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void confirmedBookingOccupiesSeatAfterOriginalHoldExpires() {
        Booking booking = createBooking(Instant.now().plusSeconds(300));
        reservationService.confirm(booking.getId());
        Instant expiredHoldTime = Instant.now().minusSeconds(1);
        jdbcTemplate.update("update bookings set held_until = ? where id = ?",
                Timestamp.from(expiredHoldTime), booking.getId());
        Instant persistedHeldUntil = findBooking(booking).getHeldUntil();

        List<Integer> occupiedSeats = bookingRepository.findOccupiedSeatNumbers(
                booking.getFlight().getId(), Instant.now());

        assertThat(findBooking(booking).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(persistedHeldUntil).isBefore(Instant.now());
        assertThat(findBooking(booking).getHeldUntil()).isEqualTo(persistedHeldUntil);
        assertThat(occupiedSeats).containsExactly(booking.getSeatNumber());
    }

    private Booking createBooking(Instant heldUntil) {
        Flight flight = flightRepository.saveAndFlush(new Flight(
                "DUB", "Dublin", "LHR", "London", "UTC",
                Instant.now().plusSeconds(4 * 60 * 60), 1));
        return bookingRepository.saveAndFlush(new Booking(flight, "Passenger", 1, heldUntil, null));
    }

    private void assertStatus(Booking booking, BookingStatus expectedStatus) {
        assertThat(findBooking(booking).getStatus()).isEqualTo(expectedStatus);
    }

    private Booking findBooking(Booking booking) {
        return bookingRepository.findById(booking.getId()).orElseThrow();
    }
}
