package org.eclipse.dse.spi.issuerservice;

import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.result.StoreFailure;
import org.eclipse.edc.spi.result.StoreResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.spi.result.StoreFailure.Reason.NOT_FOUND;

public abstract class MembershipAttestationStoreTestBase {

    protected abstract MembershipAttestationStore getStore();

    protected MembershipAttestation getAttestation() {
        return getAttestation(UUID.randomUUID().toString());
    }

    protected MembershipAttestation getAttestation(String id) {
        return new MembershipAttestation(id, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), Instant.now(),
                List.of("EMDS", "TOUR"));
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
                            Comparator.comparing((Instant i) -> i.truncatedTo(ChronoUnit.MILLIS)),
                            Instant.class
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
                            Comparator.comparing((Instant i) -> i.truncatedTo(ChronoUnit.MILLIS)),
                            Instant.class
                    )
                    .isEqualTo(attestation);
        }
    }

    @Nested
    class Update {

        @Test
        @DisplayName("Verify that an existing entity is updated instead")
        void update_exists_shouldUpdate() {
            var attestation = getAttestation();
            getStore().save(attestation);

            var newAttestation = getAttestation(attestation.id());

            getStore().update(newAttestation);

            var actual = getStore().findById(attestation.id());
            assertThat(actual).isNotNull();
            assertThat(actual.membershipType()).isEqualTo(newAttestation.membershipType());
        }

        @Test
        @DisplayName("Verify not found is returned is trying to update attestation that does not exist")
        void update_doesNotExist_shouldUpdate() {
            var attestation = getAttestation();

            var result = getStore().update(attestation);

            assertThat(result.failed()).isTrue();
            assertThat(result.reason()).isEqualTo(StoreFailure.Reason.NOT_FOUND);
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
                    .filter(new Criterion("holderId", "=", attestation1.holderId()))
                    .build();

            var attestationRetrieved = getStore().query(spec);

            assertThat(attestationRetrieved)
                    .isNotNull()
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields("membershipStartDate")
                    .containsExactly(attestation1);
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
            getStore().save(attestation);

            var attestationDeleted = getStore().deleteById(attestation.id());

            assertThat(attestationDeleted).isNotNull().extracting(StoreResult::succeeded).isEqualTo(true);
            assertThat(attestationDeleted.getContent())
                    .usingRecursiveComparison()
                    .withComparatorForType(
                            Comparator.comparing((Instant i) -> i.truncatedTo(ChronoUnit.MILLIS)),
                            Instant.class
                    )
                    .isEqualTo(attestation);

            assertThat(getStore().findById(attestation.id())).isNull();
        }
    }

}
