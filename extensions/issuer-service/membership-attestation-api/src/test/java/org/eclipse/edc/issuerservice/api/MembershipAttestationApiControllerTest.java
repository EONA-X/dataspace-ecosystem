package org.eclipse.edc.issuerservice.api;

import io.restassured.common.mapper.TypeRef;
import io.restassured.specification.RequestSpecification;
import org.eclipse.dse.spi.issuerservice.MembershipAttestation;
import org.eclipse.dse.spi.issuerservice.MembershipAttestationStore;
import org.eclipse.edc.identityhub.api.Versions;
import org.eclipse.edc.junit.annotations.ApiTest;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.result.StoreResult;
import org.eclipse.edc.web.jersey.testfixtures.RestControllerTestBase;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ApiTest
class MembershipAttestationApiControllerTest extends RestControllerTestBase {

    private static final String PARTICIPANT_ID = "test-participant";
    private final MembershipAttestationStore store = mock();

    @Override
    protected Object controller() {
        return new MembershipAttestationApiController(store);
    }

    private RequestSpecification baseRequest() {
        return given()
                .baseUri("http://localhost:" + port + Versions.UNSTABLE + "/participants/" + Base64.getUrlEncoder().encodeToString(PARTICIPANT_ID.getBytes()) + "/attestation-membership")
                .when();
    }

    private static MembershipAttestation getAttestation() {
        return new MembershipAttestation(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                Instant.now(),
                List.of("EMDS"));
    }

    @Nested
    class Query {

        @Test
        void success_withoutBody() {
            var attestation = getAttestation();
            when(store.query(assertArg(spec -> assertThat(spec.getFilterExpression()).isEmpty())))
                    .thenReturn(Stream.of(attestation));

            var retrieved = baseRequest()
                    .contentType(JSON)
                    .post("/request")
                    .then()
                    .log().ifError()
                    .statusCode(200)
                    .contentType(JSON)
                    .extract()
                    .as(new TypeRef<List<MembershipAttestation>>() {
                    });

            assertThat(retrieved)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields("membershipStartDate")
                    .containsExactly(attestation);
        }

        @Test
        void success_withBody() {
            var attestation = getAttestation();
            var criterion = new Criterion("foo", "=", "bar");
            var spec = QuerySpec.Builder.newInstance()
                    .filter(criterion)
                    .build();
            when(store.query(assertArg(s -> assertThat(s.getFilterExpression())
                    .hasSize(1)
                    .allSatisfy(c -> assertThat(c).usingRecursiveComparison().isEqualTo(criterion))
            ))).thenReturn(Stream.of(attestation));

            var retrieved = baseRequest()
                    .contentType(JSON)
                    .body(spec)
                    .post("/request")
                    .then()
                    .log().ifError()
                    .statusCode(200)
                    .contentType(JSON)
                    .extract()
                    .as(new TypeRef<List<MembershipAttestation>>() {
                    });

            assertThat(retrieved)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields("membershipStartDate")
                    .containsExactly(attestation);
        }

        @Test
        void membership_attestation_api_query_empty_returns_empty_list() {
            when(store.query(assertArg(spec -> assertThat(spec.getFilterExpression()).isEmpty())))
                    .thenReturn(Stream.empty());

            var retrieved = baseRequest()
                    .contentType(JSON)
                    .post("/request")
                    .then()
                    .log().ifError()
                    .statusCode(200)
                    .contentType(JSON)
                    .extract()
                    .as(new TypeRef<List<MembershipAttestation>>() {
                    });

            assertThat(retrieved).isEmpty();
        }
    }

    @Nested
    class Save {

        @Test
        void success() {
            var attestation = getAttestation();
            when(store.save(assertArg(a -> assertThat(a.id()).isEqualTo(attestation.id())))).thenReturn(StoreResult.success());

            baseRequest()
                    .contentType(JSON)
                    .body(attestation)
                    .post()
                    .then()
                    .log().ifError()
                    .statusCode(204);

            verify(store).save(assertArg(a -> assertThat(a.id()).isEqualTo(attestation.id())));
        }

        @Test
        void conflict() {
            var attestation = getAttestation();
            when(store.save(assertArg(a -> assertThat(a.id()).isEqualTo(attestation.id()))))
                    .thenReturn(StoreResult.alreadyExists("Membership Attestation already exists"));

            baseRequest()
                    .contentType(JSON)
                    .body(attestation)
                    .post()
                    .then()
                    .log().ifError()
                    .statusCode(409);

            verify(store).save(assertArg(a -> assertThat(a.id()).isEqualTo(attestation.id())));
        }

    }

    @Nested
    class Update {

        @Test
        void success() {
            var attestation = getAttestation();
            when(store.update(assertArg(a -> assertThat(a.id()).isEqualTo(attestation.id())))).thenReturn(StoreResult.success());

            baseRequest()
                    .contentType(JSON)
                    .body(attestation)
                    .put()
                    .then()
                    .log().ifError()
                    .statusCode(204);

            verify(store).update(assertArg(a -> assertThat(a.id()).isEqualTo(attestation.id())));
        }
    }

    @Nested
    class Delete {

        @Test
        void success() {
            var attestationId = UUID.randomUUID().toString();
            when(store.deleteById(attestationId)).thenReturn(StoreResult.success());

            baseRequest()
                    .delete(attestationId)
                    .then()
                    .log().ifError()
                    .statusCode(204);

            verify(store).deleteById(attestationId);
        }
    }

}