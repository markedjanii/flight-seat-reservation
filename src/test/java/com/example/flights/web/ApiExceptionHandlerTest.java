package com.example.flights.web;

import com.example.flights.api.FlightController;
import com.example.flights.api.ApiExceptionHandler;
import com.example.flights.exception.*;
import com.example.flights.service.ReservationService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FlightController.class)
@Import(ApiExceptionHandler.class)
class ApiExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReservationService reservationService;

    @Test
    void flightNotFoundReturns404() throws Exception {
        when(reservationService.reserveSeat(eq(1L), any(), eq("key-1"))).thenThrow(new FlightNotFoundException("Flight not found"));

        mockMvc.perform(post("/flights/1/bookings")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passengerName\":\"Alice\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Flight not found"))
                .andExpect(jsonPath("$.path").value("/flights/1/bookings"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void bookingNotFoundReturns404() throws Exception {
        when(reservationService.confirm(7L)).thenThrow(new BookingNotFoundException("Booking not found"));

        mockMvc.perform(post("/bookings/7/confirm"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Booking not found"))
                .andExpect(jsonPath("$.path").value("/bookings/7/confirm"));
    }

    @Test
    void seatUnavailableReturns409() throws Exception {
        when(reservationService.reserveSeat(eq(1L), any(), eq("key-1"))).thenThrow(new SeatUnavailableException("Flight is fully booked"));

        mockMvc.perform(post("/flights/1/bookings")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passengerName\":\"Alice\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Flight is fully booked"))
                .andExpect(jsonPath("$.path").value("/flights/1/bookings"));
    }

    @Test
    void bookingExpiredReturns409() throws Exception {
        when(reservationService.confirm(5L)).thenThrow(new BookingExpiredException("Hold expired"));

        mockMvc.perform(post("/bookings/5/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Hold expired"))
                .andExpect(jsonPath("$.path").value("/bookings/5/confirm"));
    }

    @Test
    void bookingNotAllowedReturns409() throws Exception {
        when(reservationService.confirm(9L)).thenThrow(new BookingNotAllowedException("Booking cannot be confirmed"));

        mockMvc.perform(post("/bookings/9/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Booking cannot be confirmed"))
                .andExpect(jsonPath("$.path").value("/bookings/9/confirm"));
    }

    @Test
    void invalidBusinessRequestReturns400() throws Exception {
        when(reservationService.createFlight(any())).thenThrow(new InvalidBusinessRequestException("Departure must be in the future"));

        mockMvc.perform(post("/admin/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originAirport\":\"DUB\",\"originCity\":\"Dublin\",\"destinationAirport\":\"LHR\",\"destinationCity\":\"London\",\"departureZone\":\"Europe/Dublin\",\"departureLocal\":\"2024-01-01T10:00:00\",\"capacity\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Departure must be in the future"))
                .andExpect(jsonPath("$.path").value("/admin/flights"));
    }

    @Test
    void validationErrorsReturn400() throws Exception {
        mockMvc.perform(post("/admin/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originAirport\":\"\",\"originCity\":\"Dublin\",\"destinationAirport\":\"LHR\",\"destinationCity\":\"London\",\"departureZone\":\"Europe/Dublin\",\"departureLocal\":\"2027-07-10T14:30:00\",\"capacity\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value(Matchers.startsWith("Invalid request: originAirport")))
                .andExpect(jsonPath("$.path").value("/admin/flights"));
    }
}
