package io.github.hectorvent.floci.services.securityadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SigV4CredentialScope;
import io.github.hectorvent.floci.services.guardduty.GuardDutyService;
import io.github.hectorvent.floci.services.guardduty.model.AdminAccount;
import io.github.hectorvent.floci.services.macie2.MacieController;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class SecurityAdminController {
    private final GuardDutyService guardDutyService;
    private final MacieController macieController;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;

    @Inject
    public SecurityAdminController(GuardDutyService guardDutyService, MacieController macieController,
                                   ObjectMapper mapper, RegionResolver regionResolver) {
        this.guardDutyService = guardDutyService;
        this.macieController = macieController;
        this.mapper = mapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/admin")
    public Response listAdmin(@Context HttpHeaders headers,
                              @Context UriInfo uriInfo,
                              @QueryParam("maxResults") String maxResults,
                              @QueryParam("nextToken") String nextToken) {
        String service = SigV4CredentialScope.serviceName(headers.getHeaderString("Authorization"))
                .or(() -> SigV4CredentialScope.serviceNameFromCredential(
                        uriInfo.getQueryParameters().getFirst("X-Amz-Credential")))
                .orElse("");
        if ("macie2".equals(service)) {
            String region = headers.getHeaderString("Authorization") != null
                    ? regionResolver.resolveRegion(headers)
                    : regionResolver.resolveRegionFromPresignedCredential(
                            uriInfo.getQueryParameters().getFirst("X-Amz-Credential"));
            return macieController.listAdminInternal(region);
        }
        if ("guardduty".equals(service) || service.isBlank()) {
            GuardDutyService.Page<AdminAccount> page = guardDutyService.listOrganizationAdminAccounts(
                    regionResolver.resolveRegion(headers), maxResults, nextToken);
            var response = mapper.createObjectNode();
            var accounts = response.putArray("adminAccounts");
            for (AdminAccount account : page.items()) {
                accounts.add(mapper.valueToTree(account));
            }
            if (page.nextToken() != null) {
                response.put("nextToken", page.nextToken());
            }
            return Response.ok(response).build();
        }
        throw new AwsException("UnknownOperationException", "GET /admin is not supported for " + service + ".", 400);
    }
}
