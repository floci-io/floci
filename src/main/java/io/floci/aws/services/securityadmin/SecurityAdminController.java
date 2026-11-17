package io.floci.aws.services.securityadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.SigV4CredentialScope;
import io.floci.aws.services.guardduty.GuardDutyService;
import io.floci.aws.services.guardduty.model.AdminAccount;
import io.floci.aws.services.macie2.MacieController;
import io.floci.aws.services.securityhub.SecurityHubController;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class SecurityAdminController {
    private final GuardDutyService guardDutyService;
    private final MacieController macieController;
    private final SecurityHubController securityHubController;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;

    @Inject
    public SecurityAdminController(GuardDutyService guardDutyService, MacieController macieController,
                                   SecurityHubController securityHubController,
                                   ObjectMapper mapper, RegionResolver regionResolver) {
        this.guardDutyService = guardDutyService;
        this.macieController = macieController;
        this.securityHubController = securityHubController;
        this.mapper = mapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/admin")
    public Response listAdmin(@Context HttpHeaders headers,
                              @Context UriInfo uriInfo,
                              @QueryParam("maxResults") String maxResults,
                              @QueryParam("nextToken") String nextToken) {
        String service = signingService(headers, uriInfo);
        if ("macie2".equals(service)) {
            return macieController.listAdminInternal(signingRegion(headers, uriInfo));
        }
        if ("guardduty".equals(service) || service.isBlank()) {
            GuardDutyService.Page<AdminAccount> page = guardDutyService.listOrganizationAdminAccounts(
                    regionResolver.resolveRegion(headers), maxResults, nextToken);
            ObjectNode response = mapper.createObjectNode();
            ArrayNode accounts = response.putArray("adminAccounts");
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

    /** Macie CreateMember and Security Hub CreateMembers share POST /members; the signing scope picks one. */
    @POST
    @Path("/members")
    public Response createMembers(@Context HttpHeaders headers, @Context UriInfo uriInfo, String body) {
        String service = signingService(headers, uriInfo);
        if ("securityhub".equals(service)) {
            return securityHubController.createMembers(signingRegion(headers, uriInfo), body);
        }
        if ("macie2".equals(service) || service.isBlank()) {
            return macieController.createMember(signingRegion(headers, uriInfo), body);
        }
        throw new AwsException("UnknownOperationException", "POST /members is not supported for " + service + ".", 400);
    }

    /** Macie and Security Hub ListMembers share GET /members, with differently cased query parameters. */
    @GET
    @Path("/members")
    public Response listMembers(@Context HttpHeaders headers, @Context UriInfo uriInfo) {
        String service = signingService(headers, uriInfo);
        MultivaluedMap<String, String> query = uriInfo.getQueryParameters();
        if ("securityhub".equals(service)) {
            return securityHubController.listMembers(signingRegion(headers, uriInfo), query.getFirst("OnlyAssociated"),
                    query.getFirst("MaxResults"), query.getFirst("NextToken"));
        }
        if ("macie2".equals(service) || service.isBlank()) {
            return macieController.listMembers(signingRegion(headers, uriInfo), query.getFirst("maxResults"),
                    query.getFirst("nextToken"), query.getFirst("onlyAssociated"));
        }
        throw new AwsException("UnknownOperationException", "GET /members is not supported for " + service + ".", 400);
    }

    /** The service a request is signed for, from its Authorization header or, presigned, its X-Amz-Credential. */
    private static String signingService(HttpHeaders headers, UriInfo uriInfo) {
        return SigV4CredentialScope.serviceName(headers.getHeaderString("Authorization"))
                .or(() -> SigV4CredentialScope.serviceNameFromCredential(
                        uriInfo.getQueryParameters().getFirst("X-Amz-Credential")))
                .orElse("");
    }

    /** The region a request is signed for, read from the same place as {@link #signingService}. */
    private String signingRegion(HttpHeaders headers, UriInfo uriInfo) {
        return headers.getHeaderString("Authorization") != null
                ? regionResolver.resolveRegion(headers)
                : regionResolver.resolveRegionFromPresignedCredential(
                        uriInfo.getQueryParameters().getFirst("X-Amz-Credential"));
    }
}
