package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.cognito.CognitoOAuthController;
import io.github.hectorvent.floci.services.cognito.CognitoWellKnownController;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Provider
public class ServiceEnabledFilter implements ContainerRequestFilter {

    private static final Pattern AUTH_SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Context
    ResourceInfo resourceInfo;

    private final ServiceRegistry serviceRegistry;

    @Inject
    public ServiceEnabledFilter(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String serviceKey = resolveServiceKey(ctx);
        if (serviceKey == null) {
            return;
        }
        if (!serviceRegistry.isServiceEnabled(serviceKey)) {
            ctx.abortWith(disabledResponse(ctx, serviceKey));
        }
    }

    private String resolveServiceKey(ContainerRequestContext ctx) {
        String target = ctx.getHeaderString("X-Amz-Target");
        if (target != null) {
            return serviceKeyFromTarget(target);
        }

        String auth = ctx.getHeaderString("Authorization");
        if (auth != null) {
            Matcher m = AUTH_SERVICE_PATTERN.matcher(auth);
            if (m.find()) {
                return mapCredentialScope(m.group(1).toLowerCase());
            }
        }

        return serviceKeyFromMatchedResource();
    }

    private String serviceKeyFromTarget(String target) {
        if (target.startsWith("AmazonSSM.")) return "ssm";
        if (target.startsWith("AWSEvents.")) return "events";
        if (target.startsWith("Logs_20140328.")) return "logs";
        if (target.startsWith("secretsmanager.")) return "secretsmanager";
        if (target.startsWith("Kinesis_20131202.")) return "kinesis";
        if (target.startsWith("AmazonApiGatewayV2.")) return "apigatewayv2";
        if (target.startsWith("TrentService.")) return "kms";
        if (target.startsWith("AWSCognitoIdentityProviderService.")) return "cognito-idp";
        if (target.startsWith("DynamoDB_20120810.") || target.startsWith("DynamoDBStreams_20120810.")) return "dynamodb";
        if (target.startsWith("AmazonSQS.")) return "sqs";
        if (target.startsWith("SNS_20100331.")) return "sns";
        if (target.startsWith("AWSStepFunctions.")) return "states";
        if (target.startsWith("GraniteServiceVersion20100801.")) return "monitoring";
        return null;
    }

    private String mapCredentialScope(String scope) {
        return switch (scope) {
            case "execute-api" -> "apigateway";
            default -> scope;
        };
    }

    private String serviceKeyFromMatchedResource() {
        Class<?> resourceClass = resourceInfo != null ? resourceInfo.getResourceClass() : null;
        if (resourceClass == null) {
            return null;
        }
        if (CognitoOAuthController.class.equals(resourceClass)
                || CognitoWellKnownController.class.equals(resourceClass)) {
            return "cognito-idp";
        }
        return null;
    }

    private Response disabledResponse(ContainerRequestContext ctx, String serviceKey) {
        String message = "Service " + serviceKey + " is not enabled.";
        String target = ctx.getHeaderString("X-Amz-Target");
        String contentType = ctx.getMediaType() != null ? ctx.getMediaType().toString() : "";
        boolean jsonEndpoint = serviceKeyFromMatchedResource() != null;

        if (target != null || contentType.contains("json") || jsonEndpoint) {
            return Response.status(400)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new AwsErrorResponse("ServiceNotAvailableException", message))
                    .build();
        }

        String xml = new XmlBuilder()
                .start("ErrorResponse")
                  .start("Error")
                    .elem("Type", "Sender")
                    .elem("Code", "ServiceNotAvailableException")
                    .elem("Message", message)
                  .end("Error")
                  .elem("RequestId", java.util.UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(400).entity(xml).type(MediaType.APPLICATION_XML).build();
    }
}
