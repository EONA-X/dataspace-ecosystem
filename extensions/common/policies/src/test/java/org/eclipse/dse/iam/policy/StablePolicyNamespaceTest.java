package org.eclipse.dse.iam.policy;

import org.eclipse.edc.connector.controlplane.catalog.spi.policy.CatalogPolicyContext;
import org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext;
import org.eclipse.edc.connector.controlplane.contract.spi.policy.TransferProcessPolicyContext;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.CredentialSubject;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.Issuer;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiableCredential;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.policy.engine.PolicyEngineImpl;
import org.eclipse.edc.policy.engine.RuleBindingRegistryImpl;
import org.eclipse.edc.policy.engine.ScopeFilter;
import org.eclipse.edc.policy.engine.spi.PolicyContext;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.engine.spi.RuleBindingRegistry;
import org.eclipse.edc.policy.engine.validation.RuleValidator;
import org.eclipse.edc.policy.model.Action;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.core.CoreConstants;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.dse.iam.policy.CatalogDiscoveryPolicyContext.CATALOG_DISCOVERY_SCOPE;
import static org.eclipse.dse.iam.policy.CredentialsUtils.VC_CLAIM;
import static org.eclipse.dse.iam.policy.PolicyConstants.DOMAIN_CREDENTIAL_TYPE;
import static org.eclipse.dse.iam.policy.PolicyConstants.GENERIC_CLAIM_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.MEMBERSHIP_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.MEMBERSHIP_CREDENTIAL_TYPE;
import static org.eclipse.dse.iam.policy.PolicyConstants.RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT;
import static org.eclipse.edc.connector.controlplane.catalog.spi.policy.CatalogPolicyContext.CATALOG_SCOPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext.NEGOTIATION_SCOPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.TransferProcessPolicyContext.TRANSFER_SCOPE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_USE_ACTION_ATTRIBUTE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * #799: the eox-policy left operands are accepted under both the legacy namespace and the stable ODRL profile
 * namespace, with the same functions, bindings and semantics. Every case is run under both namespaces and the stable
 * result is compared with its legacy twin.
 */
class StablePolicyNamespaceTest {

    /**
     * Legacy namespace, still carried by stored policies and live agreements (#995 migrates them, #996 drops it).
     */
    static final String LEGACY_NS = "https://w3id.org/eonax/policy/";
    /**
     * Stable namespace of the Eona-X ODRL profile (EONA-X/eona-vocabularies-reference#13); releases are identified by
     * owl:versionIRI, never by this namespace.
     */
    static final String STABLE_NS = "https://eona-x.eu/vocabulary/odrl-profile#";

    private static final String MEMBERSHIP_TYPE = MEMBERSHIP_CREDENTIAL_TYPE + ".membership.membershipType";
    private static final String GROUPS = MEMBERSHIP_CREDENTIAL_TYPE + ".groups";
    private static final String DOMAIN = DOMAIN_CREDENTIAL_TYPE + ".domain";

    private static final Map<String, Set<String>> BOUND_SCOPES = Map.of(
            MEMBERSHIP_CONSTRAINT, Set.of(CATALOG_SCOPE, NEGOTIATION_SCOPE, TRANSFER_SCOPE, CATALOG_DISCOVERY_SCOPE),
            GENERIC_CLAIM_CONSTRAINT, Set.of(NEGOTIATION_SCOPE, TRANSFER_SCOPE),
            RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT, Set.of(CATALOG_DISCOVERY_SCOPE, NEGOTIATION_SCOPE, TRANSFER_SCOPE)
    );

    private PolicyEngine policyEngine;
    private RuleBindingRegistry ruleBindingRegistry;
    private JsonLd jsonLd;

    /**
     * term, claim path (null for Membership), operator, right operand, expected outcome where the term is bound.
     */
    static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of(MEMBERSHIP_CONSTRAINT, null, Operator.EQ, MembershipConstraintFunction.ACTIVE, true),
                Arguments.of(GENERIC_CLAIM_CONSTRAINT, MEMBERSHIP_TYPE, Operator.EQ, "FullMember", true),
                Arguments.of(GENERIC_CLAIM_CONSTRAINT, MEMBERSHIP_TYPE, Operator.EQ, "External", false),
                Arguments.of(GENERIC_CLAIM_CONSTRAINT, DOMAIN, Operator.IS_NONE_OF, "['travel']", true),
                Arguments.of(GENERIC_CLAIM_CONSTRAINT, DOMAIN, Operator.IS_NONE_OF, "['route','travel']", false),
                Arguments.of(RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT, GROUPS, Operator.HAS_PART, "EMDS", true),
                Arguments.of(RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT, GROUPS, Operator.HAS_PART, "TOUR", false)
        );
    }

    static Stream<Arguments> scenariosPerScope() {
        return scenarios().flatMap(arguments -> Stream.of(CATALOG_SCOPE, NEGOTIATION_SCOPE, TRANSFER_SCOPE, CATALOG_DISCOVERY_SCOPE)
                .map(scope -> {
                    var a = arguments.get();
                    return Arguments.of(scope, a[0], a[1], a[2], a[3], a[4]);
                }));
    }

    @BeforeEach
    void setUp() throws Exception {
        ruleBindingRegistry = new RuleBindingRegistryImpl();
        policyEngine = new PolicyEngineImpl(new ScopeFilter(ruleBindingRegistry), new RuleValidator(ruleBindingRegistry));
        jsonLd = mock(JsonLd.class);

        var extension = new PolicyEvaluationExtension();
        setField(extension, "policyEngine", policyEngine);
        setField(extension, "ruleBindingRegistry", ruleBindingRegistry);
        setField(extension, "jsonLdService", jsonLd);
        setField(extension, "monitor", mock(Monitor.class));
        extension.initialize(mock(ServiceExtensionContext.class, Mockito.RETURNS_DEEP_STUBS));
    }

    @ParameterizedTest(name = "{0}{1} {2} {3}")
    @MethodSource("scenarios")
    void constraintFunction_stableNamespace_evaluatesLikeLegacyTwin(String term, String path, Operator operator, Object right, boolean expected) {
        var function = functionFor(term);
        var legacy = leftOperand(LEGACY_NS, term, path);
        var stable = leftOperand(STABLE_NS, term, path);

        assertThat(function.canHandle(stable)).as("canHandle(%s)", stable).isTrue();

        var legacyContext = new MembershipConstraintFunctionTest.TestPolicyContext(agentClaims());
        var stableContext = new MembershipConstraintFunctionTest.TestPolicyContext(agentClaims());
        var legacyResult = function.evaluate(legacy, operator, right, null, legacyContext);
        var stableResult = function.evaluate(stable, operator, right, null, stableContext);

        assertThat(legacyResult).as("legacy result").isEqualTo(expected);
        assertThat(stableResult).as("stable result").isEqualTo(legacyResult);
        assertThat(stableContext.getProblems()).isEqualTo(legacyContext.getProblems());
    }

    @ParameterizedTest
    @ValueSource(strings = {MEMBERSHIP_CONSTRAINT, GENERIC_CLAIM_CONSTRAINT, RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT})
    void bindings_stableNamespace_sameScopesAsLegacyTwin(String term) {
        var suffix = MEMBERSHIP_CONSTRAINT.equals(term) ? "" : ".$." + MEMBERSHIP_TYPE;

        var legacy = ruleBindingRegistry.bindings(LEGACY_NS + term + suffix);
        var stable = ruleBindingRegistry.bindings(STABLE_NS + term + suffix);

        assertThat(legacy).isEqualTo(BOUND_SCOPES.get(term));
        assertThat(stable).isEqualTo(legacy);
    }

    @ParameterizedTest(name = "{0} {1}{2} {3} {4}")
    @MethodSource("scenariosPerScope")
    void policyEngine_stableNamespace_evaluatesLikeLegacyTwin(String scope, String term, String path, Operator operator, Object right, boolean expected) {
        var legacyResult = policyEngine.evaluate(policy(leftOperand(LEGACY_NS, term, path), operator, right), context(scope));
        var stableResult = policyEngine.evaluate(policy(leftOperand(STABLE_NS, term, path), operator, right), context(scope));

        var expectedInScope = !BOUND_SCOPES.get(term).contains(scope) || expected;
        assertThat(legacyResult.succeeded()).as("legacy in %s", scope).isEqualTo(expectedInScope);
        assertThat(stableResult.succeeded()).as("stable in %s", scope).isEqualTo(legacyResult.succeeded());
    }

    @ParameterizedTest(name = "{0}{1}")
    @MethodSource("scenarios")
    void policyValidation_acceptsStableNamespaceLeftOperand(String term, String path, Operator operator, Object right, boolean expected) {
        var legacy = policyEngine.validate(policy(leftOperand(LEGACY_NS, term, path), operator, right));
        var stable = policyEngine.validate(policy(leftOperand(STABLE_NS, term, path), operator, right));

        assertThat(legacy.succeeded()).as("legacy: %s", detail(legacy)).isTrue();
        assertThat(stable.succeeded()).as("stable: %s", detail(stable)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {LEGACY_NS, STABLE_NS})
    void policyValidation_unknownTerm_stillRejected(String namespace) {
        var result = policyEngine.validate(policy(namespace + "UnknownClaim.$.MembershipCredential.foo", Operator.EQ, "bar"));

        assertThat(result.failed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {LEGACY_NS, STABLE_NS})
    void nonFullMemberAllowConstraint_acceptedInBothNamespaces(String namespace) {
        var validator = new NonFullMemberDefaultDenyValidator<MembershipConstraintFunctionTest.TestPolicyContext>();
        var external = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("membership", Map.of("membershipType", "External")));
        var context = new MembershipConstraintFunctionTest.TestPolicyContext(Map.of(VC_CLAIM, List.of(external)));
        var allow = policy("%s%s.$.%s".formatted(namespace, RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT, MEMBERSHIP_TYPE), Operator.EQ, "External");

        assertThat(validator.apply(allow, context)).as("allow under %s: %s", namespace, context.getProblems()).isTrue();
    }

    @Test
    void coreConstants_pinBothNamespaces() throws ReflectiveOperationException {
        // read through reflection: DSE_POLICY_PROFILE_NS and DSE_POLICY_NAMESPACES are introduced by #799, and the
        // test must compile (and fail) before they exist
        assertThat(CoreConstants.class.getField("DSE_POLICY_PROFILE_NS").get(null)).isEqualTo(STABLE_NS);
        assertThat(CoreConstants.DSE_POLICY_NS).isEqualTo(LEGACY_NS);
        assertThat(CoreConstants.class.getField("DSE_POLICY_NAMESPACES").get(null)).isEqualTo(List.of(STABLE_NS, LEGACY_NS));
    }

    @Test
    void jsonLdPrefix_eoxPolicy_mapsToStableNamespace() {
        verify(jsonLd).registerNamespace("eox-policy", STABLE_NS);
    }

    private static AbstractDynamicCredentialConstraintFunction<MembershipConstraintFunctionTest.TestPolicyContext> functionFor(String term) {
        return switch (term) {
            case MEMBERSHIP_CONSTRAINT -> new MembershipConstraintFunction<>();
            case GENERIC_CLAIM_CONSTRAINT -> new JsonPathCredentialConstraintFunction<>();
            case RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT -> new CatalogDiscoveryConstraintFunction<>();
            default -> throw new IllegalArgumentException(term);
        };
    }

    private static String leftOperand(String namespace, String term, String path) {
        return path == null ? namespace + term : "%s%s.$.%s".formatted(namespace, term, path);
    }

    private static Policy policy(String leftOperand, Operator operator, Object right) {
        var constraint = AtomicConstraint.Builder.newInstance()
                .leftExpression(new LiteralExpression(leftOperand))
                .operator(operator)
                .rightExpression(new LiteralExpression(right))
                .build();
        var permission = Permission.Builder.newInstance()
                .action(Action.Builder.newInstance().type(ODRL_USE_ACTION_ATTRIBUTE).build())
                .constraint(constraint)
                .build();
        return Policy.Builder.newInstance().permission(permission).build();
    }

    private static PolicyContext context(String scope) {
        var agent = new ParticipantAgent(agentClaims(), emptyMap());
        return switch (scope) {
            case CATALOG_SCOPE -> new CatalogPolicyContext(agent);
            case NEGOTIATION_SCOPE -> new ContractNegotiationPolicyContext(agent);
            case TRANSFER_SCOPE -> new TransferProcessPolicyContext(agent, null, Instant.now());
            case CATALOG_DISCOVERY_SCOPE -> new CatalogDiscoveryPolicyContext(agent);
            default -> throw new IllegalArgumentException(scope);
        };
    }

    /**
     * A FullMember of group EMDS, in domain "route".
     */
    private static Map<String, Object> agentClaims() {
        var membership = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of(
                "membership", Map.of("membershipType", "FullMember"),
                "groups", List.of("EMDS")));
        var domain = createVc(DOMAIN_CREDENTIAL_TYPE, Map.of("domain", "route"));
        return Map.of(VC_CLAIM, List.of(membership, domain));
    }

    private static VerifiableCredential createVc(String type, Map<String, Object> claims) {
        return VerifiableCredential.Builder.newInstance()
                .type(type)
                .issuer(new Issuer("did:web:issuer"))
                .issuanceDate(Instant.now())
                .credentialSubject(CredentialSubject.Builder.newInstance().claims(claims).id("did:web:subject").build())
                .build();
    }

    private static String detail(Result<Void> result) {
        return result.succeeded() ? "ok" : result.getFailureDetail();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = PolicyEvaluationExtension.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
