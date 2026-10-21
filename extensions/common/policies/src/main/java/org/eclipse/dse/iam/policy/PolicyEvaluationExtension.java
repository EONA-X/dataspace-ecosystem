package org.eclipse.dse.iam.policy;

import org.eclipse.edc.connector.controlplane.catalog.spi.policy.CatalogPolicyContext;
import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.connector.controlplane.contract.spi.policy.TransferProcessPolicyContext;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.engine.spi.RuleBindingRegistry;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.util.List;
import java.util.Set;

import static org.eclipse.dse.iam.policy.CatalogDiscoveryPolicyContext.CATALOG_DISCOVERY_SCOPE;
import static org.eclipse.dse.iam.policy.PolicyConstants.GENERIC_CLAIM_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.MEMBERSHIP_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.startsWithTerm;
import static org.eclipse.edc.connector.controlplane.catalog.spi.policy.CatalogPolicyContext.CATALOG_SCOPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext.NEGOTIATION_SCOPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.TransferProcessPolicyContext.TRANSFER_SCOPE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_USE_ACTION_ATTRIBUTE;
import static org.eclipse.edc.policy.model.OdrlNamespace.ODRL_SCHEMA;
import static org.eclipse.edc.spi.core.CoreConstants.DSE_POLICY_PREFIX;
import static org.eclipse.edc.spi.core.CoreConstants.DSE_POLICY_PROFILE_NS;

public class PolicyEvaluationExtension implements ServiceExtension {

    private static final Set<String> RULE_SCOPES = Set.of(
            TRANSFER_SCOPE,
            CATALOG_SCOPE,
            NEGOTIATION_SCOPE,
            CATALOG_DISCOVERY_SCOPE
    );

    private static final List<String> ADDITIONAL_ODRL_ACTIONS = List.of(
            ODRL_SCHEMA + "transfer",
            ODRL_SCHEMA + "share",
            ODRL_SCHEMA + "distribute"
    );

    @Inject
    private PolicyEngine policyEngine;

    @Inject
    private RuleBindingRegistry ruleBindingRegistry;

    @Inject
    private JsonLd jsonLdService;

    @Inject
    private Monitor monitor;


    @Override
    public void initialize(ServiceExtensionContext context) {
        registerNamespaces();
        registerFunctions();
        registerBindings();
        registerPreValidators();
    }

    /**
     * The eox-policy prefix expands to the stable profile namespace. No prefix is registered for the legacy namespace:
     * stored policies hold expanded IRIs, and clients still writing legacy terms send their own @context (or full
     * IRIs), which the functions and bindings below keep accepting.
     */
    private void registerNamespaces() {
        jsonLdService.registerNamespace(DSE_POLICY_PREFIX, DSE_POLICY_PROFILE_NS);
    }

    private void registerFunctions() {
        policyEngine.registerFunction(CatalogPolicyContext.class, Permission.class, new MembershipConstraintFunction<>());
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, new MembershipConstraintFunction<>());
        policyEngine.registerFunction(TransferProcessPolicyContext.class, Permission.class, new MembershipConstraintFunction<>());
        policyEngine.registerFunction(CatalogDiscoveryPolicyContext.class, Permission.class, new MembershipConstraintFunction<>());

        policyEngine.registerFunction(CatalogPolicyContext.class, Permission.class, new JsonPathCredentialConstraintFunction<>());
        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, new JsonPathCredentialConstraintFunction<>());
        policyEngine.registerFunction(TransferProcessPolicyContext.class, Permission.class, new JsonPathCredentialConstraintFunction<>());
        policyEngine.registerFunction(CatalogDiscoveryPolicyContext.class, Permission.class, new JsonPathCredentialConstraintFunction<>());

        policyEngine.registerFunction(ContractNegotiationPolicyContext.class, Permission.class, new CatalogDiscoveryConstraintFunction<>());
        policyEngine.registerFunction(TransferProcessPolicyContext.class, Permission.class, new CatalogDiscoveryConstraintFunction<>());
        policyEngine.registerFunction(CatalogDiscoveryPolicyContext.class, Permission.class, new CatalogDiscoveryConstraintFunction<>());
    }

    private void registerPreValidators() {
        policyEngine.registerPreValidator(CatalogDiscoveryPolicyContext.class, new NonFullMemberDefaultDenyValidator<>());
        policyEngine.registerPreValidator(ContractNegotiationPolicyContext.class, new NonFullMemberDefaultDenyValidator<>());
        policyEngine.registerPreValidator(TransferProcessPolicyContext.class, new NonFullMemberDefaultDenyValidator<>());
    }

    /**
     * Binds every eox-policy term in both the stable and the legacy namespace (see {@link PolicyConstants#termIris}).
     * An unbound left operand would be silently dropped by EDC's scope filter at evaluation, i.e. permitted.
     */
    private void registerBindings() {
        ruleBindingRegistry.dynamicBind(s -> startsWithTerm(s, GENERIC_CLAIM_CONSTRAINT) ? Set.of(NEGOTIATION_SCOPE, TRANSFER_SCOPE) : Set.of());
        ruleBindingRegistry.dynamicBind(s -> startsWithTerm(s, MEMBERSHIP_CONSTRAINT) ? RULE_SCOPES : Set.of());
        ruleBindingRegistry.dynamicBind(s -> startsWithTerm(s, RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT) ? Set.of(CATALOG_DISCOVERY_SCOPE, NEGOTIATION_SCOPE, TRANSFER_SCOPE) : Set.of());
        RULE_SCOPES.forEach(scope -> ruleBindingRegistry.bind(ODRL_USE_ACTION_ATTRIBUTE, scope));
        
        for (var action : ADDITIONAL_ODRL_ACTIONS) {
            RULE_SCOPES.forEach(scope -> ruleBindingRegistry.bind(action, scope));
        }
    }

}
