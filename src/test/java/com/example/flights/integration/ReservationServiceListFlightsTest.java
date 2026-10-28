package com.example.flights.integration;

import com.example.flights.api.Responses.FlightView;
import com.example.flights.model.Booking;
import com.example.flights.model.BookingStatus;
import com.example.flights.model.Flight;
import com.example.flights.repo.BookingRepository;
import com.example.flights.repo.FlightRepository;
import com.example.flights.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:flights-list-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "booking.expiry-check-ms=600000"
})
@Transactional
class ReservationServiceListFlightsTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private FlightRepository flightRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void listsFlightsUsingGroupedOccupiedSeatCounts() {
        Instant now = Instant.now();
        Flight twoOccupied = createFlight("DUB", 4, now);
        Flight fullyOccupied = createFlight("LHR", 2, now);
        Flight withoutBookings = createFlight("CDG", 3, now);
        Flight oneOccupied = createFlight("AMS", 5, now);

        createBooking(twoOccupied, BookingStatus.CONFIRMED, now.minusSeconds(60));
        createBooking(twoOccupied, BookingStatus.HELD, now.plusSeconds(60));
        createBooking(twoOccupied, BookingStatus.HELD, now.minusSeconds(1));
        createBooking(fullyOccupied, BookingStatus.CONFIRMED, now.minusSeconds(60));
        createBooking(fullyOccupied, BookingStatus.CONFIRMED, now.minusSeconds(60));
        createBooking(oneOccupied, BookingStatus.HELD, now.plusSeconds(60));

        List<FlightView> results = reservationService.listFlights(null, null, null);
        Map<Long, FlightView> viewsById = results.stream()
                .collect(Collectors.toMap(FlightView::id, Function.identity()));

        assertThat(viewsById).containsOnlyKeys(twoOccupied.getId(), withoutBookings.getId(), oneOccupied.getId());
        assertThat(viewsById.get(twoOccupied.getId()).availableSeats()).isEqualTo(2);
        assertThat(viewsById.get(withoutBookings.getId()).availableSeats()).isEqualTo(3);
        assertThat(viewsById.get(oneOccupied.getId()).availableSeats()).isEqualTo(4);
    }

    private Flight createFlight(String airport, int capacity, Instant now) {
        return flightRepository.save(new Flight(
                airport, airport, "DST", "Destination", "UTC", now.plusSeconds(7200), capacity));
    }

    private void createBooking(Flight flight, BookingStatus status, Instant heldUntil) {
        Booking booking = new Booking(flight, "Passenger", 1, heldUntil, java.util.UUID.randomUUID().toString());
        if (status == BookingStatus.CONFIRMED) {
            booking.confirm();
        }
        bookingRepository.save(booking);
    }
}
