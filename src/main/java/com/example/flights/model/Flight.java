package com.example.flights.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "flights")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Flight {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String originAirport;
    @Column(nullable = false)
    private String originCity;
    @Column(nullable = false)
    private String destinationAirport;
    @Column(nullable = false)
    private String destinationCity;
    @Column(nullable = false)
    private String departureZone;
    @Column(nullable = false)
    private Instant departureAt;
    @Column(nullable = false)
    private int capacity;

    public Flight(String originAirport, String originCity, String destinationAirport, String destinationCity, String departureZone, Instant departureAt, int capacity) {
        this.originAirport = originAirport;
        this.originCity = originCity;
        this.destinationAirport = destinationAirport;
        this.destinationCity = destinationCity;
        this.departureZone = departureZone;
        this.departureAt = departureAt;
        this.capacity = capacity;
    }

}
