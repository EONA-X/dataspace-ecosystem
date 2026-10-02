package org.eclipse.edc.issuerservice.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.eclipse.dse.spi.issuerservice.MembershipAttestation;
import org.eclipse.dse.spi.issuerservice.MembershipAttestationStore;
import org.eclipse.edc.identityhub.api.Versions;
import org.eclipse.edc.spi.query.QuerySpec;

import java.time.Instant;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.eclipse.edc.spi.result.ServiceResult.from;
import static org.eclipse.edc.web.spi.exception.ServiceResultHandler.exceptionMapper;

@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path(Versions.UNSTABLE + "/participants/{participantContextId}/attestation-membership")
public class MembershipAttestationApiController implements MembershipAttestationAdminApi {

    private final MembershipAttestationStore store;

    public MembershipAttestationApiController(MembershipAttestationStore store) {
        this.store = store;
    }

    @POST
    @Path("/request")
    @Override
    public List<MembershipAttestation> queryMembershipAttestations(@PathParam("participantContextId") String participantContextId, QuerySpec spec) {
        if (spec == null) {
            spec = QuerySpec.Builder.newInstance().build();
        }
        return store.query(spec).toList();
    }

    @POST
    @Override
    public void createMembershipAttestation(@PathParam("participantContextId") String participantContextId, MembershipAttestationDto dto) {
        var attestation = toAttestation(dto);
        from(store.save(attestation)).orElseThrow(exceptionMapper(MembershipAttestation.class));
    }

    @PUT
    @Override
    public void updateMembershipAttestation(@PathParam("participantContextId") String participantContextId, MembershipAttestationDto dto) {
        var attestation = toAttestation(dto);
        from(store.update(attestation)).orElseThrow(exceptionMapper(MembershipAttestation.class, attestation.id()));
    }

    @DELETE
    @Path("/{id}")
    @Override
    public void deleteMembershipAttestation(@PathParam("participantContextId") String participantContextId, @PathParam("id") String id) {
        from(store.deleteById(id)).orElseThrow(exceptionMapper(MembershipAttestation.class, id));
    }

    private MembershipAttestation toAttestation(MembershipAttestationDto dto) {
        var groups = dto.groups() != null ? dto.groups() : List.<String>of();
        return new MembershipAttestation(dto.id(), dto.holderId(), dto.name(), dto.membershipType(), Instant.now(), groups);
    }
}