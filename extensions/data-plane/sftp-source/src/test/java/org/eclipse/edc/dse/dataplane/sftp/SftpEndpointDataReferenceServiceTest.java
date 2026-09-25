package org.eclipse.edc.dse.dataplane.sftp;

import org.eclipse.edc.connector.dataplane.spi.DataFlow;
import org.eclipse.edc.connector.dataplane.spi.Endpoint;
import org.eclipse.edc.connector.dataplane.spi.iam.DataPlaneAccessTokenService;
import org.eclipse.edc.connector.dataplane.spi.iam.PublicEndpointGeneratorService;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.types.domain.transfer.FlowType;
import org.eclipse.edc.spi.types.domain.transfer.TransferType;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.spi.result.Result.success;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SftpEndpointDataReferenceServiceTest {

    private final DataPlaneAccessTokenService accessTokenService = mock();
    private final PublicEndpointGeneratorService endpointGenerator = mock();
    private final SftpEndpointDataReferenceService service =
            new SftpEndpointDataReferenceService(accessTokenService, endpointGenerator, "test-participant", Clock.systemUTC());

    @Test
    void createEndpointDataReference_shouldGenerateEndpointForSourceType() {
        var source = SftpDataAddress.Builder.newInstance().host("sftp.example.org").path("/foo.txt").build();
        var dataFlow = DataFlow.Builder.newInstance()
                .source(source)
                .transferType(new TransferType("HttpData", FlowType.PULL))
                .participantId("consumer-participant")
                .assetId("asset-1")
                .agreementId("agreement-1")
                .build();

        var endpoint = Endpoint.url("https://dataplane.example.org/api/public");
        when(endpointGenerator.generateFor(eq("HttpData"), eq(source))).thenReturn(success(endpoint));
        var token = mock(TokenRepresentation.class);
        when(token.getToken()).thenReturn("test-token");
        when(token.getAdditional()).thenReturn(java.util.Map.of());
        when(accessTokenService.obtainToken(any(), eq(source), any())).thenReturn(success(token));

        var result = service.createEndpointDataReference(dataFlow);

        assertThat(result.succeeded()).isTrue();
        var dataAddress = result.getContent();
        assertThat(dataAddress.getStringProperty("https://w3id.org/edc/v0.0.1/ns/endpoint")).isEqualTo(endpoint.endpoint());
    }

    @Test
    void createEndpointDataReference_shouldFail_whenNoGeneratorRegisteredForDestinationType() {
        var source = SftpDataAddress.Builder.newInstance().host("sftp.example.org").path("/foo.txt").build();
        var dataFlow = DataFlow.Builder.newInstance()
                .source(source)
                .transferType(new TransferType("UnknownType", FlowType.PULL))
                .participantId("consumer-participant")
                .build();

        when(endpointGenerator.generateFor(eq("UnknownType"), eq(source)))
                .thenReturn(org.eclipse.edc.spi.result.Result.failure("no generator"));

        var result = service.createEndpointDataReference(dataFlow);

        assertThat(result.failed()).isTrue();
    }
}
