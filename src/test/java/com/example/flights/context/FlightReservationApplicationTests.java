package com.example.flights.context;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:flights-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop","booking.expiry-check-ms=600000"})
class FlightReservationApplicationTests { @Test void contextLoads() {} }
