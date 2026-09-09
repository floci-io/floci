package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.acm.AcmJsonHandler;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsHandler;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsJsonHandler;
import io.github.hectorvent.floci.services.cognito.CognitoJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler;
import io.github.hectorvent.floci.services.kinesis.KinesisJsonHandler;
import io.github.hectorvent.floci.services.kms.KmsJsonHandler;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerJsonHandler;
import io.github.hectorvent.floci.services.sns.SnsJsonHandler;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.sqs.SqsQueryHandler;
import io.github.hectorvent.floci.services.ssm.SsmJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsJsonHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes API Gateway AWS integration requests to the correct internal service handler.
 *
 * <p>Parses integration URIs of the form
 * {@code arn:aws:apigateway:{region}:{service}:action/{ActionName}}
 * and dispatches to the matching JSON handler.
 */
@ApplicationScoped
public class AwsServiceRouter {

    private static final Logger LOG = Logger.getLogger(AwsServiceRouter.class);

    private final StepFunctionsJsonHandler stepFunctionsHandler;
    private final DynamoDbJsonHandler dynamoDbHandler;
    private final SqsJsonHandler sqsHandler;
    private final SqsQueryHandler sqsQueryHandler;
    private final SnsJsonHandler snsHandler;
    private final EventBridgeHandler eventBridgeHandler;
    private final SsmJsonHandler ssmHandler;
    private final KinesisJsonHandler kinesisHandler;
    private final CloudWatchLogsHandler logsHandler;
    private final CloudWatchMetricsJsonHandler metricsHandler;
    private final SecretsManagerJsonHandler secretsManagerHandler;
    private final KmsJsonHandler kmsHandler;
    private final CognitoJsonHandler cognitoHandler;
    private final AcmJsonHandler acmHandler;

    @Inject
    public AwsServiceRouter(StepFunctionsJsonHandler stepFunctionsHandler,
                            DynamoDbJsonHandler dynamoDbHandler,
                            SqsJsonHandler sqsHandler,
                            SqsQueryHandler sqsQueryHandler,
                            SnsJsonHandler snsHandler,
                            EventBridgeHandler eventBridgeHandler,
                            SsmJsonHandler ssmHandler,
                            KinesisJsonHandler kinesisHandler,
                            CloudWatchLogsHandler logsHandler,
                            CloudWatchMetricsJsonHandler metricsHandler,
                            SecretsManagerJsonHandler secretsManagerHandler,
                            KmsJsonHandler kmsHandler,
                            CognitoJsonHandler cognitoHandler,
                            AcmJsonHandler acmHandler) {
        this.stepFunctionsHandler = stepFunctionsHandler;
        this.dynamoDbHandler = dynamoDbHandler;
        this.sqsHandler = sqsHandler;
        this.sqsQueryHandler = sqsQueryHandler;
        this.snsHandler = snsHandler;
        this.eventBridgeHandler = eventBridgeHandler;
        this.ssmHandler = ssmHandler;
        this.kinesisHandler = kinesisHandler;
        this.logsHandler = logsHandler;
        this.metricsHandler = metricsHandler;
        this.secretsManagerHandler = secretsManagerHandler;
        this.kmsHandler = kmsHandler;
        this.cognitoHandler = cognitoHandler;
        this.acmHandler = acmHandler;
    }

    private static final Pattern ACTION_URI_PATTERN = Pattern.compile(
            "^arn:aws:apigateway:([^:]+):([^:]+):action/(.+)$"
    );
    private static final Pattern PATH_URI_PATTERN = Pattern.compile(
            "^arn:aws:apigateway:([^:]+):([^:]+):path/(.+)$"
    );

    /**
     * Parsed components of an API Gateway AWS integration URI.
     */
    public record IntegrationTarget(String region, String service, String action, String path) {
        public IntegrationTarget(String region, String service, String action) {
            this(region, service, action, null);
        }
    }

    /**
     * Parses an integration URI in either of the two forms AWS accepts:
     *
     * <ul>
     *   <li><b>action</b> — {@code arn:aws:apigateway:{region}:{service}:action/{Action}}.
     *       The action is encoded in the URI and the request template renders a JSON body.</li>
     *   <li><b>path</b> — {@code arn:aws:apigateway:{region}:{service}:path/{resourcePath}}.
     *       The action is not in the URI; it is carried in the rendered request body
     *       (the AWS query protocol, e.g. {@code Action=SendMessage&...}). For this form
     *       {@link IntegrationTarget#action()} is {@code null}.</li>
     * </ul>
     *
     * @return parsed target, or null if the URI format is not recognized
     */
    public IntegrationTarget parseIntegrationUri(String uri) {
        if (uri == null || !uri.startsWith("arn:aws:apigateway:")) {
            return null;
        }
        // arn:aws:apigateway:{region}:{service}:{action/{Action}|path/{resourcePath}}
        String[] parts = uri.split(":");
        if (parts.length < 6) {
            return null;
        }
        String region = parts[3];
        String service = parts[4];
        // parts[5] is either "action/{ActionName}" or "path/{resourcePath}".
        // A path may itself contain ':' (rare), so re-join the remainder.
        String resourceSpec = parts.length > 6
                ? String.join(":", java.util.Arrays.copyOfRange(parts, 5, parts.length))
                : parts[5];
        if (resourceSpec.startsWith("action/")) {
            String action = resourceSpec.substring("action/".length());
            return new IntegrationTarget(region, service, action);
        }
        if (resourceSpec.startsWith("path/")) {
            // Action is supplied by the rendered request body (query protocol).
            return new IntegrationTarget(region, service, null, resourceSpec.substring("path/".length()));
        }
        return null;
    }

    /**
     * Dispatches to the appropriate service handler.
     *
     * @param service     the AWS service name from the URI (e.g., "states", "dynamodb")
     * @param action      the action name (e.g., "StartExecution", "PutItem")
     * @param requestBody the JSON request body
     * @param region      the AWS region
     * @return the service response
     */
    public Response invoke(String service, String action, JsonNode requestBody, String region) {
        LOG.debugv("AWS integration dispatch: {0}:{1} in {2}", service, action, region);

        try {
            return switch (service) {
                case "states" -> stepFunctionsHandler.handle(action, requestBody, region);
                case "dynamodb" -> dynamoDbHandler.handle(action, requestBody, region);
                case "sqs" -> sqsHandler.handle(action, requestBody, region);
                case "sns" -> snsHandler.handle(action, requestBody, region);
                case "events" -> eventBridgeHandler.handle(action, requestBody, region);
                case "ssm" -> ssmHandler.handle(action, requestBody, region);
                case "kinesis" -> kinesisHandler.handle(action, requestBody, region);
                case "logs" -> logsHandler.handle(action, requestBody, region);
                case "monitoring" -> metricsHandler.handle(action, requestBody, region);
                case "secretsmanager" -> secretsManagerHandler.handle(action, requestBody, region);
                case "kms" -> kmsHandler.handle(action, requestBody, region);
                case "cognito-idp" -> cognitoHandler.handle(action, requestBody, region);
                case "acm" -> acmHandler.handle(action, requestBody, region);
                default -> throw new AwsException("UnknownService",
                        "Unsupported AWS service integration: " + service, 400);
            };
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalError",
                    e.getMessage() != null ? e.getMessage() : "Service invocation failed", 500);
        }
    }

    /**
     * Dispatches an AWS query-protocol (form-encoded) integration request.
     *
     * <p>Used for {@code path/}-style integration URIs whose VTL request template renders an
     * {@code application/x-www-form-urlencoded} body in the AWS query protocol, e.g.
     * {@code Action=SendMessage&QueueUrl=...&MessageBody=...}. The {@code Action} parameter
     * selects the operation, mirroring {@link io.github.hectorvent.floci.core.common.AwsQueryController}.
     *
     * @param service the AWS service name from the URI (e.g., "sqs")
     * @param params  the parsed form parameters, including {@code Action}
     * @param region  the AWS region
     * @return the service response (query-protocol XML)
     */
    public Response invokeQuery(String service, MultivaluedMap<String, String> params, String region) {
        String action = params.getFirst("Action");
        LOG.debugv("AWS query integration dispatch: {0}:{1} in {2}", service, action, region);

        if (action == null || action.isBlank()) {
            throw new AwsException("MissingAction",
                    "The request must contain the parameter Action", 400);
        }

        try {
            return switch (service) {
                case "sqs" -> sqsQueryHandler.handle(action, params, region);
                default -> throw new AwsException("UnknownService",
                        "Unsupported AWS query-protocol service integration: " + service, 400);
            };
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InternalError",
                    e.getMessage() != null ? e.getMessage() : "Service invocation failed", 500);
        }
    }
}
