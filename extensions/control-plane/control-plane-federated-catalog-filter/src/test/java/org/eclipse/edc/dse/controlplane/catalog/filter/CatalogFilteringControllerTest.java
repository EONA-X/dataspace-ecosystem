package org.eclipse.edc.dse.controlplane.catalog.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.FilterRequest;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.iam.TokenParameters;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.util.io.Ports.getFreePort;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CatalogFilteringControllerTest {

    private static final String OWN_DID = "did:web:own";
    private static final String AUTHORITY_DID = "did:web:authority";

    private final Monitor monitor = mock();
    private final IdentityService identityService = mock();
    private final DidResolverRegistry didResolverRegistry = mock();
    private final AuthorityCatalogDidResolver authorityCatalogDidResolver = mock();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.systemUTC();

    private int port;
    private ClientAndServer server;

    private CatalogFilteringController controller;

    @BeforeEach
    void setUp() {
        port = getFreePort();
        server = ClientAndServer.startClientAndServer(port);
        controller = new CatalogFilteringController(authorityCatalogDidResolver, monitor, identityService,
                OWN_DID, clock, AUTHORITY_DID, didResolverRegistry, mapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void getCatalog_authorityUrlResolutionFails_shouldThrow() {
        when(authorityCatalogDidResolver.fetchCatalogFilterUrl()).thenReturn(Result.failure("not found"));

        assertThrows(RuntimeException.class, () -> controller.getCatalog());

        verifyNoInteractions(identityService);
    }

    @Test
    void getCatalog_identityServiceFails_shouldReturn500() {
        when(authorityCatalogDidResolver.fetchCatalogFilterUrl())
                .thenReturn(Result.success("http://localhost:" + port + "/filter"));
        when(identityService.obtainClientCredentials(eq(OWN_DID), any())).thenReturn(Result.failure("no token"));

        var response = controller.getCatalog();

        assertThat(response.getStatus()).isEqualTo(500);
    }

    @Test
    void getCatalog_success_shouldPostFilterRequestAndReturnCatalog() throws Exception {
        when(authorityCatalogDidResolver.fetchCatalogFilterUrl())
                .thenReturn(Result.success("http://localhost:" + port + "/filter"));

        var token = TokenRepresentation.Builder.newInstance().token(UUID.randomUUID().toString()).build();
        when(identityService.obtainClientCredentials(eq(OWN_DID), any())).thenReturn(Result.success(token));

        var expectedBody = mapper.writeValueAsString(new FilterRequest(token, OWN_DID));
        server.when(HttpRequest.request().withPath("/filter").withBody(expectedBody))
                .respond(HttpResponse.response().withStatusCode(200).withBody("[{\"filtered\":true}]", MediaType.APPLICATION_JSON));

        var response = controller.getCatalog();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo("[{\"filtered\":true}]");
    }

    @Test
    void getCatalog_success_shouldBuildTokenParametersWithExpectedClaims() {
        when(authorityCatalogDidResolver.fetchCatalogFilterUrl())
                .thenReturn(Result.success("http://localhost:" + port + "/filter"));

        var token = TokenRepresentation.Builder.newInstance().token(UUID.randomUUID().toString()).build();
        var captor = org.mockito.ArgumentCaptor.forClass(TokenParameters.class);
        when(identityService.obtainClientCredentials(eq(OWN_DID), captor.capture())).thenReturn(Result.success(token));

        server.when(HttpRequest.request().withPath("/filter"))
                .respond(HttpResponse.response().withStatusCode(200).withBody("[]", MediaType.APPLICATION_JSON));

        controller.getCatalog();

        var params = captor.getValue();
        assertThat(params.getStringClaim("iss")).isEqualTo(OWN_DID);
        assertThat(params.getStringClaim("sub")).isEqualTo(OWN_DID);
        assertThat(params.getStringClaim("aud")).isEqualTo(AUTHORITY_DID);
        assertThat((String) params.getClaims().get("scope")).contains("VerifiableCredential:read");
    }

    @Test
    void getCatalog_emptyResponseBody_shouldReturn500() {
        when(authorityCatalogDidResolver.fetchCatalogFilterUrl())
                .thenReturn(Result.success("http://localhost:" + port + "/filter"));

        var token = TokenRepresentation.Builder.newInstance().token(UUID.randomUUID().toString()).build();
        when(identityService.obtainClientCredentials(eq(OWN_DID), any())).thenReturn(Result.success(token));

        server.when(HttpRequest.request().withPath("/filter"))
                .respond(HttpResponse.response().withStatusCode(200));

        var response = controller.getCatalog();

        assertThat(response.getStatus()).isEqualTo(500);
    }

}
