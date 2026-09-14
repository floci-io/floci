package io.github.hectorvent.floci.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Optional;

/**
 * The gateway response types of a REST API, with the status code AWS assigns each one when it
 * has not been customised. {@code DEFAULT_4XX} and {@code DEFAULT_5XX} carry no status of their
 * own: they are the fallback for every type of their class that has no customisation.
 */
@RegisterForReflection
public enum GatewayResponseType {
    ACCESS_DENIED(403),
    API_CONFIGURATION_ERROR(500),
    AUTHORIZER_CONFIGURATION_ERROR(500),
    AUTHORIZER_FAILURE(500),
    BAD_REQUEST_PARAMETERS(400),
    BAD_REQUEST_BODY(400),
    DEFAULT_4XX(null),
    DEFAULT_5XX(null),
    EXPIRED_TOKEN(403),
    INTEGRATION_FAILURE(504),
    INTEGRATION_TIMEOUT(504),
    INVALID_API_KEY(403),
    INVALID_SIGNATURE(403),
    MISSING_AUTHENTICATION_TOKEN(403),
    QUOTA_EXCEEDED(429),
    REQUEST_TOO_LARGE(413),
    RESOURCE_NOT_FOUND(404),
    THROTTLED(429),
    UNAUTHORIZED(401),
    UNSUPPORTED_MEDIA_TYPE(415),
    WAF_FILTERED(403);

    private final Integer defaultStatusCode;

    GatewayResponseType(Integer defaultStatusCode) {
        this.defaultStatusCode = defaultStatusCode;
    }

    /** The status AWS answers with when the type is not customised; null for the two DEFAULT_ types. */
    public Integer defaultStatusCode() {
        return defaultStatusCode;
    }

    public boolean isDefaultType() {
        return defaultStatusCode == null;
    }

    /** The DEFAULT_ type that stands in for this one when it has no customisation of its own. */
    public GatewayResponseType fallback(int statusCode) {
        int status = defaultStatusCode != null ? defaultStatusCode : statusCode;
        return status >= 500 ? DEFAULT_5XX : DEFAULT_4XX;
    }

    public static Optional<GatewayResponseType> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (GatewayResponseType type : values()) {
            if (type.name().equals(name)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
