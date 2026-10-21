package org.eclipse.edc.spi.core;

import java.util.List;

public interface CoreConstants {
    String DSE_NS = "https://w3id.org/eonax/v0.0.1/ns/";
    String DSE_POLICY_PREFIX = "eox-policy";

    /**
     * Stable namespace of the Eona-X ODRL profile, the one the {@value #DSE_POLICY_PREFIX} JSON-LD prefix expands to
     * (EONA-X/eona-vocabularies-reference#13). Profile releases are identified by their {@code owl:versionIRI}
     * ({@code https://eona-x.eu/vocabulary/odrl-profile/v0.0.N#}), never by this namespace, so a policy written against
     * it keeps its meaning across profile releases.
     */
    String DSE_POLICY_PROFILE_NS = "https://eona-x.eu/vocabulary/odrl-profile#";

    /**
     * Legacy namespace of the eox-policy left operands. Still accepted, with the same functions and semantics as
     * {@link #DSE_POLICY_PROFILE_NS}, because stored policy definitions and live contract agreements carry it.
     * Stored policies are migrated by eona-x/backlog#995; support is dropped by eona-x/backlog#996.
     */
    String DSE_POLICY_NS = "https://w3id.org/eonax/policy/";

    /**
     * Every namespace accepted for eox-policy left operands, stable first.
     */
    List<String> DSE_POLICY_NAMESPACES = List.of(DSE_POLICY_PROFILE_NS, DSE_POLICY_NS);

    String DSE_VC_TYPE_SCOPE_ALIAS = "org.eclipse.dse.vc.type";
}
