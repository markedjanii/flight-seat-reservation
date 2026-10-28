# Flight Seat Reservation System

A Java 21 and Spring Boot 3.4.5 service for searching flights and reserving seats. It implements the six baseline REST endpoints in the assignment brief. This is a focused coding-assessment implementation, not a production-hardened airline reservation platform.

## Requirements

- Java 21
- Maven 3.6.3 or later

## Build, test, and run

Run these commands from the project root:

```shell
mvn clean test
mvn clean package
mvn spring-boot:run
```

The test suite contains 96 automated tests: unit, integration, MVC/web-layer, and Spring context tests. Integration tests use an H2 test database. No Docker or external database is required.

The application listens at `http://localhost:8080`. Runtime data is stored in the file-based H2 database at `./data/flights` and survives application restarts on the same machine. Hibernate uses `ddl-auto: update` for this assessment.

## REST API

| Method | Endpoint | Purpose | Success |
|---|---|---|---|
| `GET` | `/flights` | List future flights with available seats; optional `date` (`YYYY-MM-DD`), `origin`, and `destination` filters | `200 OK` |
| `POST` | `/admin/flights` | Create a flight | `201 Created` |
| `DELETE` | `/admin/flights/{id}` | Delete a flight that has no booking history | `204 No Content` |
| `POST` | `/flights/{id}/bookings` | Hold the lowest-numbered available seat | `201 Created` |
| `POST` | `/bookings/{id}/confirm` | Confirm a held booking | `200 OK` |
| `DELETE` | `/bookings/{id}` | Cancel a held or confirmed booking | `204 No Content` |

Flight creation requires nonblank airport codes, cities, and an IANA departure time zone, a local departure date/time, and a capacity of at least one. The departure must be in the future. For example:

```sh
curl -X POST http://localhost:8080/admin/flights \
  -H 'Content-Type: application/json' \
  -d '{"originAirport":"DUB","originCity":"Dublin","destinationAirport":"LHR","destinationCity":"London","departureZone":"Europe/Dublin","departureLocal":"2027-07-10T14:30:00","capacity":2}'
```

Search flights by route and local departure date at the origin:

```sh
curl 'http://localhost:8080/flights?origin=DUB&destination=LHR&date=2027-07-10'
```

Hold a seat with an opaque, nonblank `Idempotency-Key`. It does **not** have to be a UUID:

```sh
curl -X POST http://localhost:8080/flights/1/bookings \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: booking-request-123' \
  -d '{"passengerName":"Alice Example"}'
```

Use the booking ID returned by the hold request to confirm or cancel:

```sh
curl -X POST http://localhost:8080/bookings/1/confirm
curl -X DELETE http://localhost:8080/bookings/1
```

An admin can delete a flight only if no bookings have ever been recorded for it:

```sh
curl -X DELETE http://localhost:8080/admin/flights/2
```

Replace the example IDs with IDs returned by the service. The examples use a POSIX-style shell; on Windows, run them in Git Bash or adapt quoting for PowerShell. Validation and business errors handled by the API return a JSON body containing `timestamp`, `status`, `error`, and `path`; not-found errors use `404`, invalid requests use `400`, and booking conflicts use `409`.

## Booking rules and design

- **Booking cutoff:** A booking is rejected at or after 45 minutes before departure. Departure is entered as a local date/time plus an IANA time zone, converted to an `Instant`, and compared with the current instant. A date search uses the flight's origin-local date. DST gaps are rejected; an overlap uses the offset selected from the zone rules.
- **Temporary holds:** A new booking starts as `HELD` and occupies its seat until `heldUntil`, initially 10 minutes after creation. The duration is configurable with `booking.hold-minutes`.
- **Expiration:** Availability queries treat a HELD booking as occupying a seat only while `heldUntil` is in the future. Confirmation and cancellation also check expiration directly, so correctness does not depend on the scheduled job. The scheduler runs after a configurable fixed delay (`booking.expiry-check-ms`, 30 seconds by default): it queries candidate flight IDs without loading bookings, then delegates each flight to `BookingExpirationService`. That service starts a separate `READ_COMMITTED` transaction per flight, locks the flight row, loads expired holds, and changes them to `EXPIRED`. It logs and continues after an individual flight fails; cleanup is therefore best-effort, while timestamp checks govern availability and confirmation.
- **Double-booking prevention:** Reservation, confirmation, cancellation, flight deletion, and expiration coordinate through a `PESSIMISTIC_WRITE` lock on the flight row. Reservation, confirmation, cancellation, and expiration use `READ_COMMITTED` transactions. Operations acquire the flight lock before loading and changing relevant booking state; reservation checks occupied seats and saves the booking while holding that lock. The database's unique idempotency-key constraint provides an additional safeguard against duplicate requests.
- **Idempotency:** `Idempotency-Key` is required and must be nonblank; the application does not validate UUID format. Repeating a key for the same flight and passenger returns the original booking without extending its hold. Reusing the key for different request details is rejected with a conflict.
- **Seat allocation and lifecycle:** The lowest available seat number from `1` through the flight capacity is assigned. Confirmed bookings continue to occupy their seats; cancelled and expired bookings do not. Held bookings can be confirmed or cancelled, and confirmed bookings can be cancelled. A flight with any booking history cannot be deleted.
- **Responses:** Flight and booking endpoints return response DTOs rather than entities. Flight search returns only future flights with at least one available seat.

## Limitations and trade-offs

- `/admin/flights` endpoints have **no authentication or authorization**. They are suitable only for local assessment use, not exposure to untrusted clients.
- File-based H2 is convenient for a single local instance; this setup is not intended for multi-instance deployment or production workloads. Pessimistic locking relies on database support and should be validated against the database used for deployment.
- The schema is managed with Hibernate `ddl-auto: update`, not versioned migrations.
- Flight search loads flights and filters them in the service; it has no pagination. There is no payment flow, seat map, or passenger preference support.
- DST overlap selection is implicit rather than user-selectable. A production system may require the caller to choose an explicit offset.
- Cancellation is allowed after departure; no departure-based cancellation cutoff is implemented.
