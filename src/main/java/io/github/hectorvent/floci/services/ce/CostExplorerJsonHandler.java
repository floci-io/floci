package io.github.hectorvent.floci.services.ce;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for AWS Cost Explorer operations.
 * Dispatches {@code X-Amz-Target: AWSInsightsIndexService.*} actions to
 * {@link CostExplorerService}.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_Operations_AWS_Cost_Explorer_Service.html">AWS Cost Explorer API</a>
 */
@ApplicationScoped
public class CostExplorerJsonHandler {

    private static final Logger LOG = Logger.getLogger(CostExplorerJsonHandler.class);

    private final CostExplorerService service;

    @Inject
    public CostExplorerJsonHandler(CostExplorerService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("CostExplorer action: {0}", action);
        return switch (action) {
            case "GetCostAndUsage" -> Response.ok(service.getCostAndUsage(request, region)).build();
            case "GetCostAndUsageWithResources" -> Response.ok(service.getCostAndUsageWithResources(request, region)).build();
            case "GetDimensionValues" -> Response.ok(service.getDimensionValues(request, region)).build();
            case "GetTags" -> Response.ok(service.getTags(request, region)).build();
            case "GetReservationCoverage" -> Response.ok(service.getReservationCoverage()).build();
            case "GetReservationUtilization" -> Response.ok(service.getReservationUtilization()).build();
            case "GetSavingsPlansCoverage" -> Response.ok(service.getSavingsPlansCoverage()).build();
            case "GetSavingsPlansUtilization" -> Response.ok(service.getSavingsPlansUtilization()).build();
            case "GetCostCategories" -> Response.ok(service.getCostCategories(request)).build();
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AWSInsightsIndexService." + action))
                    .build();
        };
    }
}
