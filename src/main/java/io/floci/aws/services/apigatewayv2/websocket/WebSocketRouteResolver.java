package io.floci.aws.services.apigatewayv2.websocket;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.apigatewayv2.ApiGatewayV2Service;
import io.floci.aws.services.apigatewayv2.model.Api;
import io.floci.aws.services.apigatewayv2.model.Route;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Resolves the target route for an incoming WebSocket message based on the API's
 * routeSelectionExpression and configured routes.
 *
 * Extracted from WebSocketHandler for independent testability and to keep the handler thin.
 */
@ApplicationScoped
public class WebSocketRouteResolver {

    private static final Logger LOG = Logger.getLogger(WebSocketRouteResolver.class);

    private final ApiGatewayV2Service apiGatewayV2Service;
    private final RouteSelectionEvaluator routeSelectionEvaluator;

    @Inject
    public WebSocketRouteResolver(ApiGatewayV2Service apiGatewayV2Service,
                                  RouteSelectionEvaluator routeSelectionEvaluator) {
        this.apiGatewayV2Service = apiGatewayV2Service;
        this.routeSelectionEvaluator = routeSelectionEvaluator;
    }

    /**
     * Result of route resolution.
     *
     * @param route           the matched route (null if no route found)
     * @param effectiveRouteKey the route key to use in the proxy event
     * @param errorMessage    error message to send to client if no route found (null if route was found)
     */
    public record RouteResolution(Route route, String effectiveRouteKey, String errorMessage) {

        public boolean hasRoute() {
            return route != null;
        }

        public static RouteResolution matched(Route route, String effectiveRouteKey) {
            return new RouteResolution(route, effectiveRouteKey, null);
        }

        public static RouteResolution noRoute(String errorMessage) {
            return new RouteResolution(null, null, errorMessage);
        }
    }

    /**
     * Resolve the target route for a message.
     *
     * @param region  the AWS region
     * @param apiId   the API ID
     * @param message the raw message body
     * @return RouteResolution with the matched route or an error message
     */
    public RouteResolution resolve(String region, String apiId, String message) {
        // Load the API to get routeSelectionExpression
        Api api;
        try {
            api = apiGatewayV2Service.getApi(region, apiId);
        } catch (AwsException e) {
            LOG.warnv("Failed to load API {0} for message routing: {1}", apiId, e.getMessage());
            return RouteResolution.noRoute("Internal server error");
        }

        String routeSelectionExpression = api.getRouteSelectionExpression();

        // Evaluate routeSelectionExpression against the message
        String routeKey = routeSelectionEvaluator.evaluate(routeSelectionExpression, message);

        // Look up matching route
        Route route = null;
        if (routeKey != null) {
            route = apiGatewayV2Service.findRouteByKey(region, apiId, routeKey);
        }

        // Fall back to $default if no match
        if (route == null) {
            route = apiGatewayV2Service.findRouteByKey(region, apiId, "$default");
        }

        // If no route found (no match and no $default)
        if (route == null) {
            if (routeKey == null) {
                return RouteResolution.noRoute("Could not route message");
            } else {
                return RouteResolution.noRoute("No route found");
            }
        }

        // Determine the effective route key for the proxy event
        String effectiveRouteKey = routeKey != null ? routeKey : "$default";
        return RouteResolution.matched(route, effectiveRouteKey);
    }
}
