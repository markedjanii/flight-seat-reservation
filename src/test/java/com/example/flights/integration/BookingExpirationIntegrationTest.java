package com.example.flights.integration;

import com.example.flights.api.Responses.BookingView;
import com.example.flights.model.Booking;
import com.example.flights.model.BookingStatus;
import com.example.flights.model.Flight;
import com.example.flights.repo.BookingRepository;
import com.example.flights.repo.FlightRepository;
import com.example.flights.service.BookingExpirationService;
import com.example.flights.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking-expiration-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "booking.expiry-check-ms=600000"
})
class BookingExpirationIntegrationTest {
    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private BookingExpirationService bookingExpirationService;

    @MockitoSpyBean
    private BookingExpirationService bookingExpirationServiceSpy;

    @Autowired
    private FlightRepository flightRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void expiresOnlyEligibleBookingsAcrossFlights() {
        Flight firstFlight = createFlight(3);
        Flight secondFlight = createFlight(2);
        Booking expiredOne = createBooking(firstFlight, Instant.now().minusSeconds(1));
        Booking expiredTwo = createBooking(firstFlight, Instant.now().minusSeconds(2));
        Booking unexpired = createBooking(firstFlight, Instant.now().plusSeconds(60));
        Booking confirmed = createBooking(secondFlight, Instant.now().minusSeconds(1));
        confirmed.confirm();
        confirmed = bookingRepository.saveAndFlush(confirmed);
        Booking expiredOnSecondFlight = createBooking(secondFlight, Instant.now().minusSeconds(1));

        reservationService.expireHolds();

        assertStatus(expiredOne, BookingStatus.EXPIRED);
        assertStatus(expiredTwo, BookingStatus.EXPIRED);
        assertStatus(unexpired, BookingStatus.HELD);
        assertStatus(confirmed, BookingStatus.CONFIRMED);
        assertStatus(expiredOnSecondFlight, BookingStatus.EXPIRED);
    }

    @Test
    void failedFlightDoesNotPreventOtherFlightsFromBeingProcessed() {
        Flight failingFlight = createFlight(1);
        Flight succeedingFlight = createFlight(1);
        Booking failingBooking = createBooking(failingFlight, Instant.now().minusSeconds(1));
        Booking succeedingBooking = createBooking(succeedingFlight, Instant.now().minusSeconds(1));

        doThrow(new IllegalStateException("simulated processing failure"))
                .when(bookingExpirationServiceSpy).expireHoldsForFlight(failingFlight.getId());

        reservationService.expireHolds();

        assertStatus(failingBooking, BookingStatus.HELD);
        assertStatus(succeedingBooking, BookingStatus.EXPIRED);
    }

    @Test
    void concurrentConfirmationAndExpirationLeaveSuccessfulConfirmationIntact() throws Exception {
        Flight flight = createFlight(1);
        Booking booking = createBooking(flight, Instant.now().plusSeconds(300));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<BookingView> confirmation = executor.submit(() -> {
                ready.countDown();
                awaitStart(start);
                return reservationService.confirm(booking.getId());
            });
            Future<?> expiration = executor.submit(() -> {
                ready.countDown();
                awaitStart(start);
                bookingExpirationService.expireHoldsForFlight(flight.getId());
                return null;
            });

            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(confirmation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status())
                    .isEqualTo(BookingStatus.CONFIRMED);
            expiration.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertStatus(booking, BookingStatus.CONFIRMED);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void flightScanReturnsDistinctFlightIdsWithoutLoadingBookings() {
        Flight flight = createFlight(2);
        createBooking(flight, Instant.now().minusSeconds(1));
        createBooking(flight, Instant.now().minusSeconds(2));

        List<Long> flightIds = bookingRepository.findFlightIdsWithExpiredHolds(Instant.now());

        assertThat(flightIds).containsExactly(flight.getId());
    }

    private Flight createFlight(int capacity) {
        return flightRepository.saveAndFlush(new Flight(
                "DUB", "Dublin", "LHR", "London", "UTC",
                Instant.now().plusSeconds(4 * 60 * 60), capacity));
    }

    private Booking createBooking(Flight flight, Instant heldUntil) {
        return bookingRepository.saveAndFlush(new Booking(
                flight, "Passenger", 1, heldUntil, null));
    }

    private void assertStatus(Booking booking, BookingStatus expectedStatus) {
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(expectedStatus);
    }

    private static void awaitStart(CountDownLatch start) throws InterruptedException {
        if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent start");
        }
    }
}
