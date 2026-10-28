package com.example.flights.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(
        name = "bookings",
        indexes = {
                @Index(
                        name = "idx_booking_flight_status",
                        columnList = "flight_id,status"
                ),
                @Index(
                        name = "idx_booking_status_held_until_flight",
                        columnList = "status,held_until,flight_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flight_id")
    private Flight flight;
    @Column(nullable = false)
    private String passengerName;
    @Column(nullable = false)
    private int seatNumber;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;
    @Column(nullable = false)
    private Instant heldUntil;
    @Column(unique = true)
    private String idempotencyKey;

    public Booking(Flight flight, String passengerName, int seatNumber, Instant heldUntil, String idempotencyKey) {
        this.flight = flight;
        this.passengerName = passengerName;
        this.seatNumber = seatNumber;
        this.heldUntil = heldUntil;
        this.idempotencyKey = idempotencyKey;
        this.status = BookingStatus.HELD;
    }

    public void confirm() {
        this.status = BookingStatus.CONFIRMED;
    }

    public void cancel() {
        this.status = BookingStatus.CANCELLED;
    }

    public void expire() {
        this.status = BookingStatus.EXPIRED;
    }
}
