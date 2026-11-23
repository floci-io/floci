package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsPartition;
import io.floci.aws.core.common.AwsPartitions;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.RequestSourceAddress;
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

import java.util.Optional;

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
 * <p>A call IAM enforcement refuses carries the code, the message and the request ID its refusal
 * answered with, so the record and the response match, and {@code "requestParameters": null}, as AWS
 * records an authorization refusal. Every call is recorded, a trail or not, since event history keeps
 * every management event.
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

    /**
     * The caller signing with {@code authorization}'s key, as a record names it, read before the call runs:
     * a call such as {@code DeleteAccessKey} can remove the very key it was signed with.
     */
    public ObjectNode caller(String authorization) {
        return cloudTrail.userIdentity(accounts.extractAccessKeyId(authorization));
    }

    /**
     * Records the call {@code response} answered, made by {@code caller}, as {@link #caller} read it. It is
     * recorded whether or not a trail selects it, as event history keeps every management event.
     */
    public void record(String action, MultivaluedMap<String, String> params, String authorization,
                       ObjectNode caller, Response response) {
        if (response == null || !(response.getEntity() instanceof String xml)) {
            return;
        }
        Answer answer = Answer.of(xml, response.getStatus() >= 400);
        if ("UnsupportedOperation".equals(answer.errorCode())) {
            return;
        }
        // A read-only call's result is left out: "When logging a read-only event, CloudTrail redacts
        // the responseElements information".
        ObjectNode result = answer.errorCode() == null && !IamActionResources.isReadOnly(action)
                ? IamCloudTrailShapes.get().result(action, xml) : null;
        cloudTrail.emitManagementEvent(event(action, params, accounts.extractAccessKeyId(authorization), caller,
                requestContext.getRegion(), request(currentRequest.getCurrent()), result, answer));
    }

    /**
     * The record of a call IAM enforcement refused with {@code refusal} before IAM ran it, named after the
     * request's {@code Action} and carrying the code, message and request ID the refusal answered with; null
     * for a request that names none.
     */
    public static CloudTrailService.ManagementEventInput denied(MultivaluedMap<String, String> params,
                                                                String accessKeyId, String region,
                                                                RoutingContext routingContext, Response refusal) {
        String action = params.getFirst("Action");
        if (action == null || action.isBlank()) {
            return null;
        }
        return event(action, null, accessKeyId, null, region, request(routingContext), null,
                Answer.of(refusal.getEntity() instanceof String xml ? xml : "", true));
    }

    /** The request ID a Query response answered with, and for a failed one its error's code and message. */
    private record Answer(String requestId, String errorCode, String errorMessage) {

        static Answer of(String xml, boolean failed) {
            String code = failed ? XmlParser.extractFirst(xml, "Code", null) : null;
            return new Answer(XmlParser.extractFirst(xml, "RequestId", null), code,
                    code == null ? null : XmlParser.extractFirst(xml, "Message", null));
        }
    }

    private static HttpServerRequest request(RoutingContext routingContext) {
        return routingContext == null ? null : routingContext.request();
    }

    private static CloudTrailService.ManagementEventInput event(String action, MultivaluedMap<String, String> params,
                                                                String accessKeyId, ObjectNode caller, String region,
                                                                HttpServerRequest request, ObjectNode result,
                                                                Answer answer) {
        String sourceIp = request == null ? null : RequestSourceAddress.of(request);
        String userAgent = request == null ? null : request.getHeader("User-Agent");
        IamCloudTrailShapes shapes = IamCloudTrailShapes.get();
        // IAM's events are recorded in the Region IAM's global endpoint signs for in the request's
        // partition, and as a global service's, which only a trail including global service events records.
        Optional<AwsPartition.GlobalEndpoint> iam = AwsPartitions.forRegionOrCommercial(region).globalEndpoint("iam");
        return new CloudTrailService.ManagementEventInput(EVENT_SOURCE, action,
                iam.map(AwsPartition.GlobalEndpoint::signingRegion).orElse(region), accessKeyId,
                params == null ? null : shapes.requestParameters(action, params), result,
                IamActionResources.isReadOnly(action), sourceIp, userAgent, answer.requestId(),
                shapes.recordedErrorCode(answer.errorCode()), answer.errorMessage(), iam.isPresent(), caller);
    }
}
