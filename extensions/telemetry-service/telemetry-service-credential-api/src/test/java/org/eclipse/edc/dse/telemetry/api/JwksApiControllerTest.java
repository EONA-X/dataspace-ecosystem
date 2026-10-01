package org.eclipse.edc.dse.telemetry.api;

import io.restassured.specification.RequestSpecification;
import org.eclipse.edc.junit.annotations.ApiTest;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.web.jersey.testfixtures.RestControllerTestBase;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ApiTest
class JwksApiControllerTest extends RestControllerTestBase {

    private static final String PUBLIC_KEY_ALIAS = "authority-public-key";

    private final Vault vault = mock();
    private final Monitor monitor = mock();
    private final Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);

    @Override
    protected Object controller() {
        return new JwksApiController(vault, PUBLIC_KEY_ALIAS, monitor, clock);
    }

    private RequestSpecification baseRequest() {
        return given()
                .baseUri("http://localhost:" + port + "/v1alpha")
                .when();
    }

    @Test
    void jwksEndpointSuccess_returnsRsaJwkFromVault() throws Exception {
        when(vault.resolveSecret(PUBLIC_KEY_ALIAS)).thenReturn(generateRsaPublicKeyPem());

        baseRequest()
                .get("/jwks.json")
                .then()
                .log().ifError()
                .statusCode(200)
                .contentType(JSON)
                .body("keys", hasSize(1))
                .body("keys[0].kty", equalTo("RSA"))
                .body("keys[0].e", equalTo("AQAB"))
                .body("keys[0].n", not(emptyString()))
                .body("keys[0].kid", equalTo(PUBLIC_KEY_ALIAS));
    }

    @Test
    void jwksEndpointSuccess_cachesKeyAcrossSubsequentRequests() throws Exception {
        when(vault.resolveSecret(PUBLIC_KEY_ALIAS)).thenReturn(generateRsaPublicKeyPem());

        baseRequest().get("/jwks.json").then().statusCode(200);
        baseRequest().get("/jwks.json").then().statusCode(200);

        verify(vault, times(1)).resolveSecret(PUBLIC_KEY_ALIAS);
    }

    /**
     * Generates a fresh RSA key pair and PEM-encodes the public key as an X.509
     * SubjectPublicKeyInfo block, the format expected by {@code JWK.parseFromPEMEncodedObjects}.
     */
    private static String generateRsaPublicKeyPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        byte[] encoded = keyPair.getPublic().getEncoded();
        String base64 = Base64.getEncoder().encodeToString(encoded);

        var pem = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            pem.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        pem.append("-----END PUBLIC KEY-----\n");
        return pem.toString();
    }
}
