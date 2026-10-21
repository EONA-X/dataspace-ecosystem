package org.eclipse.edc.dse.controlplane.policy;

import jakarta.json.JsonObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.server.ApplicationHandler;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the ordering the extension relies on, in a real Jersey pipeline: interceptor instances are
 * registered per resource class through a {@link DynamicFeature}, as EDC's DynamicResourceFeature does,
 * and the EDC-like expansion interceptor (no priority) must run before ours whatever the registration order.
 */
class IriRightOperandJerseyOrderTest {

    private static final String EXPANDED = """
            {"http://www.w3.org/ns/odrl/2/rightOperand": [{"@id": "https://provider.example/terms/api-1.1"}]}
            """;

    @Test
    void controllerReceivesTheNormalizedExpandedBody() throws Exception {
        // Ours registered first on purpose: only the priority may decide the order.
        var config = new ResourceConfig(EchoController.class)
                .register(new PerResource(List.of(new IriRightOperandInterceptor(), new FakeJsonLdExpansion())));
        var handler = new ApplicationHandler(config);

        var request = new ContainerRequest(URI.create("http://localhost/"), URI.create("http://localhost/policydefinitions"),
                "POST", null, new MapPropertiesDelegate(), config);
        request.header("Content-Type", MediaType.APPLICATION_JSON);
        request.setEntityStream(new ByteArrayInputStream("{\"rightOperand\": {\"@id\": \"compact\"}}".getBytes(StandardCharsets.UTF_8)));

        var response = handler.apply(request).get();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat((String) response.getEntity())
                .contains("\"@value\":\"https://provider.example/terms/api-1.1\"")
                .doesNotContain("\"@id\"");
    }

    @Path("/policydefinitions")
    public static class EchoController {
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public String create(JsonObject body) {
            return body.toString();
        }
    }

    /** Stands in for EDC's JerseyJsonLdInterceptor: no priority, replaces the body with its expanded form. */
    static class FakeJsonLdExpansion implements ReaderInterceptor {
        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws java.io.IOException {
            context.getInputStream().readAllBytes();
            context.setInputStream(new ByteArrayInputStream(EXPANDED.getBytes(StandardCharsets.UTF_8)));
            return context.proceed();
        }
    }

    /** Mirrors EDC's DynamicResourceFeature: registers instances for one resource class. */
    record PerResource(List<Object> resources) implements DynamicFeature {
        @Override
        public void configure(ResourceInfo resourceInfo, FeatureContext context) {
            if (resourceInfo.getResourceClass() == EchoController.class) {
                resources.forEach(context::register);
            }
        }
    }
}
