package io.github.hectorvent.floci.services.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON 1.1 handler for AWS Price List Service operations.
 * Dispatches {@code X-Amz-Target: AWSPriceListService.*} actions to {@link PricingService}.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_Operations_AWS_Price_List_Service.html">AWS Price List Service API</a>
 */
@ApplicationScoped
public class PricingJsonHandler {

    private static final Logger LOG = Logger.getLogger(PricingJsonHandler.class);

    private final PricingService service;

    @Inject
    public PricingJsonHandler(PricingService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Pricing action: {0}", action);
        return switch (action) {
            case "DescribeServices" -> handleDescribeServices(request);
            case "GetAttributeValues" -> handleGetAttributeValues(request);
            case "GetProducts" -> handleGetProducts(request);
            case "ListPriceLists" -> handleListPriceLists(request);
            case "GetPriceListFileUrl" -> handleGetPriceListFileUrl(request);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AWSPriceListService." + action))
                    .build();
        };
    }

    private Response handleDescribeServices(JsonNode request) {
        ObjectNode response = service.describeServices(
                stringOrNull(request, "ServiceCode"),
                stringOrNull(request, "FormatVersion"),
                stringOrNull(request, "NextToken"),
                integerOrNull(request, "MaxResults"));
        return Response.ok(response).build();
    }

    private Response handleGetAttributeValues(JsonNode request) {
        ObjectNode response = service.getAttributeValues(
                stringOrNull(request, "ServiceCode"),
                stringOrNull(request, "AttributeName"),
                stringOrNull(request, "NextToken"),
                integerOrNull(request, "MaxResults"));
        return Response.ok(response).build();
    }

    private Response handleGetProducts(JsonNode request) {
        ObjectNode response = service.getProducts(
                stringOrNull(request, "ServiceCode"),
                parseFilters(request.path("Filters")),
                stringOrNull(request, "FormatVersion"),
                stringOrNull(request, "NextToken"),
                integerOrNull(request, "MaxResults"));
        return Response.ok(response).build();
    }

    private Response handleListPriceLists(JsonNode request) {
        ObjectNode response = service.listPriceLists(
                stringOrNull(request, "ServiceCode"),
                timestampOrNull(request, "EffectiveDate"),
                stringOrNull(request, "RegionCode"),
                stringOrNull(request, "CurrencyCode"),
                stringOrNull(request, "NextToken"),
                integerOrNull(request, "MaxResults"));
        return Response.ok(response).build();
    }

    private Response handleGetPriceListFileUrl(JsonNode request) {
        ObjectNode response = service.getPriceListFileUrl(
                stringOrNull(request, "PriceListArn"),
                stringOrNull(request, "FileFormat"));
        return Response.ok(response).build();
    }

    private static List<PricingService.FilterSpec> parseFilters(JsonNode node) {
        List<PricingService.FilterSpec> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode entry : node) {
            String field = nonBlankOrNull(entry, "Field");
            String value = nonBlankOrNull(entry, "Value");
            if (field == null) {
                throw new AwsException("InvalidParameterException",
                        "Filter entries must include a non-empty 'Field'.", 400);
            }
            if (value == null) {
                throw new AwsException("InvalidParameterException",
                        "Filter entries must include a non-empty 'Value'.", 400);
            }
            out.add(new PricingService.FilterSpec(
                    entry.path("Type").asText("TERM_MATCH"),
                    field,
                    value));
        }
        return out;
    }

    private static String stringOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : null;
    }

    private static String nonBlankOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return (text == null || text.isEmpty()) ? null : text;
    }

    private static Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return (value != null && value.isNumber()) ? value.asInt() : null;
    }

    /**
     * Reads an AWS-style timestamp field. The JSON 1.1 wire format for AWS
     * Pricing encodes timestamps as Unix epoch seconds (JSON number); SDK and
     * CLI clients also sometimes send ISO-8601 strings. Both are accepted.
     */
    private static Instant timestampOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            // AWS encodes epoch seconds (may include fractional part).
            double seconds = value.asDouble();
            long wholeSeconds = (long) seconds;
            long nanos = (long) Math.round((seconds - wholeSeconds) * 1_000_000_000L);
            return Instant.ofEpochSecond(wholeSeconds, nanos);
        }
        String text = value.asText();
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e1) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e2) {
                throw new AwsException("ValidationException",
                        field + " must be an ISO-8601 timestamp or Unix epoch seconds.", 400);
            }
        }
    }
}
