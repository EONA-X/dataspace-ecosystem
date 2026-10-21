package org.eclipse.dse.iam.policy;

import java.util.List;
import java.util.Optional;

import static org.eclipse.edc.spi.core.CoreConstants.DSE_POLICY_NAMESPACES;
import static org.eclipse.edc.spi.core.CoreConstants.DSE_POLICY_NS;

/**
 * eox-policy terms and their left-operand IRIs. A term is accepted under every namespace of
 * {@link org.eclipse.edc.spi.core.CoreConstants#DSE_POLICY_NAMESPACES} (stable profile namespace and legacy namespace),
 * with the same function, bindings and semantics.
 */
public interface PolicyConstants {
    String MEMBERSHIP_CREDENTIAL_TYPE = "MembershipCredential";
    String DOMAIN_CREDENTIAL_TYPE = "DomainCredential";

    String MEMBERSHIP_CONSTRAINT = "Membership";
    /**
     * Legacy-namespace IRI of {@link #MEMBERSHIP_CONSTRAINT}.
     */
    String DSE_MEMBERSHIP_CONSTRAINT = DSE_POLICY_NS + MEMBERSHIP_CONSTRAINT;

    String GENERIC_CLAIM_CONSTRAINT = "GenericClaim";
    /**
     * Legacy-namespace IRI of {@link #GENERIC_CLAIM_CONSTRAINT}.
     */
    String DSE_GENERIC_CLAIM_CONSTRAINT = DSE_POLICY_NS + GENERIC_CLAIM_CONSTRAINT;

    String RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT = "RestrictedDiscoveryClaim";
    /**
     * Legacy-namespace IRI of {@link #RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT}.
     */
    String DSE_RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT = DSE_POLICY_NS + RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT;

    /**
     * The IRIs of an eox-policy term, one per accepted namespace, stable first.
     */
    static List<String> termIris(String term) {
        return DSE_POLICY_NAMESPACES.stream().map(namespace -> namespace + term).toList();
    }

    /**
     * Whether the left operand starts with the IRI of the term, in any accepted namespace.
     */
    static boolean startsWithTerm(String leftOperand, String term) {
        return leftOperand != null && termIris(term).stream().anyMatch(leftOperand::startsWith);
    }

    /**
     * For a claim-path left operand {@code <namespace><term>.$.<CredentialType>.<path>}, the term IRI it is written
     * with ({@code <namespace><term>}), so that the caller strips exactly that prefix, whatever the namespace length.
     */
    static Optional<String> claimTermIri(Object leftOperand, String term) {
        if (!(leftOperand instanceof String left)) {
            return Optional.empty();
        }
        return termIris(term).stream().filter(iri -> left.startsWith(iri + ".$")).findFirst();
    }
}
