package io.floci.aws.services.ce;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Set;

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
    /** The reads that take a {@code BillingViewArn}, which they answer the costs of. */
    private static final Set<String> BILLING_VIEW_READS = Set.of("GetCostAndUsage", "GetCostAndUsageWithResources",
            "GetDimensionValues", "GetTags", "GetCostCategories");

    private final CostExplorerService service;
    private final CostAnomalyService anomalies;

    private final RegionResolver regionResolver;

    @Inject
    public CostExplorerJsonHandler(CostExplorerService service, CostAnomalyService anomalies,
                                   RegionResolver regionResolver) {
        this.service = service;
        this.anomalies = anomalies;
        this.regionResolver = regionResolver;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("CostExplorer action: {0}", action);
        if (BILLING_VIEW_READS.contains(action)) {
            requireServedBillingView(request);
        }
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
            case "CreateAnomalyMonitor" -> Response.ok(anomalies.createMonitor(request)).build();
            case "GetAnomalyMonitors" -> Response.ok(anomalies.getMonitors(request)).build();
            case "UpdateAnomalyMonitor" -> Response.ok(anomalies.updateMonitor(request)).build();
            case "DeleteAnomalyMonitor" -> {
                anomalies.deleteMonitor(request);
                yield Response.ok("{}").build();
            }
            case "CreateAnomalySubscription" -> Response.ok(anomalies.createSubscription(request)).build();
            case "GetAnomalySubscriptions" -> Response.ok(anomalies.getSubscriptions(request)).build();
            case "UpdateAnomalySubscription" -> Response.ok(anomalies.updateSubscription(request)).build();
            case "DeleteAnomalySubscription" -> {
                anomalies.deleteSubscription(request);
                yield Response.ok("{}").build();
            }
            case "ListTagsForResource" -> Response.ok(anomalies.listTags(request)).build();
            case "TagResource" -> {
                anomalies.tagResource(request);
                yield Response.ok("{}").build();
            }
            case "UntagResource" -> {
                anomalies.untagResource(request);
                yield Response.ok("{}").build();
            }
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AWSInsightsIndexService." + action))
                    .build();
        };
    }

    /**
     * Refuses a billing view other than the caller's primary one, the costs of the whole account, the only view Floci
     * answers the costs of: AWS answers {@code ResourceNotFoundException}, "The specified ARN in the request doesn't
     * exist.", for a view it does not hold. AWS does not document the primary view's ARN; Floci takes it as
     * {@code billingview/primary}. The view is that ARN exactly, of AWS Billing, global, in the request's partition
     * and account, as IAM checks it: one of another service or with a Region names no view IAM could check.
     */
    private void requireServedBillingView(JsonNode request) {
        String view = request.path("BillingViewArn").asText("");
        if (view.isEmpty()) {
            return;
        }
        if (!regionResolver.buildGlobalArn("billing", "billingview/primary").equals(view)) {
            throw new AwsException("ResourceNotFoundException", "The specified ARN in the request doesn't exist.", 400);
        }
    }
}
