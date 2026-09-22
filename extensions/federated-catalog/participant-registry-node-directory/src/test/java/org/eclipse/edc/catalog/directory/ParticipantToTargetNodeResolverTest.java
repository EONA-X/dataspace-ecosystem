package org.eclipse.edc.catalog.directory;

import org.eclipse.edc.crawler.spi.TargetNode;
import org.eclipse.edc.iam.did.spi.document.DidDocument;
import org.eclipse.edc.iam.did.spi.document.Service;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.issuerservice.spi.holder.model.Holder;
import org.eclipse.edc.spi.result.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.catalog.directory.ParticipantToTargetNodeResolver.DSP_MESSAGING_TYPE;
import static org.eclipse.edc.protocol.dsp.spi.type.Dsp2025Constants.DATASPACE_PROTOCOL_HTTP_V_2025_1;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParticipantToTargetNodeResolverTest {

    private static final String DID = "did:web:participant";
    private final DidResolverRegistry didResolverRegistry = mock();
    private final ParticipantToTargetNodeResolver resolver = new ParticipantToTargetNodeResolver(didResolverRegistry);

    @Test
    void success() {
        var service = new Service("service-id", DSP_MESSAGING_TYPE, "http://participant.com");
        when(didResolverRegistry.resolve(DID)).thenReturn(Result.success(createDidDocument(service)));
        var holder = createHolder();

        var result = resolver.apply(holder);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.getContent()).usingRecursiveComparison()
                .isEqualTo(new TargetNode(holder.getParticipantContextId(), holder.getDid(), service.getServiceEndpoint(), List.of(DATASPACE_PROTOCOL_HTTP_V_2025_1)));
    }

    @Test
    void didResolutionFails_shouldReturnError() {
        when(didResolverRegistry.resolve(DID)).thenReturn(Result.failure("did resolution fails"));
        var holder = createHolder();

        var result = resolver.apply(holder);

        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureDetail()).contains("did resolution fails");
    }

    @Test
    void missingDspMessagingUrl_shouldReturnError() {
        var service = new Service("service-id", "other", "http://participant.com");
        when(didResolverRegistry.resolve(DID)).thenReturn(Result.success(createDidDocument(service)));
        var holder = createHolder();

        var result = resolver.apply(holder);

        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureDetail()).isEqualTo("Failed to find '%s' endpoint for did document '%s'".formatted(DSP_MESSAGING_TYPE, DID));
    }

    private static DidDocument createDidDocument(Service service) {
        return DidDocument.Builder.newInstance()
                .id(DID)
                .service(List.of(service))
                .build();
    }

    private static Holder createHolder() {
        return Holder.Builder.newInstance()
                .holderId(UUID.randomUUID().toString())
                .holderName("participant-id")
                .did(DID)
                .participantContextId(UUID.randomUUID().toString())
                .holderName("participant-name")
                .build();
    }

}