package com.example.flights.repo;

import com.example.flights.model.Flight;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface FlightRepository extends JpaRepository<Flight, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select f from Flight f
            where f.id = :id
            """)
    Optional<Flight> findByIdForUpdate(@Param("id") Long id);
}
