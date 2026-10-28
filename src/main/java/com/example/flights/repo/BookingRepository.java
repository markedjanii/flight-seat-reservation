package com.example.flights.repo;

import com.example.flights.model.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    @Query("""
            select b.seatNumber from Booking b
            where b.flight.id = :flightId
              and (b.status = BookingStatus.CONFIRMED
                   or (b.status = BookingStatus.HELD and b.heldUntil > :now))
            """)
    List<Integer> findOccupiedSeatNumbers(@Param("flightId") Long flightId, @Param("now") Instant now);

    @Query("""
            select b.flight.id as flightId, count(b) as occupiedSeats
            from Booking b
            where b.flight.id in :flightIds
              and (b.status = BookingStatus.CONFIRMED
                   or (b.status = BookingStatus.HELD and b.heldUntil > :now))
            group by b.flight.id
            """)
    List<OccupiedSeatCount> countOccupiedSeatsByFlightIds(@Param("flightIds") Collection<Long> flightIds, @Param("now") Instant now);

    long countByFlightId(Long flightId);

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT b.flight.id FROM Booking b WHERE b.id = :bookingId")
    Optional<Long> findFlightIdByBookingId(@Param("bookingId") Long bookingId);

    @Transactional(readOnly = true)
    @Query("""
            SELECT DISTINCT b.flight.id
            FROM Booking b
            WHERE b.status = BookingStatus.HELD
              AND b.heldUntil <= :now
            """)
    List<Long> findFlightIdsWithExpiredHolds(@Param("now") Instant now);

    @Query("""
            SELECT b FROM Booking b
            WHERE b.flight.id = :flightId
              AND b.status = BookingStatus.HELD
              AND b.heldUntil <= :now
            """)
    List<Booking> findExpiredHoldsByFlightId(
            @Param("flightId") Long flightId,
            @Param("now") Instant now
    );

    interface OccupiedSeatCount {
        Long getFlightId();

        Long getOccupiedSeats();
    }
}
