package org.eclipse.edc.dse.controlplane.policy;

import org.eclipse.edc.connector.controlplane.api.management.policy.v3.PolicyDefinitionApiV3Controller;
import org.eclipse.edc.connector.controlplane.api.management.policy.v4.PolicyDefinitionApiV4Controller;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;
import org.eclipse.edc.web.spi.configuration.ApiContext;

/**
 * Lets the management API accept IRI right operands ({@code {"@id": …}}) on policy definitions, which
 * EDC 0.15 answers with HTTP 500 (eclipse-edc/Connector#6066). Remove once upstream accepts them.
 */
@Extension(IriRightOperandExtension.NAME)
public class IriRightOperandExtension implements ServiceExtension {

    public static final String NAME = "Policy definition IRI right operand";

    @Inject
    private WebService webService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var interceptor = new IriRightOperandInterceptor();
        webService.registerDynamicResource(ApiContext.MANAGEMENT, PolicyDefinitionApiV3Controller.class, interceptor);
        webService.registerDynamicResource(ApiContext.MANAGEMENT, PolicyDefinitionApiV4Controller.class, interceptor);
    }
}
