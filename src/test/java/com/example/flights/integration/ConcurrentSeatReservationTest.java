package com.example.flights.integration;

import com.example.flights.api.Requests.CreateBooking;
import com.example.flights.api.Responses.BookingView;
import com.example.flights.exception.SeatUnavailableException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:concurrent-seat-reservation-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "booking.expiry-check-ms=600000"
})
class ConcurrentSeatReservationTest {
    private static final int REQUEST_COUNT = 10;
    private static final int THREAD_COUNT = 10;
    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private FlightRepository flightRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void concurrentReservationsDoNotOverbookFlight() throws Exception {
        Flight flight = flightRepository.saveAndFlush(new Flight(
                "DUB",
                "Dublin",
                "LHR",
                "London",
                "UTC",
                Instant.now().plusSeconds(4 * 60 * 60),
                2
        ));

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReservationResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < REQUEST_COUNT; i++) {
                int requestNumber = i;
                String idempotencyKey = UUID.randomUUID().toString();
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent start");
                    }

                    try {
                        BookingView booking = reservationService.reserveSeat(
                                flight.getId(),
                                new CreateBooking("Passenger " + requestNumber),
                                idempotencyKey
                        );
                        return new ReservationResult(booking, false);
                    } catch (SeatUnavailableException e) {
                        return new ReservationResult(null, true);
                    }
                }));
            }

            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("all worker threads are ready")
                    .isTrue();
            start.countDown();

            List<ReservationResult> results = new ArrayList<>(REQUEST_COUNT);
            for (Future<ReservationResult> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }

            List<BookingView> successfulReservations = results.stream()
                    .filter(result -> !result.unavailable())
                    .map(ReservationResult::booking)
                    .toList();
            long unavailableRequests = results.stream().filter(ReservationResult::unavailable).count();

            assertThat(successfulReservations).hasSize(2);
            assertThat(unavailableRequests).isEqualTo(8);
            assertThat(successfulReservations)
                    .extracting(BookingView::seatNumber)
                    .containsExactlyInAnyOrder(1, 2);

            List<Booking> persistedBookings = bookingRepository.findAll().stream()
                    .filter(booking -> booking.getFlight().getId().equals(flight.getId()))
                    .toList();

            assertThat(bookingRepository.countByFlightId(flight.getId())).isEqualTo(2);
            assertThat(persistedBookings).hasSize(2);
            assertThat(persistedBookings)
                    .allSatisfy(booking -> assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD));
            assertThat(persistedBookings)
                    .extracting(Booking::getSeatNumber)
                    .containsExactlyInAnyOrder(1, 2)
                    .doesNotHaveDuplicates();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThatCode(() -> {
                if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Worker threads did not terminate");
                }
            }).doesNotThrowAnyException();
        }
    }

    private record ReservationResult(BookingView booking, boolean unavailable) {
    }
}
