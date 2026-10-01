package org.eclipse.edc.api;

import jakarta.ws.rs.core.Response;
import org.eclipse.edc.FilterRequest;
import org.eclipse.edc.spi.iam.IdentityService;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.eclipse.edc.util.FederatedCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VcCatalogFilterControllerTest {

    private static final String OWN_DID = "did:web:own";
    private static final String CONSUMER_DID = "did:web:consumer.example.com";
    private static final String INVALID_JWT = "eyJhbGciOiJSUzI1NiJ9.invalide.invalide";

    private final ServiceExtensionContext context = mock();
    private final Monitor monitor = mock();
    private final FederatedCatalogService federatedCatalogService = mock();
    private final IdentityService identityService = mock();
    private final TypeTransformerRegistry transformerRegistry = mock();

    private VcCatalogFilterController controller;

    @BeforeEach
    void setUp() {
        when(context.getSetting("edc.participant.id", ServiceExtensionContext.ANONYMOUS_PARTICIPANT)).thenReturn(OWN_DID);
        controller = new VcCatalogFilterController(context, monitor, federatedCatalogService, identityService, transformerRegistry);
    }

    @Test
    void federated_catalog_filter_invalid_token_returns_empty() {
        // Given: a request carrying an invalid/expired JWT that fails identity verification
        var token = TokenRepresentation.Builder.newInstance().token(INVALID_JWT).build();
        var request = new FilterRequest(token, CONSUMER_DID);
        when(identityService.verifyJwtToken(eq(OWN_DID), eq(token), any())).thenReturn(Result.failure("invalid token"));

        // When: the controller filters the federated catalog with this request
        var response = controller.filter(request);

        // Then: the token validation failure leaves credentials null, so the controller short-circuits
        // and returns 204 No Content, without ever reaching the federated catalog service
        assertThat(response.getStatus()).isEqualTo(Response.Status.NO_CONTENT.getStatusCode());
        verify(identityService).verifyJwtToken(eq(OWN_DID), eq(token), any());
        verifyNoInteractions(federatedCatalogService);
    }
}
