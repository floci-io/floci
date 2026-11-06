package io.floci.aws.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Evaluates the API's routeSelectionExpression against a message payload
 * to determine which route to invoke.
 *
 * Supports the $request.body.{fieldName} expression format, extracting
 * the named top-level JSON field from the message body.
 */
@ApplicationScoped
public class RouteSelectionEvaluator {

    private static final Logger LOG = Logger.getLogger(RouteSelectionEvaluator.class);
    private static final String EXPRESSION_PREFIX = "$request.body.";

    @Inject
    ObjectMapper objectMapper;

    /**
     * Extracts the route key from a message using the route selection expression.
     *
     * @param routeSelectionExpression the expression (e.g. "$request.body.action")
     * @param messageBody the raw message body (expected to be JSON)
     * @return the extracted route key, or null if extraction fails
     */
    public String evaluate(String routeSelectionExpression, String messageBody) {
        if (routeSelectionExpression == null || messageBody == null) {
            return null;
        }

        String fieldName = parseFieldName(routeSelectionExpression);
        if (fieldName == null) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(messageBody);
            JsonNode fieldNode = root.get(fieldName);
            if (fieldNode == null || fieldNode.isMissingNode()) {
                return null;
            }

            if (fieldNode.isTextual()) {
                return fieldNode.asText();
            }

            // For non-string values (number, boolean, object, array), convert to string
            return fieldNode.asText();
        } catch (Exception e) {
            LOG.debugv("Failed to parse message body as JSON: {0}", e.getMessage());
            return null;
        }
    }

    /**
     * Parses a $request.body.{fieldName} expression and returns the field name.
     *
     * @param expression the route selection expression
     * @return the field name, or null if the expression format is not recognized
     */
    String parseFieldName(String expression) {
        if (expression == null || !expression.startsWith(EXPRESSION_PREFIX)) {
            return null;
        }

        String fieldName = expression.substring(EXPRESSION_PREFIX.length());
        if (fieldName.isEmpty()) {
            return null;
        }

        return fieldName;
    }
}
