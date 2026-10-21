package org.eclipse.edc.dse.controlplane.policy;

import jakarta.annotation.Priority;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IriRightOperandInterceptorTest {

    private final IriRightOperandInterceptor interceptor = new IriRightOperandInterceptor();

    @Test
    void runsAfterEdcJsonLdExpansion() {
        // EDC's JerseyJsonLdInterceptor has no @Priority (USER); reader interceptors run in ascending order.
        assertThat(IriRightOperandInterceptor.class.getAnnotation(Priority.class).value()).isGreaterThan(Priorities.USER);
    }

    @Test
    void rewritesTheExpandedBodyBeforeTheControllerReadsIt() throws Exception {
        var context = context(JsonObject.class, """
                {"http://www.w3.org/ns/odrl/2/rightOperand": [{"@id": "https://provider.example/terms/api-1.1"}]}
                """);

        interceptor.aroundReadFrom(context);

        var body = forwardedBody(context);
        assertThat(body).contains("\"@value\":\"https://provider.example/terms/api-1.1\"")
                .doesNotContain("\"@id\"");
        verify(context).proceed();
    }

    @Test
    void forwardsAnUnaffectedBodyUnchanged() throws Exception {
        var json = "{\"http://www.w3.org/ns/odrl/2/rightOperand\":[{\"@value\":\"active\"}]}";
        var context = context(JsonObject.class, json);

        interceptor.aroundReadFrom(context);

        assertThat(forwardedBody(context)).isEqualTo(json);
    }

    @Test
    void ignoresBodiesThatAreNotJsonObjects() throws Exception {
        var context = context(String.class, "{}");

        interceptor.aroundReadFrom(context);

        verify(context, org.mockito.Mockito.never()).setInputStream(org.mockito.ArgumentMatchers.any());
        verify(context).proceed();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ReaderInterceptorContext context(Class type, String body) {
        var context = mock(ReaderInterceptorContext.class);
        when(context.getType()).thenReturn(type);
        when(context.getInputStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return context;
    }

    private static String forwardedBody(ReaderInterceptorContext context) throws Exception {
        var captor = ArgumentCaptor.forClass(InputStream.class);
        verify(context).setInputStream(captor.capture());
        return new String(captor.getValue().readAllBytes(), StandardCharsets.UTF_8);
    }
}
