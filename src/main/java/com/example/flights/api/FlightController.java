package com.example.flights.api;

import com.example.flights.api.Requests.*;
import com.example.flights.api.Responses.*;
import com.example.flights.service.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
public class FlightController {
    private final ReservationService service;

    public FlightController(ReservationService service) {
        this.service = service;
    }

    @GetMapping("/flights")
    public List<FlightView> flights(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                    @RequestParam(required = false) String origin, @RequestParam(required = false) String destination) {
        return service.listFlights(date, origin, destination);
    }

    @PostMapping("/admin/flights")
    @ResponseStatus(HttpStatus.CREATED)
    public FlightView create(@Valid @RequestBody CreateFlight body) {
        return service.createFlight(body);
    }

    @DeleteMapping("/admin/flights/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFlight(@PathVariable Long id) {
        service.deleteFlight(id);
    }

    @PostMapping("/flights/{id}/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView hold(@PathVariable Long id,
                            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                            @Valid @RequestBody CreateBooking body) {
        return service.reserveSeat(id, body, idempotencyKey);
    }

    @PostMapping("/bookings/{id}/confirm")
    public BookingView confirm(@PathVariable Long id) {
        return service.confirm(id);
    }

    @DeleteMapping("/bookings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long id) {
        service.cancel(id);
    }
}
