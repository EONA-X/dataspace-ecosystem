package org.eclipse.edc.util;

import org.eclipse.dse.iam.core.DefaultScopeExtractor;
import org.eclipse.dse.iam.core.RequestCatalogDiscoveryContext;
import org.eclipse.dse.iam.policy.CatalogDiscoveryConstraintFunction;
import org.eclipse.dse.iam.policy.CatalogDiscoveryPolicyContext;
import org.eclipse.edc.catalog.transform.JsonObjectToCatalogTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDataServiceTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDatasetTransformer;
import org.eclipse.edc.catalog.transform.JsonObjectToDistributionTransformer;
import org.eclipse.edc.connector.controlplane.catalog.spi.Catalog;
import org.eclipse.edc.connector.controlplane.catalog.spi.Dataset;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToPolicyTransformer;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.CredentialSubject;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.Issuer;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiableCredential;
import org.eclipse.edc.jsonld.TitaniumJsonLd;
import org.eclipse.edc.jsonld.spi.JsonLd;
import org.eclipse.edc.participant.spi.ParticipantIdMapper;
import org.eclipse.edc.policy.engine.PolicyEngineImpl;
import org.eclipse.edc.policy.engine.RuleBindingRegistryImpl;
import org.eclipse.edc.policy.engine.ScopeFilter;
import org.eclipse.edc.policy.engine.spi.PolicyEngine;
import org.eclipse.edc.policy.engine.spi.RuleBindingRegistry;
import org.eclipse.edc.policy.engine.validation.RuleValidator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.iam.ClaimToken;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.types.TypeManager;
import org.eclipse.edc.transform.TypeTransformerRegistryImpl;
import org.eclipse.edc.transform.spi.TypeTransformerRegistry;
import org.eclipse.edc.transform.transformer.edc.to.JsonObjectToCriterionTransformer;
import org.eclipse.edc.transform.transformer.edc.to.JsonObjectToQuerySpecTransformer;
import org.eclipse.edc.transform.transformer.edc.to.JsonValueToGenericTypeTransformer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.eclipse.dse.iam.policy.CatalogDiscoveryPolicyContext.CATALOG_DISCOVERY_SCOPE;
import static org.eclipse.dse.iam.policy.PolicyConstants.DOMAIN_CREDENTIAL_TYPE;
import static org.eclipse.dse.iam.policy.PolicyConstants.DSE_RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.MEMBERSHIP_CREDENTIAL_TYPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.ContractNegotiationPolicyContext.NEGOTIATION_SCOPE;
import static org.eclipse.edc.connector.controlplane.contract.spi.policy.TransferProcessPolicyContext.TRANSFER_SCOPE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_USE_ACTION_ATTRIBUTE;
import static org.eclipse.edc.spi.constants.CoreConstants.JSON_LD;
import static org.eclipse.edc.util.IdentityServiceValidator.READ_ALL_CREDENTIAL_SCOPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FederatedCatalogFilterServiceTest {

    private static AuthorityCatalogFilterDidResolver didResolverRegistry = mock();
    private static PolicyEngine policyEngine = null;
    private static RuleBindingRegistry registry = new RuleBindingRegistryImpl();
    private static final String ISSUER = "did:web:issuer";
    private static final String SUBJECT = "did:web:subject";
    private static final String PARTICIPANT_DID = "did:web:participant";
    private static ParticipantIdMapper participantIdMapper = mock(ParticipantIdMapper.class);
    private static final String CATALOG_REPLY = "/catalogReply.json";
    private static TypeTransformerRegistry transformerRegistry = new TypeTransformerRegistryImpl();
    private static HttpClient httpClient = mock(HttpClient.class);
    private static HttpResponse<String> httpResponse = mock(HttpResponse.class);
    private static TypeManager typeManager = mock(TypeManager.class);
    private static Monitor monitor = mock();
    private static Collection<Catalog> filteredCatalogs;

    private static final String MULTI_OFFER_PARTIAL_ASSET = "multi-offer-partial-asset";
    private static final String MULTI_OFFER_PARTIAL_RESTRICTIVE_OFFER_ID =
            "cmVzdHJpY3RlZC1vZmZlcg==:bXVsdGktb2ZmZXItcGFydGlhbC1hc3NldA==:MDFhZTQ0NDEtOTk5OS00ZWEwLWFlNDMtMDFhZTQ0NDE5OTk5";
    private static final String MULTI_OFFER_PARTIAL_PERMISSIVE_OFFER_ID =
            "cGVybWlzc2l2ZS1vZmZlcg==:bXVsdGktb2ZmZXItcGFydGlhbC1hc3NldA==:MDJhZTQ0NDEtOTk5OS00ZWEwLWFlNDMtMDJhZTQ0NDE5OTk5";

    @BeforeAll
    static void init() throws IOException, InterruptedException {
        policyEngine = new PolicyEngineImpl(new ScopeFilter(registry), new RuleValidator(registry));
        policyEngine.registerPostValidator(RequestCatalogDiscoveryContext.class, new DefaultScopeExtractor<>(Set.of(READ_ALL_CREDENTIAL_SCOPE)));
        registry.bind(ODRL_USE_ACTION_ATTRIBUTE, CATALOG_DISCOVERY_SCOPE);
        policyEngine.registerFunction(CatalogDiscoveryPolicyContext.class, Permission.class, new CatalogDiscoveryConstraintFunction<>());
        registry.dynamicBind(s -> s.startsWith(DSE_RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT) ? Set.of(CATALOG_DISCOVERY_SCOPE, NEGOTIATION_SCOPE, TRANSFER_SCOPE) : Set.of());
        registerTransformers();
        when(didResolverRegistry.fetchCatalogFilterUrl()).thenReturn(Result.success("http://example.com/catalog"));
        String reply = loadCatalogFromFile(CATALOG_REPLY);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(reply);
        when(httpClient.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(httpResponse);

        JsonLd jsonLd = new TitaniumJsonLd(monitor);
        FederatedCatalogService service = new FederatedCatalogService(policyEngine, monitor, didResolverRegistry, transformerRegistry, jsonLd, httpClient);
        Collection<Catalog> catalogs = service.retrieveCatalog();

        var membershipVc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of(
                "hello", "world",
                "groups", List.of("EMDS", "ERDS", "ETDS", "DEPLOYTOUR"),
                "participantId", SUBJECT
        ));
        var domainVc = createVc(DOMAIN_CREDENTIAL_TYPE, Map.of("domain", "route"));
        var credentials = List.of(membershipVc, domainVc);
        ClaimToken tokens = ClaimToken.Builder.newInstance().claim("vc", credentials).build();

        filteredCatalogs = service.filterCatalog(catalogs, service.createContext(tokens), PARTICIPANT_DID);
    }

    private static void registerTransformers() {
        transformerRegistry.register(new JsonObjectToCatalogTransformer());
        transformerRegistry.register(new JsonObjectToDatasetTransformer());
        transformerRegistry.register(new JsonObjectToPolicyTransformer(participantIdMapper));
        transformerRegistry.register(new JsonObjectToDataServiceTransformer());
        transformerRegistry.register(new JsonObjectToDistributionTransformer());
        transformerRegistry.register(new JsonObjectToQuerySpecTransformer());
        transformerRegistry.register(new JsonObjectToCriterionTransformer());

        VcCatalogInitializer.registerTransformers(transformerRegistry);

        transformerRegistry.register(new JsonValueToGenericTypeTransformer(typeManager, JSON_LD));
    }

    private static Optional<Dataset> findDataset(String datasetId) {
        return filteredCatalogs.stream()
                .flatMap(c -> c.getDatasets().stream())
                .filter(d -> d.getId().equals(datasetId))
                .findFirst();
    }

    private static void assertDatasetVisible(String datasetId) {
        assertTrue(findDataset(datasetId).isPresent(), () -> "Expected dataset " + datasetId + " to be visible");
    }

    private static void assertDatasetHidden(String datasetId) {
        assertTrue(findDataset(datasetId).isEmpty(), () -> "Expected dataset " + datasetId + " to be hidden");
    }

    @Test
    void prohibitionOffer_isVisible() {
        assertDatasetVisible("prohibition-test");
    }

    @Test
    void obligationOffer_isVisible() {
        assertDatasetVisible("obligation-test");
    }

    @Test
    void restrictedRouteAsset_matchesParticipantDomain_isVisible() {
        assertDatasetVisible("restricted-route-asset");
    }

    @Test
    void restrictedTravelAsset_doesNotMatchParticipantDomain_isHidden() {
        assertDatasetHidden("restricted-travel-asset");
    }

    @Test
    void visibleRestrictedAsset_isVisible() {
        assertDatasetVisible("visible-restricted-asset");
    }

    @Test
    void restrictedAndAsset_unsatisfiableConstraint_isHidden() {
        assertDatasetHidden("restricted-and-asset");
    }

    @Test
    void visibleListAsset_isVisible() {
        assertDatasetVisible("visible-list-asset");
    }

    @Test
    void restrictedListAsset_isHidden() {
        assertDatasetHidden("restricted-list-asset");
    }

    @Test
    void groupsEmdsVisibleAsset_isVisible() {
        assertDatasetVisible("groups-emds-visible-asset");
    }

    @Test
    void groupsSecurityHiddenAsset_isHidden() {
        assertDatasetHidden("groups-security-hidden-asset");
    }

    @Test
    void participantVisibleAsset_isVisible() {
        assertDatasetVisible("participant-visible-asset");
    }

    @Test
    void participantHiddenAsset_isHidden() {
        assertDatasetHidden("participant-hidden-asset");
    }

    @Test
    void noPolicyAsset_isVisible() {
        assertDatasetVisible("no-policy-asset");
    }

    @Test
    void multiOfferAsset_oneOfferPasses_datasetVisibleWithOnlyPassingOfferKept() {
        Optional<Dataset> dataset = findDataset(MULTI_OFFER_PARTIAL_ASSET);
        assertTrue(dataset.isPresent(), "Expected dataset to remain visible when at least one offer passes");
        Map<String, Policy> offers = dataset.get().getOffers();
        assertEquals(1, offers.size());
        assertFalse(offers.containsKey(MULTI_OFFER_PARTIAL_RESTRICTIVE_OFFER_ID), "Failing offer should be dropped");
        assertTrue(offers.containsKey(MULTI_OFFER_PARTIAL_PERMISSIVE_OFFER_ID), "Passing offer should be kept");
    }

    @Test
    void multiOfferAsset_allOffersFail_datasetHidden() {
        assertDatasetHidden("multi-offer-all-fail-asset");
    }

    private static VerifiableCredential createVc(String type, Map<String, Object> claims) {
        return VerifiableCredential.Builder.newInstance()
                .type(type)
                .issuer(new Issuer(ISSUER))
                .issuanceDate(Instant.now())
                .credentialSubject(CredentialSubject.Builder.newInstance().claims(claims).id(SUBJECT).build())
                .build();
    }

    private static String loadCatalogFromFile(String resourcePath) throws IOException {
        String catalogReply;
        try (var is = FederatedCatalogFilterServiceTest.class.getResourceAsStream(resourcePath)) {
            catalogReply = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        return catalogReply;
    }
}
