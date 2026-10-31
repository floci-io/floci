package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.MethodConfig;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Verifies Cognito user-pool tokens for REST execute-api methods. */
@ApplicationScoped
public class CognitoUserPoolAuthorizer {

    public enum Failure {
        UNAUTHORIZED
    }

    public record Result(Failure failure, String principalId, Map<String, Object> context) {}

    private final ApiGatewayService apiGatewayService;
    private final CognitoService cognitoService;

    @Inject
    public CognitoUserPoolAuthorizer(ApiGatewayService apiGatewayService, CognitoService cognitoService) {
        this.apiGatewayService = apiGatewayService;
        this.cognitoService = cognitoService;
    }

    public Result authorize(String region, String apiId, MethodConfig method, HttpHeaders headers) {
        if (method.getAuthorizerId() == null) {
            return new Result(Failure.UNAUTHORIZED, null, null);
        }
        try {
            Authorizer authorizer = apiGatewayService.getAuthorizer(region, apiId, method.getAuthorizerId());
            List<String> providerArns = authorizer.getProviderARNs();
            if (providerArns == null || providerArns.isEmpty()) {
                return new Result(Failure.UNAUTHORIZED, null, null);
            }
            String identitySource = authorizer.getIdentitySource();
            if (identitySource == null || !identitySource.startsWith("method.request.header.")) {
                return new Result(Failure.UNAUTHORIZED, null, null);
            }
            String headerName = identitySource.substring("method.request.header.".length());
            String authorization = headers.getHeaderString(headerName);
            if (authorization == null || authorization.isBlank()) {
                return new Result(Failure.UNAUTHORIZED, null, null);
            }
            String token = authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? authorization.substring(7).trim() : authorization.trim();
            CognitoService.VerifiedApiGatewayToken verified = cognitoService.verifyApiGatewayToken(token);
            String verifiedPoolArn = cognitoService.describeUserPool(verified.poolId()).getArn();
            if (!providerArns.contains(verifiedPoolArn)) {
                return new Result(Failure.UNAUTHORIZED, null, null);
            }
            // AWS answers 401, not 403, when the token claims none of the method's scopes, ID tokens included.
            List<String> requiredScopes = method.getAuthorizationScopes();
            if (requiredScopes != null && !requiredScopes.isEmpty()) {
                if (!"access".equals(verified.tokenUse())) {
                    return new Result(Failure.UNAUTHORIZED, null, null);
                }
                Set<String> granted = new HashSet<>();
                Object scopeClaim = verified.claims().get("scope");
                if (scopeClaim instanceof String value) {
                    granted.addAll(Arrays.asList(value.trim().split("\\s+")));
                }
                if (requiredScopes.stream().noneMatch(granted::contains)) {
                    return new Result(Failure.UNAUTHORIZED, null, null);
                }
            }
            String principalId = String.valueOf(verified.claims().getOrDefault("sub", "unknown"));
            Map<String, Object> claims = new LinkedHashMap<>(verified.claims());
            claims.replaceAll((key, value) -> value instanceof List<?> items
                    ? items.stream().map(String::valueOf).collect(Collectors.joining(",")) : value);
            return new Result(null, principalId, Map.of("claims", claims));
        } catch (AwsException e) {
            return new Result(Failure.UNAUTHORIZED, null, null);
        }
    }
}
