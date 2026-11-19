package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsPartition;
import io.floci.aws.core.common.AwsPartitions;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.common.XmlParser;
import io.floci.aws.services.cloudtrail.CloudTrailService;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

/**
 * Records IAM API calls as CloudTrail management events, as the IAM User Guide says CloudTrail
 * does: "CloudTrail logs all authenticated API requests to IAM and AWS STS API operations"
 * (https://docs.aws.amazon.com/IAM/latest/UserGuide/cloudtrail-integration.html). A call is recorded
 * whether it succeeds, fails, or is refused by IAM enforcement.
 *
 * <p>The record follows that page's and the CloudTrail log file examples' IAM events
 * (https://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-log-file-examples.html):
 * {@code eventSource} {@code iam.amazonaws.com}, {@code awsRegion} the partition's IAM signing Region
 * ({@code us-east-1} in {@code aws}), {@code requestParameters} the request's members, and
 * {@code responseElements} null for a read-only call, as "When logging a read-only event,
 * CloudTrail redacts the responseElements information", and the call's result otherwise. Which
 * call is read-only is AWS's Service Reference's access level ({@link IamActionResources#isReadOnly});
 * how each member is named and typed, which members are left out as sensitive, and the name each
 * error is recorded under are botocore's IAM model ({@link IamCloudTrailShapes}).
 *
 * <p>A call IAM enforcement refuses carries the refusal in the format IAM's guide gives access
 * denied messages, "User user is not authorized to perform action on resource because context"
 * (https://docs.aws.amazon.com/IAM/latest/UserGuide/troubleshoot_access-denied.html), and
 * {@code "requestParameters": null}, as AWS records an authorization refusal.
 */
@ApplicationScoped
public class IamCloudTrailEvents {

    private static final String EVENT_SOURCE = ServicePrincipals.of("iam");

    private final CloudTrailService cloudTrail;
    private final AccountResolver accounts;
    private final RequestContext requestContext;
    private final CurrentVertxRequest currentRequest;

    @Inject
    public IamCloudTrailEvents(CloudTrailService cloudTrail, AccountResolver accounts,
                               RequestContext requestContext, CurrentVertxRequest currentRequest) {
        this.cloudTrail = cloudTrail;
        this.accounts = accounts;
        this.requestContext = requestContext;
        this.currentRequest = currentRequest;
    }

    /** Records the call {@code response} answered. */
    public void record(String action, MultivaluedMap<String, String> params, String authorization,
                       Response response) {
        if (!cloudTrail.hasTrails() || response == null || !(response.getEntity() instanceof String xml)) {
            return;
        }
        String errorCode = response.getStatus() >= 400 ? XmlParser.extractFirst(xml, "Code", null) : null;
        if ("UnsupportedOperation".equals(errorCode)) {
            return;
        }
        String errorMessage = errorCode == null ? null : XmlParser.extractFirst(xml, "Message", null);
        // A read-only call's result is left out: "When logging a read-only event, CloudTrail redacts
        // the responseElements information".
        ObjectNode result = errorCode == null && !IamActionResources.isReadOnly(action)
                ? IamCloudTrailShapes.get().result(action, xml) : null;
        cloudTrail.emitManagementEvent(event(action, params, accounts.extractAccessKeyId(authorization),
                requestContext.getRegion(), request(currentRequest.getCurrent()), result,
                XmlParser.extractFirst(xml, "RequestId", null), errorCode, errorMessage));
    }

    /**
     * The record of a call IAM enforcement refused before IAM ran it, named after the request's
     * {@code Action}; null for a request that names none.
     */
    public static CloudTrailService.ManagementEventInput denied(MultivaluedMap<String, String> params,
                                                                String accessKeyId, String region,
                                                                RoutingContext routingContext,
                                                                String errorCode, String errorMessage) {
        String action = params.getFirst("Action");
        if (action == null || action.isBlank()) {
            return null;
        }
        return event(action, null, accessKeyId, region, request(routingContext), null, null,
                errorCode, errorMessage);
    }

    private static HttpServerRequest request(RoutingContext routingContext) {
        return routingContext == null ? null : routingContext.request();
    }

    private static CloudTrailService.ManagementEventInput event(String action, MultivaluedMap<String, String> params,
                                                                String accessKeyId, String region,
                                                                HttpServerRequest request, ObjectNode result,
                                                                String requestId, String errorCode,
                                                                String errorMessage) {
        String sourceIp = request == null || request.remoteAddress() == null ? null : request.remoteAddress().host();
        String userAgent = request == null ? null : request.getHeader("User-Agent");
        IamCloudTrailShapes shapes = IamCloudTrailShapes.get();
        return new CloudTrailService.ManagementEventInput(EVENT_SOURCE, action, iamRegion(region), accessKeyId,
                params == null ? null : shapes.requestParameters(action, params), result,
                IamActionResources.isReadOnly(action), sourceIp, userAgent, requestId,
                shapes.recordedErrorCode(errorCode), errorMessage);
    }

    /** IAM's events are recorded in the Region IAM's endpoint signs for in the request's partition. */
    private static String iamRegion(String region) {
        AwsPartition partition = AwsPartitions.forRegionOrCommercial(region);
        return partition.globalEndpoint("iam").map(AwsPartition.GlobalEndpoint::signingRegion).orElse(region);
    }
}
