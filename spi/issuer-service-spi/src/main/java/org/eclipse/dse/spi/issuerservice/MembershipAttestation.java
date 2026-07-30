package org.eclipse.dse.spi.issuerservice;

import java.time.Instant;
import java.util.List;

public record MembershipAttestation(String id, String holderId, String name, String membershipType, Instant membershipStartDate,
                                     List<String> groups) {
}
