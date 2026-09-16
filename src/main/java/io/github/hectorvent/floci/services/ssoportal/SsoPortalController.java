package io.github.hectorvent.floci.services.ssoportal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.ssoportal.model.PortalAccountInfo;
import io.github.hectorvent.floci.services.ssoportal.model.PortalRoleInfo;
import io.github.hectorvent.floci.services.ssoportal.model.PortalRoleCredentials;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class SsoPortalController {
    private final SsoPortalService service;
    private final ObjectMapper objectMapper;

    @Inject
    public SsoPortalController(SsoPortalService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/logout")
    public Response logout(@HeaderParam("x-amz-sso_bearer_token") String accessToken) {
        service.logout(accessToken);
        return Response.ok().build();
    }

    @GET
    @Path("/federation/credentials")
    public Response getRoleCredentials(
            @HeaderParam("x-amz-sso_bearer_token") String accessToken,
            @QueryParam("account_id") String accountId,
            @QueryParam("role_name") String roleName) {
        PortalRoleCredentials credentials = service.getRoleCredentials(accessToken, accountId, roleName);
        var response = objectMapper.createObjectNode();
        var roleCredentials = response.putObject("roleCredentials");
        roleCredentials.put("accessKeyId", credentials.accessKeyId());
        roleCredentials.put("expiration", credentials.expiration());
        roleCredentials.put("secretAccessKey", credentials.secretAccessKey());
        roleCredentials.put("sessionToken", credentials.sessionToken());
        return Response.ok(response).build();
    }

    @GET
    @Path("/assignment/roles")
    public Response listAccountRoles(
            @HeaderParam("x-amz-sso_bearer_token") String accessToken,
            @QueryParam("account_id") String accountId,
            @QueryParam("max_result") String maxResults,
            @QueryParam("next_token") String nextToken) {
        var page = service.listAccountRoles(accessToken, accountId, maxResults, nextToken);
        var response = objectMapper.createObjectNode();
        var roles = response.putArray("roleList");
        for (PortalRoleInfo role : page.items()) {
            var item = roles.addObject();
            item.put("accountId", role.accountId());
            item.put("roleName", role.roleName());
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/assignment/accounts")
    public Response listAccounts(
            @HeaderParam("x-amz-sso_bearer_token") String accessToken,
            @QueryParam("max_result") String maxResults,
            @QueryParam("next_token") String nextToken) {
        var page = service.listAccounts(accessToken, maxResults, nextToken);
        var response = objectMapper.createObjectNode();
        var accounts = response.putArray("accountList");
        for (PortalAccountInfo account : page.items()) {
            var item = accounts.addObject();
            item.put("accountId", account.accountId());
            if (account.accountName() != null) {
                item.put("accountName", account.accountName());
            }
            if (account.emailAddress() != null) {
                item.put("emailAddress", account.emailAddress());
            }
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }
}
