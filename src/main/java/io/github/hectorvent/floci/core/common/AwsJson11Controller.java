package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.acm.AcmJsonHandler;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2JsonHandler;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsHandler;
import io.github.hectorvent.floci.services.cognito.CognitoJsonHandler;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler;
import io.github.hectorvent.floci.services.kinesis.KinesisJsonHandler;
import io.github.hectorvent.floci.services.kms.KmsJsonHandler;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerJsonHandler;
import io.github.hectorvent.floci.services.ssm.SsmJsonHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Generic dispatcher for all AWS services that use the application/x-amz-json-1.1 protocol.
 * Routes requests to the appropriate service handler based on the X-Amz-Target header prefix.
 * <p>
 * Currently supported services:
 * - SSM (AmazonSSM.*)
 * - EventBridge (AmazonEventBridge.*)
 * - CloudWatch Logs (Logs_20140328.*)
 */
@Path("/")
public class AwsJson11Controller {

    private static final Logger LOG = Logger.getLogger(AwsJson11Controller.class);

    private static final String SSM_TARGET_PREFIX = "AmazonSSM.";
    private static final String EVENTBRIDGE_TARGET_PREFIX = "AWSEvents.";
    private static final String LOGS_TARGET_PREFIX = "Logs_20140328.";
    private static final String SECRETS_MANAGER_TARGET_PREFIX = "secretsmanager.";
    private static final String KINESIS_TARGET_PREFIX = "Kinesis_20131202.";
    private static final String APIGW_V2_TARGET_PREFIX = "AmazonApiGatewayV2.";
    private static final String KMS_TARGET_PREFIX = "TrentService.";
    private static final String COGNITO_TARGET_PREFIX = "AWSCognitoIdentityProviderService.";
    private static final String ACM_TARGET_PREFIX = "CertificateManager.";

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final SsmJsonHandler ssmJsonHandler;
    private final EventBridgeHandler eventBridgeHandler;
    private final CloudWatchLogsHandler cloudWatchLogsHandler;
    private final SecretsManagerJsonHandler secretsManagerJsonHandler;
    private final KinesisJsonHandler kinesisJsonHandler;
    private final ApiGatewayV2JsonHandler apigwV2JsonHandler;
    private final KmsJsonHandler kmsJsonHandler;
    private final CognitoJsonHandler cognitoJsonHandler;
    private final AcmJsonHandler acmJsonHandler;

    @Inject
    public AwsJson11Controller(ObjectMapper objectMapper, RegionResolver regionResolver,
                               SsmJsonHandler ssmJsonHandler, EventBridgeHandler eventBridgeHandler,
                               CloudWatchLogsHandler cloudWatchLogsHandler,
                               SecretsManagerJsonHandler secretsManagerJsonHandler,
                               KinesisJsonHandler kinesisJsonHandler,
                               ApiGatewayV2JsonHandler apigwV2JsonHandler,
                               KmsJsonHandler kmsJsonHandler, CognitoJsonHandler cognitoJsonHandler,
                               AcmJsonHandler acmJsonHandler) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.ssmJsonHandler = ssmJsonHandler;
        this.eventBridgeHandler = eventBridgeHandler;
        this.cloudWatchLogsHandler = cloudWatchLogsHandler;
        this.secretsManagerJsonHandler = secretsManagerJsonHandler;
        this.kinesisJsonHandler = kinesisJsonHandler;
        this.apigwV2JsonHandler = apigwV2JsonHandler;
        this.kmsJsonHandler = kmsJsonHandler;
        this.cognitoJsonHandler = cognitoJsonHandler;
        this.acmJsonHandler = acmJsonHandler;
    }

    @POST
    @Consumes("application/x-amz-json-1.1")
    @Produces(MediaType.APPLICATION_JSON)
    public Response handle(
            @HeaderParam("X-Amz-Target") String target,
            @Context HttpHeaders httpHeaders,
            String body) {

        if (target == null) {
            return null;
        }

        String prefix;
        String serviceName;

        if (target.startsWith(SSM_TARGET_PREFIX)) {
            prefix = SSM_TARGET_PREFIX;
            serviceName = "SSM";
        } else if (target.startsWith(EVENTBRIDGE_TARGET_PREFIX)) {
            prefix = EVENTBRIDGE_TARGET_PREFIX;
            serviceName = "EventBridge";
        } else if (target.startsWith(LOGS_TARGET_PREFIX)) {
            prefix = LOGS_TARGET_PREFIX;
            serviceName = "Logs";
        } else if (target.startsWith(SECRETS_MANAGER_TARGET_PREFIX)) {
            prefix = SECRETS_MANAGER_TARGET_PREFIX;
            serviceName = "SecretsManager";
        } else if (target.startsWith(KINESIS_TARGET_PREFIX)) {
            prefix = KINESIS_TARGET_PREFIX;
            serviceName = "Kinesis";
        } else if (target.startsWith(APIGW_V2_TARGET_PREFIX)) {
            prefix = APIGW_V2_TARGET_PREFIX;
            serviceName = "ApiGatewayV2";
        } else if (target.startsWith(KMS_TARGET_PREFIX)) {
            prefix = KMS_TARGET_PREFIX;
            serviceName = "KMS";
        } else if (target.startsWith(COGNITO_TARGET_PREFIX)) {
            prefix = COGNITO_TARGET_PREFIX;
            serviceName = "Cognito";
        } else if (target.startsWith(ACM_TARGET_PREFIX)) {
            prefix = ACM_TARGET_PREFIX;
            serviceName = "ACM";
        } else {
            return Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: " + target))
                    .build();
        }

        String action = target.substring(prefix.length());
        LOG.infov("AwsJson11Controller {0} action: {1}", serviceName, action);

        try {
            JsonNode request = objectMapper.readTree(body);
            String region = regionResolver.resolveRegion(httpHeaders);

            return switch (serviceName) {
                case "SSM" -> ssmJsonHandler.handle(action, request, region);
                case "EventBridge" -> eventBridgeHandler.handle(action, request, region);
                case "Logs" -> cloudWatchLogsHandler.handle(action, request, region);
                case "SecretsManager" -> secretsManagerJsonHandler.handle(action, request, region);
                case "Kinesis" -> kinesisJsonHandler.handle(action, request, region);
                case "ApiGatewayV2" -> apigwV2JsonHandler.handle(action, request, region);
                case "KMS" -> kmsJsonHandler.handle(action, request, region);
                case "Cognito" -> cognitoJsonHandler.handle(action, request, region);
                case "ACM" -> acmJsonHandler.handle(action, request, region);
                default -> null;
            };
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new AwsErrorResponse(e.getErrorCode(), e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorf("Error processing %s request", serviceName, e);
            return Response.status(500)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new AwsErrorResponse("InternalServerError", e.getMessage()))
                    .build();
        }
    }
}
