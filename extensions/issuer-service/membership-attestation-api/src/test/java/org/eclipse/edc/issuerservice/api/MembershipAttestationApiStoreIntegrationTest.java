package org.eclipse.edc.issuerservice.api;

import io.restassured.common.mapper.TypeRef;
import io.restassured.specification.RequestSpecification;
import org.eclipse.dse.spi.issuerservice.MembershipAttestation;
import org.eclipse.edc.identityhub.api.Versions;
import org.eclipse.edc.issuerservice.defaults.InMemoryMembershipAttestationStore;
import org.eclipse.edc.junit.annotations.ApiTest;
import org.eclipse.edc.query.CriterionOperatorRegistryImpl;
import org.eclipse.edc.web.jersey.testfixtures.RestControllerTestBase;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Exercises {@link MembershipAttestationApiController} wired to a real
 * {@link InMemoryMembershipAttestationStore} (no mock): the HTTP status is asserted together with
 * the state actually persisted in the store, read back through the query endpoint.
 */
@ApiTest
class MembershipAttestationApiStoreIntegrationTest extends RestControllerTestBase {

    private static final String PARTICIPANT_ID = "test-participant";
    private static final String HOLDER_ID = "did:web:consumer.example.com";

    private final InMemoryMembershipAttestationStore store = new InMemoryMembershipAttestationStore(CriterionOperatorRegistryImpl.ofDefaults());

    @Override
    protected Object controller() {
        return new MembershipAttestationApiController(store);
    }

    @Test
    void lifecycle_createUpdateDelete_isReflectedInStore() {
        var id = UUID.randomUUID().toString();

        baseRequest().contentType(JSON).body(dto(id, "FullMember")).post().then().log().ifError().statusCode(204);
        assertThat(findInStore(id)).singleElement().satisfies(a -> {
            assertThat(a.holderId()).isEqualTo(HOLDER_ID);
            assertThat(a.membershipType()).isEqualTo("FullMember");
        });

        baseRequest().contentType(JSON).body(dto(id, "Associate")).put().then().log().ifError().statusCode(204);
        assertThat(findInStore(id)).singleElement().satisfies(a -> assertThat(a.membershipType()).isEqualTo("Associate"));

        baseRequest().delete(id).then().log().ifError().statusCode(204);
        assertThat(findInStore(id)).isEmpty();

        baseRequest().delete(id).then().log().ifError()
                .statusCode(404)
                .body("[0].type", equalTo("ObjectNotFound"))
                .body("[0].message", containsString(id));
    }

    @Test
    void update_unknownId_returns404_andDoesNotCreateIt() {
        var id = UUID.randomUUID().toString();

        baseRequest().contentType(JSON).body(dto(id, "FullMember")).put().then().log().ifError()
                .statusCode(404)
                .body("[0].type", equalTo("ObjectNotFound"))
                .body("[0].message", containsString(id));

        assertThat(findInStore(id)).isEmpty();
    }

    @Test
    void delete_unknownId_returns404() {
        var id = UUID.randomUUID().toString();

        baseRequest().delete(id).then().log().ifError()
                .statusCode(404)
                .body("[0].type", equalTo("ObjectNotFound"))
                .body("[0].message", containsString(id));
    }

    @Test
    void create_existingId_returns409_andKeepsOriginal() {
        var id = UUID.randomUUID().toString();
        baseRequest().contentType(JSON).body(dto(id, "FullMember")).post().then().log().ifError().statusCode(204);

        baseRequest().contentType(JSON).body(dto(id, "Associate")).post().then().log().ifError()
                .statusCode(409)
                .body("[0].message", containsString(id));

        assertThat(findInStore(id)).singleElement().satisfies(a -> assertThat(a.membershipType()).isEqualTo("FullMember"));
    }

    private List<MembershipAttestation> findInStore(String id) {
        return baseRequest().contentType(JSON).post("/request")
                .then().log().ifError().statusCode(200)
                .extract().as(new TypeRef<List<MembershipAttestation>>() {
                })
                .stream().filter(a -> id.equals(a.id())).toList();
    }

    private static MembershipAttestationDto dto(String id, String membershipType) {
        return new MembershipAttestationDto(id, "consumer", HOLDER_ID, membershipType, List.of());
    }

    private RequestSpecification baseRequest() {
        return given()
                .baseUri("http://localhost:" + port + Versions.UNSTABLE + "/participants/" + Base64.getUrlEncoder().encodeToString(PARTICIPANT_ID.getBytes()) + "/attestation-membership")
                .when();
    }
}
