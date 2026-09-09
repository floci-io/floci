package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Lambda account settings endpoint.
 *
 * <p>GetAccountSettings: {@code GET /2016-08-19/account-settings/}. The account limits mirror
 * AWS's fixed storage defaults plus the configured region concurrency limit; the usage block is
 * derived from the caller's stored functions.
 */
@Path("/2016-08-19")
@Produces(MediaType.APPLICATION_JSON)
public class LambdaAccountSettingsController {

    private static final long ACCOUNT_TOTAL_CODE_SIZE_BYTES = 80_530_636_800L;
    private static final long CODE_SIZE_UNZIPPED_BYTES = 262_144_000L;
    private static final long CODE_SIZE_ZIPPED_BYTES = 52_428_800L;

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaAccountSettingsController(LambdaService lambdaService,
                                           RegionResolver regionResolver,
                                           ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/account-settings")
    public Response getAccountSettings(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        LambdaService.AccountSettings settings = lambdaService.getAccountSettings(region);

        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode accountLimit = root.putObject("AccountLimit");
        accountLimit.put("TotalCodeSize", ACCOUNT_TOTAL_CODE_SIZE_BYTES);
        accountLimit.put("CodeSizeUnzipped", CODE_SIZE_UNZIPPED_BYTES);
        accountLimit.put("CodeSizeZipped", CODE_SIZE_ZIPPED_BYTES);
        accountLimit.put("ConcurrentExecutions", settings.concurrentExecutions());
        accountLimit.put("UnreservedConcurrentExecutions", settings.unreservedConcurrentExecutions());
        ObjectNode accountUsage = root.putObject("AccountUsage");
        accountUsage.put("TotalCodeSize", settings.totalCodeSize());
        accountUsage.put("FunctionCount", settings.functionCount());
        return Response.ok(root).build();
    }
}
