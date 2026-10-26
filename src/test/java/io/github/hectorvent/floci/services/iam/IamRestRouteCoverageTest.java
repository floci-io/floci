package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A REST request is authorized by its route, so an operation is checked as the IAM action AWS checks
 * for it only if a rule in {@link IamActionRegistry} maps its route to that action. This builds a
 * request on each operation's route, as botocore's model gives it ({@code rest-routes.json}, from
 * {@code tools/aws/regen_rest_routes.py}), and compares the action the registry resolves with the one
 * {@code iam-authorized-actions.json} names for the operation, or else the operation's own name.
 */
class IamRestRouteCoverageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LABEL = Pattern.compile("\\{([^}+]+)(\\+?)}");

    /** The REST namespaces whose entries in {@code iam-authorized-actions.json} only the route table can apply. */
    private static final Set<String> ROUTED_NAMESPACES = Set.of("s3", "apigateway", "lambda");

    /**
     * Operations Floci does not serve, whose request runs as another operation instead and so is checked
     * as that one: S3 has no bucket metadata configuration, and {@code ?metadataConfiguration} on a
     * bucket falls through to ListObjects and DeleteBucket. Checking it as the metadata table action
     * would let that action list or delete the bucket.
     */
    private static final Map<String, String> RUN_AS_ANOTHER_OPERATION = Map.of(
            "GetBucketMetadataConfiguration", "s3:ListBucket",
            "DeleteBucketMetadataConfiguration", "s3:DeleteBucket");

    /** Models every operation of which is checked: the services whose routes the registry maps in full. */
    private static final Set<String> FULLY_ROUTED_MODELS =
            Set.of("scheduler", "pipes", "eks", "lambda", "lambda-microvms", "lambda-core", "dsql");

    private final IamActionRegistry registry = new IamActionRegistry();

    @TestFactory
    Stream<DynamicTest> eachVendoredActionOfARestServiceIsWhatItsRouteResolvesTo() throws IOException {
        JsonNode models = load("/aws/rest-routes.json").path("models");
        JsonNode authorized = load("/aws/iam-authorized-actions.json").path("authorizedActions");
        List<DynamicTest> tests = new ArrayList<>();
        for (String namespace : ROUTED_NAMESPACES) {
            Iterator<Map.Entry<String, JsonNode>> entries = authorized.path(namespace).fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                List<Route> routes = routesOf(models, namespace, entry.getKey());
                String expected = RUN_AS_ANOTHER_OPERATION.getOrDefault(entry.getKey(), entry.getValue().asText());
                tests.add(DynamicTest.dynamicTest(namespace + " " + entry.getKey(), () -> {
                    assertFalse(routes.isEmpty(), "no model route for " + entry.getKey());
                    for (Route route : routes) {
                        assertEquals(expected, resolve(route), route.toString());
                    }
                }));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> everyOperationOfAFullyRoutedServiceResolvesToItsAction() throws IOException {
        JsonNode models = load("/aws/rest-routes.json").path("models");
        JsonNode authorized = load("/aws/iam-authorized-actions.json").path("authorizedActions");
        List<DynamicTest> tests = new ArrayList<>();
        for (String model : FULLY_ROUTED_MODELS) {
            String scope = models.path(model).path("credentialScope").asText();
            Iterator<Map.Entry<String, JsonNode>> operations = models.path(model).path("operations").fields();
            while (operations.hasNext()) {
                Map.Entry<String, JsonNode> operation = operations.next();
                Route route = new Route(scope, model, operation.getKey(), operation.getValue().path("method").asText(),
                        operation.getValue().path("requestUri").asText());
                String expected = RUN_AS_ANOTHER_OPERATION.getOrDefault(operation.getKey(),
                        authorized.path(scope).path(operation.getKey()).asText(scope + ":" + operation.getKey()));
                tests.add(DynamicTest.dynamicTest(model + " " + operation.getKey(),
                        () -> assertEquals(expected, resolve(route), route.toString())));
            }
        }
        return tests.stream();
    }

    /** Every model route of {@code operation} among the models Floci authorizes under {@code scope}. */
    private static List<Route> routesOf(JsonNode models, String scope, String operation) {
        List<Route> routes = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> all = models.fields();
        while (all.hasNext()) {
            Map.Entry<String, JsonNode> model = all.next();
            JsonNode route = model.getValue().path("operations").path(operation);
            if (scope.equals(model.getValue().path("credentialScope").asText()) && !route.isMissingNode()) {
                routes.add(new Route(scope, model.getKey(), operation, route.path("method").asText(),
                        route.path("requestUri").asText()));
            }
        }
        return routes;
    }

    private String resolve(Route route) {
        return registry.resolveRoute(route.scope(), route.request());
    }

    private static JsonNode load(String resource) throws IOException {
        try (InputStream in = IamRestRouteCoverageTest.class.getResourceAsStream(resource)) {
            return MAPPER.readTree(in);
        }
    }

    /** An operation's route: its method, and its request URI with each label filled in. */
    private record Route(String scope, String model, String operation, String method, String requestUri) {

        ContainerRequestContext request() {
            int question = requestUri.indexOf('?');
            String path = fill(question < 0 ? requestUri : requestUri.substring(0, question));
            MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
            if (question >= 0) {
                for (String parameter : requestUri.substring(question + 1).split("&")) {
                    int equals = parameter.indexOf('=');
                    query.add(equals < 0 ? parameter : parameter.substring(0, equals),
                            equals < 0 ? "" : parameter.substring(equals + 1));
                }
            }
            ContainerRequestContext ctx = mock(ContainerRequestContext.class);
            UriInfo uriInfo = mock(UriInfo.class);
            when(uriInfo.getQueryParameters()).thenReturn(query);
            when(uriInfo.getPath()).thenReturn(path);
            when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:4566" + path));
            when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:4566/"));
            when(ctx.getUriInfo()).thenReturn(uriInfo);
            when(ctx.getMethod()).thenReturn(method);
            return ctx;
        }

        /**
         * The path with each {@code {label}} filled in, and a greedy {@code {label+}} given two segments. A
         * {@code {policyArn}} gets a cluster access policy ARN, encoded as an SDK sends it, since only that
         * shape routes to the policy operations.
         */
        private static String fill(String template) {
            Matcher label = LABEL.matcher(template);
            StringBuilder path = new StringBuilder();
            while (label.find()) {
                String value = label.group(1).equals("policyArn")
                        ? "arn%3Aaws%3Aeks%3A%3Aaws%3Acluster-access-policy%2FAmazonEKSViewPolicy"
                        : label.group(1).toLowerCase() + "-value";
                label.appendReplacement(path, label.group(2).isEmpty() ? value : value + "/part");
            }
            label.appendTail(path);
            return path.toString();
        }

        @Override
        public String toString() {
            return model + " " + operation + ": " + method + " " + requestUri;
        }
    }
}
