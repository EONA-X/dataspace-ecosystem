package org.eclipse.dse.spi.telemetrystorage;

import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.result.StoreResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.spi.result.StoreFailure.Reason.ALREADY_EXISTS;
import static org.eclipse.edc.spi.result.StoreFailure.Reason.NOT_FOUND;

public abstract class TelemetryEventStoreTestBase {

    protected abstract TelemetryEventStore getStore();

    protected TelemetryEvent getAttestation() {
        return getAttestation(UUID.randomUUID().toString());
    }

    protected TelemetryEvent getAttestation(String id) {
        return new TelemetryEvent(
                id,
                UUID.randomUUID().toString(), // contractId
                new java.util.Random().nextBoolean() ? "participant1" : "participant2", // participantId
                200, // responseStatusCode (example value)
                1024, // msgSize (example value)
                null, // csvId (nullable field)
                Timestamp.from(Instant.now())
        );
    }

    @Nested
    class FindById {

        @Test
        void shouldFindEntityById() {
            var attestation = getAttestation();
            getStore().save(attestation);

            var actual = getStore().findById(attestation.id());

            assertThat(actual)
                    .isNotNull()
                    .usingRecursiveComparison()
                    .withComparatorForType(
                            Comparator.comparing((Timestamp t) -> t.toInstant().truncatedTo(ChronoUnit.MILLIS)),
                            Timestamp.class
                    )
                    .isEqualTo(attestation);
        }

        @Test
        @DisplayName("Verify that null is returned when entity not found")
        void findById_notExist() {
            assertThat(getStore().findById("not-exist")).isNull();
        }
    }

    @Nested
    class Save {

        @Test
        void save_success() {
            var attestation = getAttestation();

            getStore().save(attestation);

            assertThat(getStore().findById(attestation.id()))
                    .usingRecursiveComparison()
                    .withComparatorForType(
                            Comparator.comparing((Timestamp t) -> t.toInstant().truncatedTo(ChronoUnit.MILLIS)),
                            Timestamp.class
                    )
                    .isEqualTo(attestation);
        }

        @Test
        @DisplayName("Saving a second event with the same id must be reported as ALREADY_EXISTS, not a generic error")
        void save_duplicateId_shouldReturnAlreadyExists() {
            var attestation = getAttestation("duplicate-id");
            var firstSave = getStore().save(attestation);
            assertThat(firstSave.succeeded()).isTrue();

            var secondSave = getStore().save(getAttestation("duplicate-id"));

            assertThat(secondSave.failed()).isTrue();
            assertThat(secondSave.reason()).isEqualTo(ALREADY_EXISTS);
        }
    }

    @Nested
    class Query {
        @Test
        void query() {
            var attestation1 = getAttestation();
            var attestation2 = getAttestation();

            getStore().save(attestation1);
            getStore().save(attestation2);
            var spec = QuerySpec.Builder.newInstance()
                    .filter(new Criterion("contractId", "=", attestation1.contractId()))
                    .build();

            var attestationRetrieved = getStore().query(spec);

            assertThat(attestationRetrieved)
                    .isNotNull()
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields("timestamp")
                    .allSatisfy(event -> assertThat(event.timestamp().toInstant().truncatedTo(ChronoUnit.MILLIS))
                    .isEqualTo(attestation1.timestamp().toInstant().truncatedTo(ChronoUnit.MILLIS)));

        }
    }

    @Nested
    class DeleteById {

        @Test
        @DisplayName("Delete a record that doesn't exist")
        void doesNotExist() {
            var recordDeleted = getStore().deleteById("id1");

            assertThat(recordDeleted).isNotNull().extracting(StoreResult::reason).isEqualTo(NOT_FOUND);
        }

        @Test
        @DisplayName("Delete a record that exists")
        void exists() {
            var attestation = getAttestation();
            var saveResult = getStore().save(attestation);

            var attestationDeleted = getStore().deleteById(attestation.id());

            assertThat(attestationDeleted).isNotNull().extracting(StoreResult::succeeded).isEqualTo(true);
            assertThat(attestationDeleted.getContent())
                    .usingRecursiveComparison()
                    .withComparatorForType(
                            Comparator.comparing((Timestamp t) -> t.toInstant().truncatedTo(ChronoUnit.MILLIS)),
                            Timestamp.class
                    )
                    .isEqualTo(attestation);

            assertThat(getStore().findById(attestation.id())).isNull();
        }
    }

}
