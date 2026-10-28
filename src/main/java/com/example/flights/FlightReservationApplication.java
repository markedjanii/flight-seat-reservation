package com.example.flights;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FlightReservationApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlightReservationApplication.class, args);
    }
}
