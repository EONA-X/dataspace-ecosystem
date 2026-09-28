package org.eclipse.edc.test.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.specification.RequestSpecification;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import org.apache.http.HttpStatus;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.CredentialFormat;
import org.eclipse.edc.identityhub.api.verifiablecredentials.v1.unstable.model.CredentialDescriptor;
import org.eclipse.edc.identityhub.api.verifiablecredentials.v1.unstable.model.CredentialRequestDto;
import org.hamcrest.core.AnyOf;

import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.eclipse.edc.test.system.ParticipantConstants.CLUSTER_HOSTNAME;
import static org.eclipse.edc.test.system.TestConstants.DEFAULT_TIMEOUT;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

abstract class AbstractEntity {

    protected static final int PROVISIONING_MAX_ATTEMPTS = 5;

    /*
     * Every given() call in this suite went through RestAssured's default HttpClient config,
     * which has no connection/socket timeout at all. A static initializer on the common base class runs once,
     * before any participant fixture (all extend this class) makes its first REST call.
     */
    static {
        RestAssured.config = RestAssuredConfig.config().httpClient(
                HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 10_000)
                        .setParam("http.socket.timeout", (int) DEFAULT_TIMEOUT.toMillis()));
    }

    protected abstract String name();

    /**
     * Matches the did:web scheme actually configured on every deployed component
     * (did.web.url in the Helm values, e.g. did:web:192-168-1-220.nip.io:authority:ih:did).
     * With one namespace per participant, that short hostname doesn't resolve cross-namespace.
     */
    protected String did() {
        return "did:web:" + CLUSTER_HOSTNAME.replace(":", "%3A") + ":" + name() + ":ih:did";
    }

    protected abstract String vaultUrl();

    protected abstract String identityHubIdentityUrl();

    public void requestCredential(String issuerDid, String credentialType, String credentialDefinitionId) {
        // holderPid null → the server assigns a random id (CredentialRequestDto's own javadoc).
        // A fixed value here (e.g. did()+credentialType) is reused identically on every request
        // for this participant/type, and once that exact row has ever been left in a stuck lease
        // (see waitForCredentialType's javadoc), every future request under the same id inherits
        // the same contention. init-dataspace-playground.sh never sets one either — its requests
        // go through cleanly for that reason.
        var dto = new CredentialRequestDto(issuerDid, null, List.of(
                new CredentialDescriptor(CredentialFormat.VC1_0_JWT.name(), credentialType, credentialDefinitionId)));
        given()
                .baseUri(identityHubIdentityUrl())
                .body(dto)
                .contentType(JSON)
                .when()
                .post("/v1alpha/participants/%s/credentials/request".formatted(toBase64(did())))
                .then()
                .statusCode(isStatus2xx());
    }

    /**
     * Filtered server-side by {@code type} rather than listing everything
     * and filtering client-side: the unfiltered list is paginated (default page size), and an
     * authority accumulates far more than that in self-issued BitstringStatusList credentials
     * — the one MembershipCredential/DomainCredential we actually
     * want can silently fall outside the first page and never be found.
     */
    public List<JsonObject> getCredentials(ObjectMapper mapper, String type) throws JsonProcessingException {
        String credentials =  given()
                .baseUri(identityHubIdentityUrl())
                .when()
                .contentType(JSON)
                .queryParam("type", type)
                .get("/v1alpha/participants/%s/credentials".formatted(toBase64(did())))
                .then()
                .statusCode(isStatus2xx()).extract().body().asString();
        return mapper.readValue(credentials, JsonArray.class).stream()
                .map(JsonValue::asJsonObject)
                .toList();
    }

    /**
     * POST {@code path} on {@code request}; a 409 (resource already exists — deterministic id,
     * static fixture content, e.g. a test secret) is treated as success and becomes a silent
     * no-op. Needed specifically for anything backed by Vault (secrets), since that's not
     * Postgres and can't be reset by truncating a table like everything else this suite creates.
     */
    protected void postOrSkip(RequestSpecification request, String path) {
        var response = request.when().post(path);
        if (response.statusCode() != HttpStatus.SC_CONFLICT) {
            response.then().statusCode(isStatus2xx());
        }
    }

    protected void withRetry(int maxAttempts, Runnable action) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                action.run();
                return;
            } catch (Exception | AssertionError e) {
                if (attempt == maxAttempts) {
                    if (e instanceof RuntimeException re) {
                        throw re;
                    }
                    if (e instanceof AssertionError ae) {
                        throw ae;
                    }
                    throw new RuntimeException(e);
                }
            }
        }
    }

    protected String toBase64(String s) {
        return Base64.getUrlEncoder().encodeToString(s.getBytes());
    }

    protected AnyOf<Integer> isStatus2xx() {
        return anyOf(is(200), is(201), is(204));
    }
}
