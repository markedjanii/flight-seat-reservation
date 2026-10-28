package com.example.flights.integration;

import com.example.flights.exception.BookingExpiredException;
import com.example.flights.exception.BookingNotAllowedException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking-cancellation-concurrency-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "booking.expiry-check-ms=600000"
})
class BookingCancellationConcurrencyTest {
    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private BookingExpirationService bookingExpirationService;

    @Autowired
    private FlightRepository flightRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void twoConcurrentCancellationsBothSucceed() throws Exception {
        Booking booking = createBooking(Instant.now().plusSeconds(300));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> cancelWhenStarted(booking.getId(), ready, start));
            Future<?> second = executor.submit(() -> cancelWhenStarted(booking.getId(), ready, start));
            startTogether(ready, start);

            first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertStatus(booking, BookingStatus.CANCELLED);
        } finally {
            stopExecutor(executor, start);
        }
    }

    @Test
    void twoConcurrentConfirmationsBothSucceed() throws Exception {
        Booking booking = createBooking(Instant.now().plusSeconds(300));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> confirmWhenStarted(booking.getId(), ready, start));
            Future<?> second = executor.submit(() -> confirmWhenStarted(booking.getId(), ready, start));
            startTogether(ready, start);

            first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertStatus(booking, BookingStatus.CONFIRMED);
        } finally {
            stopExecutor(executor, start);
        }
    }

    @Test
    void concurrentCancellationAndConfirmationFollowFlightLockOrder() throws Exception {
        Booking booking = createBooking(Instant.now().plusSeconds(300));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<OperationResult> confirmation = executor.submit(() -> {
                ready.countDown();
                awaitStart(start);
                try {
                    reservationService.confirm(booking.getId());
                    return new OperationResult(null);
                } catch (RuntimeException exception) {
                    return new OperationResult(exception);
                }
            });
            Future<OperationResult> cancellation = executor.submit(() -> {
                ready.countDown();
                awaitStart(start);
                try {
                    reservationService.cancel(booking.getId());
                    return new OperationResult(null);
                } catch (RuntimeException exception) {
                    return new OperationResult(exception);
                }
            });
            startTogether(ready, start);

            OperationResult confirmationResult = confirmation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            OperationResult cancellationResult = cancellation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(cancellationResult.error()).isNull();
            assertThat(confirmationResult.error() == null
                    || confirmationResult.error() instanceof BookingNotAllowedException).isTrue();
            assertStatus(booking, BookingStatus.CANCELLED);
        } finally {
            stopExecutor(executor, start);
        }
    }

    @Test
    void concurrentCancellationAndExpirationLeaveExpiredBookingExpired() throws Exception {
        Booking booking = createBooking(Instant.now().minusSeconds(1));
        Long flightId = booking.getFlight().getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<OperationResult> cancellation = executor.submit(() -> {
                ready.countDown();
                awaitStart(start);
                try {
                    reservationService.cancel(booking.getId());
                    return new OperationResult(null);
                } catch (RuntimeException exception) {
                    return new OperationResult(exception);
                }
            });
            Future<?> expiration = executor.submit(() -> {
                ready.countDown();
                awaitStart(start);
                bookingExpirationService.expireHoldsForFlight(flightId);
                return null;
            });
            startTogether(ready, start);

            OperationResult cancellationResult = cancellation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            expiration.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(cancellationResult.error()).isInstanceOf(BookingExpiredException.class);
            assertStatus(booking, BookingStatus.EXPIRED);
        } finally {
            stopExecutor(executor, start);
        }
    }

    @Test
    void confirmationCompletesBeforeCancellationAndCancellationThenSucceeds() throws Exception {
        Booking booking = createBooking(Instant.now().plusSeconds(300));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> confirmation = executor.submit(() -> reservationService.confirm(booking.getId()));
            confirmation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            Future<?> cancellation = executor.submit(() -> reservationService.cancel(booking.getId()));
            cancellation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertStatus(booking, BookingStatus.CANCELLED);
        } finally {
            shutdownExecutor(executor);
        }
    }

    @Test
    void cancellationCompletesBeforeConfirmationAndConfirmationIsRejected() throws Exception {
        Booking booking = createBooking(Instant.now().plusSeconds(300));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> cancellation = executor.submit(() -> reservationService.cancel(booking.getId()));
            cancellation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            Future<OperationResult> confirmation = executor.submit(() -> {
                try {
                    reservationService.confirm(booking.getId());
                    return new OperationResult(null);
                } catch (RuntimeException exception) {
                    return new OperationResult(exception);
                }
            });

            assertThat(confirmation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).error())
                    .isInstanceOf(BookingNotAllowedException.class);
            assertStatus(booking, BookingStatus.CANCELLED);
        } finally {
            shutdownExecutor(executor);
        }
    }

    @Test
    void confirmationCompletesBeforeExpirationAndRemainsConfirmed() throws Exception {
        Booking booking = createBooking(Instant.now().plusSeconds(300));
        Long flightId = booking.getFlight().getId();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> confirmation = executor.submit(() -> reservationService.confirm(booking.getId()));
            confirmation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            Instant expiredHeldUntil = Instant.now().minusSeconds(1);
            updateHeldUntilInNewTransaction(booking.getId(), expiredHeldUntil);

            entityManager.clear();
            Booking beforeExpiration = bookingRepository.findById(booking.getId()).orElseThrow();
            assertThat(beforeExpiration.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(beforeExpiration.getHeldUntil()).isBefore(Instant.now());

            Future<?> expiration = executor.submit(() -> bookingExpirationService.expireHoldsForFlight(flightId));
            expiration.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertStatus(booking, BookingStatus.CONFIRMED);
        } finally {
            shutdownExecutor(executor);
        }
    }

    @Test
    void concurrentConfirmationAndExpirationOfExpiredHoldLeaveItExpired() throws Exception {
        Booking booking = createBooking(Instant.now().minusSeconds(1));
        Long flightId = booking.getFlight().getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<OperationResult> confirmation = executor.submit(() -> {
                ready.countDown();
                awaitStart(start);
                try {
                    reservationService.confirm(booking.getId());
                    return new OperationResult(null);
                } catch (RuntimeException exception) {
                    return new OperationResult(exception);
                }
            });
            Future<?> expiration = executor.submit(() -> {
                ready.countDown();
                awaitStart(start);
                bookingExpirationService.expireHoldsForFlight(flightId);
                return null;
            });
            startTogether(ready, start);

            RuntimeException confirmationError =
                    confirmation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).error();
            assertThat(confirmationError).isNotNull();
            assertThat(confirmationError instanceof BookingExpiredException
                    || confirmationError instanceof BookingNotAllowedException).isTrue();
            expiration.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertStatus(booking, BookingStatus.EXPIRED);
        } finally {
            stopExecutor(executor, start);
        }
    }

    @Test
    void expirationCompletesBeforeConfirmationAndConfirmationIsRejected() throws Exception {
        Booking booking = createBooking(Instant.now().minusSeconds(1));
        Long flightId = booking.getFlight().getId();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> expiration = executor.submit(() -> bookingExpirationService.expireHoldsForFlight(flightId));
            expiration.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            Future<OperationResult> confirmation = executor.submit(() -> {
                try {
                    reservationService.confirm(booking.getId());
                    return new OperationResult(null);
                } catch (RuntimeException exception) {
                    return new OperationResult(exception);
                }
            });

            assertThat(confirmation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).error())
                    .isInstanceOf(BookingNotAllowedException.class);
            assertStatus(booking, BookingStatus.EXPIRED);
        } finally {
            shutdownExecutor(executor);
        }
    }

    @Test
    void cancellingLogicallyExpiredHeldBookingDoesNotChangeItsStatus() throws Exception {
        Booking booking = createBooking(Instant.now().minusSeconds(1));

        assertThatCode(() -> reservationService.cancel(booking.getId()))
                .isInstanceOf(BookingExpiredException.class);
        assertStatus(booking, BookingStatus.HELD);
    }

    @Test
    void cancellingAlreadyCancelledBookingIsIdempotent() throws Exception {
        Booking booking = createBooking(Instant.now().plusSeconds(300));
        reservationService.cancel(booking.getId());

        assertThatCode(() -> reservationService.cancel(booking.getId())).doesNotThrowAnyException();
        assertStatus(booking, BookingStatus.CANCELLED);
    }

    private Booking createBooking(Instant heldUntil) {
        Flight flight = flightRepository.saveAndFlush(new Flight(
                "DUB", "Dublin", "LHR", "London", "UTC",
                Instant.now().plusSeconds(4 * 60 * 60), 1));
        return bookingRepository.saveAndFlush(new Booking(flight, "Passenger", 1, heldUntil, null));
    }

    private void assertStatus(Booking booking, BookingStatus expectedStatus) {
        entityManager.clear();
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(expectedStatus);
    }

    private void updateHeldUntilInNewTransaction(Long bookingId, Instant heldUntil) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> jdbcTemplate.update(
                "update bookings set held_until = ? where id = ?",
                Timestamp.from(heldUntil),
                bookingId
        ));
    }

    private Void cancelWhenStarted(Long bookingId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        awaitStart(start);
        reservationService.cancel(bookingId);
        return null;
    }

    private Void confirmWhenStarted(Long bookingId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        awaitStart(start);
        reservationService.confirm(bookingId);
        return null;
    }

    private static void startTogether(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("both operations are ready")
                .isTrue();
        start.countDown();
    }

    private static void awaitStart(CountDownLatch start) throws InterruptedException {
        if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent start");
        }
    }

    private static void stopExecutor(ExecutorService executor, CountDownLatch start) throws InterruptedException {
        start.countDown();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("worker threads terminate")
                .isTrue();
    }

    private static void shutdownExecutor(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("worker threads terminate")
                .isTrue();
    }

    private record OperationResult(RuntimeException error) {
    }
}
