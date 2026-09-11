package org.eclipse.edc.connector.controlplane.transfer.dataplane.flow;

import org.eclipse.edc.connector.controlplane.asset.spi.domain.Asset;
import org.eclipse.edc.connector.controlplane.transfer.spi.flow.DataFlowController;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.DataFlowResponse;
import org.eclipse.edc.connector.controlplane.transfer.spi.types.TransferProcess;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.response.StatusResult;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.edr.EndpointDataReference;

import java.util.Collections;
import java.util.Set;

import static org.eclipse.edc.spi.response.ResponseStatus.FATAL_ERROR;

/**
 * Handles Kafka-based asset transfers by managing the flow of data directly between the consumer and the Kafka broker.
 * <p>
 * This controller does not involve a dedicated data plane; instead, data is accessed directly from the Kafka broker or proxy.
 * As a result, operations such as suspend, terminate, and provision are not supported, and transfer types are not provided.
 * This class is intended for scenarios where Kafka assets are transferred without intermediary data plane components.
 */

public class KafkaFlowController implements DataFlowController {

    public static final String KAFKA_BROKER_PULL = "KafkaBroker-PULL";

    @Override
    public boolean canHandle(TransferProcess transferProcess) {
        return KAFKA_BROKER_PULL.equals(transferProcess.getTransferType());
    }

    /**
     * Kafka-based assets do not support "suspend" operation because there is no dedicated data plane deployed
     * to suspend the Kafka transfer. The data is transferred directly from the Kafka broker/proxy without passing through the data plane.
     *
     * @param transferProcess object containing transfer process state
     * @return void
     */
    @Override
    public StatusResult<Void> suspend(TransferProcess transferProcess) {
        return StatusResult.failure(FATAL_ERROR, "Suspend operation is not supported for Kafka based assets as they transfer directly from the broker without a dedicated data plane");
    }

    /**
     * Kafka-based assets do not support "terminate" operation because there is no dedicated data plane deployed
     * to terminate the Kafka transfer. The data is transferred directly from the Kafka broker/proxy without passing through the data plane
     *
     * @param transferProcess object containing transfer process state
     * @return void
     */
    @Override
    public StatusResult<Void> terminate(TransferProcess transferProcess) {
        return StatusResult.failure(FATAL_ERROR, "Terminate operation is not supported for Kafka based assets as they transfer directly from the broker without a dedicated data plane");
    }

    /**
     * This method internally calls DataPlaneInstance.getAllowedTransferTypes.
     * As the data plane does not exist for Kafka-based assets, it returns empty set.
     *
     * @param asset kafka asset added by provider
     * @return transfer types
     */
    @Override
    public Set<String> transferTypesFor(Asset asset) {
        return Collections.emptySet();
    }

    @Override
    public StatusResult<DataFlowResponse> start(TransferProcess transferProcess, Policy policy) {
        var contentDataAddress = transferProcess.getContentDataAddress();
        var kafkaDataAddress = DataAddress.Builder.newInstance()
                .type(EndpointDataReference.EDR_SIMPLE_TYPE)
                .properties(contentDataAddress.getProperties())
                .property(EndpointDataReference.ID, transferProcess.getCorrelationId())
                .property(EndpointDataReference.CONTRACT_ID, transferProcess.getContractId())
                .build();
        return StatusResult.success(DataFlowResponse.Builder.newInstance().dataAddress(kafkaDataAddress).build());
    }

    /**
     * No implementation as of now, but it can be used for automation of provider proxy deployment, to provision the Kafka provider
     * proxy address in the transfer process content data address instead of actual broker address.
     *
     * @param transferProcess object containing transfer process state
     * @param policy policy that will be applied on the transfer
     * @return DataFlowResponse
     */
    @Override
    public StatusResult<DataFlowResponse> prepare(TransferProcess transferProcess, Policy policy) {
        return StatusResult.failure(FATAL_ERROR, "Provision operation is not supported for Kafka based assets as they transfer directly from the broker without a dedicated data plane");
    }
}
