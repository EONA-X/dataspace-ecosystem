package org.eclipse.dse.spi.telemetrystorage;

import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.result.StoreResult;

import java.util.stream.Stream;

public interface TelemetryEventStore {

    String TELEMETRY_EVENT_NOT_FOUND = "Telemetry Event with ID %s could not be found";
    String TELEMETRY_EVENT_ALREADY_EXISTS = "Telemetry Event with ID %s already exists";
    String TELEMETRY_EVENT_DUPLICATE_CONTRACT_PARTICIPANT_TIMESTAMP = "Telemetry Event for contract %s, participant %s at %s already exists";

    Stream<TelemetryEvent> query(QuerySpec querySpec);

    TelemetryEvent findById(String id);

    StoreResult<Void> save(TelemetryEvent attestation);

    StoreResult<TelemetryEvent> deleteById(String id);

}
