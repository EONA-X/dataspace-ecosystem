package org.eclipse.edc.issuerservice.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.dse.spi.issuerservice.DomainAttestation;
import org.eclipse.dse.spi.issuerservice.DomainAttestationStore;
import org.eclipse.edc.identityhub.api.Versions;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.web.spi.exception.NotAuthorizedException;

import java.util.Collection;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.eclipse.edc.spi.result.ServiceResult.from;
import static org.eclipse.edc.web.spi.exception.ServiceResultHandler.exceptionMapper;

@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path(Versions.UNSTABLE + "/participants/{participantContextId}/attestation-domain")
public class DomainAttestationApiController implements DomainAttestationAdminApi {

    private final DomainAttestationStore store;

    private final String authorizedDomain;

    public DomainAttestationApiController(DomainAttestationStore store, String authorizedDomain) {
        this.store = store;
        this.authorizedDomain = authorizedDomain;
    }


    @POST
    @Override
    public Response createDomainAttestation(@PathParam("participantContextId") String participantContextId, DomainAttestationDto domainAttestationDto) {
        if (StringUtils.isEmpty(authorizedDomain) || !authorizedDomain.equals(domainAttestationDto.domain())) {
            throw new NotAuthorizedException("%s domain is not authorized to be issued".formatted(domainAttestationDto.domain()));
        }
        var attestation = toDomainAttestation(domainAttestationDto);
        from(store.save(attestation)).orElseThrow(exceptionMapper(DomainAttestation.class));
        return Response.status(Response.Status.CREATED).entity(attestation.id()).build();
    }

    @PUT
    @Override
    public void updateDomainAttestation(@PathParam("participantContextId") String participantContextId, DomainAttestationDto domainAttestationDto) {
        if (StringUtils.isEmpty(authorizedDomain) || !authorizedDomain.equals(domainAttestationDto.domain())) {
            throw new NotAuthorizedException("%s domain is not authorized to be issued".formatted(domainAttestationDto.domain()));
        }
        var attestation = toDomainAttestation(domainAttestationDto);
        from(store.update(attestation)).orElseThrow(exceptionMapper(DomainAttestation.class));
    }

    @DELETE
    @Path("/{id}")
    @Override
    public void deleteDomainAttestation(@PathParam("participantContextId") String participantContextId, @PathParam("id") String id) {
        from(store.deleteById(id)).orElseThrow(exceptionMapper(DomainAttestation.class, id));
    }

    @POST
    @Path("/request")
    @Override
    public Collection<DomainAttestation> queryDomainAttestations(@PathParam("participantContextId") String participantContextId, QuerySpec querySpec) {
        if (querySpec == null) {
            querySpec = QuerySpec.Builder.newInstance().build();
        }
        return store.query(querySpec).toList();
    }

    private DomainAttestation toDomainAttestation(DomainAttestationDto dto) {
        return new DomainAttestation(dto.id(), dto.holderId(), dto.domain());
    }
}
