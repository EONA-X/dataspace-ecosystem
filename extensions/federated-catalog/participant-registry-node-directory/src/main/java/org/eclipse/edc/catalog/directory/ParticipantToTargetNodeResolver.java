package org.eclipse.edc.catalog.directory;

import org.eclipse.edc.crawler.spi.TargetNode;
import org.eclipse.edc.iam.did.spi.document.DidDocument;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.issuerservice.spi.holder.model.Holder;
import org.eclipse.edc.spi.result.Result;

import java.util.List;
import java.util.function.Function;

import static org.eclipse.edc.protocol.dsp.spi.type.Dsp2025Constants.DATASPACE_PROTOCOL_HTTP_V_2025_1;

public class ParticipantToTargetNodeResolver implements Function<Holder, Result<TargetNode>> {

    protected static final String DSP_MESSAGING_TYPE = "DSPMessaging";

    private final DidResolverRegistry didResolverRegistry;

    public ParticipantToTargetNodeResolver(DidResolverRegistry didResolverRegistry) {
        this.didResolverRegistry = didResolverRegistry;
    }

    @Override
    public Result<TargetNode> apply(Holder holder) {
        return targetUrl(holder)
                .map(url -> new TargetNode(holder.getParticipantContextId(), holder.getDid(), url, List.of(DATASPACE_PROTOCOL_HTTP_V_2025_1)));
    }

    private Result<String> targetUrl(Holder holder) {
        return didResolverRegistry.resolve(holder.getDid())
                .compose(this::dspMessagingUrl);
    }

    private Result<String> dspMessagingUrl(DidDocument didDocument) {
        return didDocument.getService().stream()
                .filter(service -> service.getType().equals(DSP_MESSAGING_TYPE))
                .findFirst()
                .map(service -> Result.success(service.getServiceEndpoint()))
                .orElse(Result.failure("Failed to find '%s' endpoint for did document '%s'".formatted(DSP_MESSAGING_TYPE, didDocument.getId())));
    }

}
