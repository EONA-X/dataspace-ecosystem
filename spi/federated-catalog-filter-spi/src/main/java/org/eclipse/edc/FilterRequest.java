package org.eclipse.edc;

import org.eclipse.edc.spi.iam.TokenRepresentation;

/**
 * participantDid is accepted for wire compatibility but ignored server-side: the caller's
 * identity is always derived from its verified credentials, never from this field (see
 * FederatedCatalogService#filterCatalog).
 */
public record FilterRequest(TokenRepresentation tokenRepresentation, String participantDid) {
}