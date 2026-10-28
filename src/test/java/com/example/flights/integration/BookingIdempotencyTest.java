package com.example.flights.integration;

import com.example.flights.api.Requests.CreateBooking;
import com.example.flights.api.Responses.BookingView;
import com.example.flights.exception.IdempotencyKeyConflictException;
import com.example.flights.model.Booking;
import com.example.flights.model.Flight;
import com.example.flights.repo.BookingRepository;
import com.example.flights.repo.FlightRepository;
import com.example.flights.service.ReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:booking-idempotency-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "booking.expiry-check-ms=600000"
})
@AutoConfigureMockMvc
class BookingIdempotencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FlightRepository flightRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void retryReturnsOriginalBookingWithoutExtendingHold() throws Exception {
        Flight flight = createFlight(3);
        String key = UUID.randomUUID().toString();

        BookingView first = postBooking(flight.getId(), key, "Alice");
        Instant originalHeldUntil = bookingRepository.findByIdempotencyKey(key).orElseThrow().getHeldUntil();
        BookingView retry = postBooking(flight.getId(), key, "Alice");

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.seatNumber()).isEqualTo(first.seatNumber());
        assertThat(retry.heldUntil()).isEqualTo(originalHeldUntil);
        assertThat(bookingsForKey(key)).hasSize(1);
    }

    @Test
    void differentKeyCreatesAnotherBooking() throws Exception {
        Flight flight = createFlight(3);

        BookingView first = postBooking(flight.getId(), UUID.randomUUID().toString(), "Alice");
        BookingView second = postBooking(flight.getId(), UUID.randomUUID().toString(), "Alice");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.seatNumber()).isNotEqualTo(first.seatNumber());
    }

    @Test
    void reusingKeyWithDifferentRequestReturnsConflict() throws Exception {
        Flight flight = createFlight(3);
        Flight otherFlight = createFlight(3);
        String key = UUID.randomUUID().toString();
        postBooking(flight.getId(), key, "Alice");

        mockMvc.perform(post("/flights/{id}/bookings", flight.getId())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBooking("Bob"))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/flights/{id}/bookings", otherFlight.getId())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBooking("Alice"))))
                .andExpect(status().isConflict());

        assertThat(bookingsForKey(key)).hasSize(1);
    }

    @Test
    void retryAfterBookingWindowClosesReturnsOriginalBooking() throws Exception {
        Flight flight = createFlight(2);
        String key = UUID.randomUUID().toString();
        BookingView first = postBooking(flight.getId(), key, "Alice");

        jdbcTemplate.update("update flights set departure_at = ? where id = ?",
                Timestamp.from(Instant.now().plusSeconds(30 * 60)), flight.getId());

        BookingView retry = postBooking(flight.getId(), key, "Alice");
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.heldUntil()).isEqualTo(bookingRepository.findByIdempotencyKey(key).orElseThrow().getHeldUntil());
    }

    @Test
    void retryAfterHoldExpiresReturnsOriginalBooking() throws Exception {
        Flight flight = createFlight(2);
        String key = UUID.randomUUID().toString();
        BookingView first = postBooking(flight.getId(), key, "Alice");
        Booking booking = bookingRepository.findByIdempotencyKey(key).orElseThrow();
        jdbcTemplate.update("update bookings set held_until = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), booking.getId());
        Instant persistedHeldUntil = bookingRepository.findByIdempotencyKey(key).orElseThrow().getHeldUntil();

        BookingView retry = postBooking(flight.getId(), key, "Alice");

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.heldUntil()).isEqualTo(persistedHeldUntil);
        assertThat(bookingsForKey(key)).hasSize(1);
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOneBooking() throws Exception {
        Flight flight = createFlight(3);
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<BookingView> first = executor.submit(() -> {
                start.await();
                return reservationService.reserveSeat(flight.getId(), new CreateBooking("Alice"), key);
            });
            Future<BookingView> second = executor.submit(() -> {
                start.await();
                return reservationService.reserveSeat(flight.getId(), new CreateBooking("Alice"), key);
            });

            start.countDown();
            BookingView firstResult = first.get();
            BookingView secondResult = second.get();

            assertThat(secondResult.id()).isEqualTo(firstResult.id());
            assertThat(secondResult.seatNumber()).isEqualTo(firstResult.seatNumber());
            assertThat(bookingsForKey(key)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyForDifferentFlightsCreatesOneBookingAndConflictsForOther() throws Exception {
        Flight firstFlight = createFlight(2);
        Flight secondFlight = createFlight(2);
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<ReservationAttempt> first = executor.submit(() -> attempt(start, firstFlight.getId(), key));
            Future<ReservationAttempt> second = executor.submit(() -> attempt(start, secondFlight.getId(), key));
            start.countDown();

            List<ReservationAttempt> results = List.of(first.get(), second.get());
            assertThat(results.stream().filter(result -> result.booking() != null)).hasSize(1);
            assertThat(results.stream().filter(result -> result.error() instanceof IdempotencyKeyConflictException)).hasSize(1);
            assertThat(bookingsForKey(key)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void idempotencyKeyHeaderIsRequired() throws Exception {
        Flight flight = createFlight(1);

        mockMvc.perform(post("/flights/{id}/bookings", flight.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBooking("Alice"))))
                .andExpect(status().isBadRequest());
    }

    private BookingView postBooking(Long flightId, String key, String passengerName) throws Exception {
        MvcResult result = mockMvc.perform(post("/flights/{id}/bookings", flightId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBooking(passengerName))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), BookingView.class);
    }

    private Flight createFlight(int capacity) {
        Instant now = Instant.now();
        return flightRepository.saveAndFlush(new Flight(
                "DUB", "Dublin", "LHR", "London", "UTC", now.plusSeconds(4 * 60 * 60), capacity));
    }

    private List<Booking> bookingsForKey(String key) {
        return bookingRepository.findAll().stream()
                .filter(booking -> key.equals(booking.getIdempotencyKey()))
                .toList();
    }

    private ReservationAttempt attempt(CountDownLatch start, Long flightId, String key) throws InterruptedException {
        start.await();
        try {
            return new ReservationAttempt(
                    reservationService.reserveSeat(flightId, new CreateBooking("Alice"), key),
                    null
            );
        } catch (RuntimeException e) {
            return new ReservationAttempt(null, e);
        }
    }

    private record ReservationAttempt(BookingView booking, RuntimeException error) {
    }
}
