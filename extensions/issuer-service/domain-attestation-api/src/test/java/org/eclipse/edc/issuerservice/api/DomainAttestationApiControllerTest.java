package org.eclipse.edc.issuerservice.api;


import io.restassured.common.mapper.TypeRef;
import io.restassured.specification.RequestSpecification;
import org.eclipse.dse.spi.issuerservice.DomainAttestation;
import org.eclipse.dse.spi.issuerservice.DomainAttestationStore;
import org.eclipse.edc.identityhub.api.Versions;
import org.eclipse.edc.issuerservice.defaults.InMemoryDomainAttestationStore;
import org.eclipse.edc.junit.annotations.ApiTest;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.result.StoreResult;
import org.eclipse.edc.web.jersey.testfixtures.RestControllerTestBase;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
class DomainAttestationApiControllerTest extends RestControllerTestBase {
    private static final String PARTICIPANT_ID = "test-participant";
    private final InMemoryDomainAttestationStore store = mock();
    private static final String AUTHORIZED_DOMAIN = "route";

    @Override
    protected Object controller() {
        return new DomainAttestationApiController(store, AUTHORIZED_DOMAIN);
    }

    private RequestSpecification baseRequest() {
        return given()
                .baseUri("http://localhost:" + port + Versions.UNSTABLE + "/participants/" + Base64.getUrlEncoder().encodeToString(PARTICIPANT_ID.getBytes()) + "/attestation-domain")
                .when();
    }

    private static DomainAttestation getAttestation() {
        return getAttestation(AUTHORIZED_DOMAIN);
    }

    private static DomainAttestation getAttestation(String domain) {
        return new DomainAttestation(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                domain == null ? AUTHORIZED_DOMAIN : domain);
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
                    .as(new TypeRef<List<DomainAttestation>>() {
                    });

            assertThat(retrieved).containsExactly(attestation);
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
                    .as(new TypeRef<List<DomainAttestation>>() {
                    });

            assertThat(retrieved)
                    .containsExactly(attestation);
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
        void domain_attestation_api_save_success() {
            var attestation = getAttestation(AUTHORIZED_DOMAIN);
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
                    .thenReturn(StoreResult.alreadyExists(DomainAttestationStore.ATTESTATION_ALREADY_EXISTS.formatted(attestation.id())));

            baseRequest()
                    .contentType(JSON)
                    .body(attestation)
                    .post()
                    .then()
                    .log().ifError()
                    .statusCode(409);

            verify(store).save(assertArg(a -> assertThat(a.id()).isEqualTo(attestation.id())));
        }

        @Test
        void failure_unauthorized_if_issuer_is_not_issuing_authorized_domain() {
            var attestation = getAttestation("air");

            when(store.save(assertArg(a -> assertThat(a.id()).isEqualTo(attestation.id())))).thenReturn(StoreResult.success());

            baseRequest()
                    .contentType(JSON)
                    .body(attestation)
                    .post()
                    .then()
                    .log().ifError()
                    .statusCode(403);
        }
    }

    @Nested
    class Update {

        @Test
        void domain_attestation_api_update_success() {
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

        @Test
        void failure_unauthorized_domain() {
            var attestation = getAttestation("air");

            when(store.save(assertArg(a -> assertThat(a.id()).isEqualTo(attestation.id())))).thenReturn(StoreResult.success());

            baseRequest()
                    .contentType(JSON)
                    .body(attestation)
                    .put()
                    .then()
                    .log().ifError()
                    .statusCode(403);
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