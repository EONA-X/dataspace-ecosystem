package org.eclipse.edc.dse.dataplane.sftp;

import org.eclipse.edc.connector.dataplane.spi.DataFlow;
import org.eclipse.edc.connector.dataplane.spi.Endpoint;
import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceService;
import org.eclipse.edc.connector.dataplane.spi.iam.DataPlaneAccessTokenService;
import org.eclipse.edc.connector.dataplane.spi.iam.PublicEndpointGeneratorService;
import org.eclipse.edc.spi.iam.TokenParameters;
import org.eclipse.edc.spi.iam.TokenRepresentation;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.types.domain.DataAddress;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.eclipse.edc.spi.constants.CoreConstants.EDC_NAMESPACE;
import static org.eclipse.edc.spi.result.Result.success;

/**
 * EDR issuance for SFTP-sourced pull transfers. Upstream EDC 0.15.0 ({@code data-plane-iam}'s
 * {@code DataPlaneAuthorizationServiceImpl}) only ever registers its {@link EndpointDataReferenceService}
 * under the "HttpData" source address type (see {@code DataPlaneIamExtension}), even though its logic
 * is source-type agnostic -- it builds the EDR purely from the transfer's destination type via
 * {@link PublicEndpointGeneratorService}. Without a matching registration for "Sftp", every SFTP-sourced
 * pull transfer fails in {@code DataPlaneManagerImpl.handlePull} with "No EDR service with type Sftp found".
 * This mirrors that same logic, registered under {@link SftpDataAddress#SFTP_TYPE} instead.
 */
public class SftpEndpointDataReferenceService implements EndpointDataReferenceService {

    private static final String PROPERTY_AGREEMENT_ID = "agreement_id";
    private static final String PROPERTY_ASSET_ID = "asset_id";
    private static final String PROPERTY_PROCESS_ID = "process_id";
    private static final String PROPERTY_FLOW_TYPE = "flow_type";
    private static final String PROPERTY_PARTICIPANT_ID = "participant_id";

    private final DataPlaneAccessTokenService accessTokenService;
    private final PublicEndpointGeneratorService endpointGenerator;
    private final String ownParticipantId;
    private final Clock clock;

    public SftpEndpointDataReferenceService(DataPlaneAccessTokenService accessTokenService,
                                             PublicEndpointGeneratorService endpointGenerator,
                                             String ownParticipantId,
                                             Clock clock) {
        this.accessTokenService = accessTokenService;
        this.endpointGenerator = endpointGenerator;
        this.ownParticipantId = ownParticipantId;
        this.clock = clock;
    }

    @Override
    public Result<DataAddress> createEndpointDataReference(DataFlow dataFlow) {
        HashMap<String, Object> additionalProperties = new HashMap<>(dataFlow.getProperties());
        additionalProperties.put(PROPERTY_AGREEMENT_ID, dataFlow.getAgreementId());
        additionalProperties.put(PROPERTY_ASSET_ID, dataFlow.getAssetId());
        additionalProperties.put(PROPERTY_PROCESS_ID, dataFlow.getId());
        additionalProperties.put(PROPERTY_FLOW_TYPE, dataFlow.getTransferType().flowType().toString());
        additionalProperties.put(PROPERTY_PARTICIPANT_ID, dataFlow.getParticipantId());
        TokenParameters tokenParams = createTokenParams(dataFlow);
        DataAddress sourceDataAddress = dataFlow.getActualSource();

        Result<DataAddress.Builder> dataAddressBuilder = endpointGenerator.generateFor(dataFlow.getTransferType().destinationType(), sourceDataAddress)
                .compose(ep -> createSecureEndpoint(ep, tokenParams, additionalProperties, sourceDataAddress))
                .compose(se -> createDataAddress(se.tokenRepresentation(), se.endpoint()));

        return dataAddressBuilder.map(DataAddress.Builder::build);
    }

    @Override
    public ServiceResult<Void> revokeEndpointDataReference(String flowId, String reason) {
        return accessTokenService.revoke(flowId, reason);
    }

    private Result<SecureEndpoint> createSecureEndpoint(Endpoint endpoint, TokenParameters tokenParameters, Map<String, Object> additionalProperties, DataAddress sourceDataAddress) {
        return accessTokenService.obtainToken(tokenParameters, sourceDataAddress, additionalProperties)
                .map(tr -> new SecureEndpoint(endpoint, tr));
    }

    private Result<DataAddress.Builder> createDataAddress(TokenRepresentation tokenRepresentation, Endpoint publicEndpoint) {
        DataAddress.Builder address = DataAddress.Builder.newInstance()
                .type(publicEndpoint.endpointType())
                .property(EDC_NAMESPACE + "endpoint", publicEndpoint.endpoint())
                .property(EDC_NAMESPACE + "endpointType", publicEndpoint.endpointType())
                .property(EDC_NAMESPACE + "authorization", tokenRepresentation.getToken())
                .properties(tokenRepresentation.getAdditional());

        return success(address);
    }

    private TokenParameters createTokenParams(DataFlow dataFlow) {
        return TokenParameters.Builder.newInstance()
                .claims("jti", UUID.randomUUID().toString())
                .claims("aud", dataFlow.getParticipantId())
                .claims("iss", ownParticipantId)
                .claims("sub", ownParticipantId)
                .claims("iat", clock.instant().getEpochSecond())
                .build();
    }

    private record SecureEndpoint(Endpoint endpoint, TokenRepresentation tokenRepresentation) {
    }
}
