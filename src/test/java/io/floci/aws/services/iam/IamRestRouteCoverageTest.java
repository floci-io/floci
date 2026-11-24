package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.testing.ParallelLocks;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
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
 * for it only if a rule in {@link IamActionRegistry} maps its route to that action, or, for an action AWS
 * authorizes on no resource type, the vendored route it matches is its own. This builds a request on each
 * operation's route, as botocore's model gives it ({@code rest-routes.json}, from
 * {@code tools/aws/regen_rest_routes.py}), and compares the action the registry resolves with the one
 * {@code iam-authorized-actions.json} names for the operation, or its route's method names for an operation it
 * authorizes by method, or else the operation's own name.
 */
@ResourceLock(ParallelLocks.PORTS)
class IamRestRouteCoverageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LABEL = Pattern.compile("\\{([^}+]+)(\\+?)}");

    /** The REST namespaces whose entries in {@code iam-authorized-actions.json} only the route table can apply. */
    private static final Set<String> ROUTED_NAMESPACES = Set.of("s3", "apigateway", "lambda");

    private final IamActionRegistry registry = new IamActionRegistry();

    @TestFactory
    Stream<DynamicTest> servedAccessAnalyzerOperationsUseTheirModeledRoutes() throws IOException {
        JsonNode model = load("/aws/rest-routes.json").path("models").path("accessanalyzer");
        assertFalse(model.isMissingNode(), "Access Analyzer's routes must be vendored from the AWS model");
        String scope = model.path("credentialScope").asText();
        List<DynamicTest> tests = new ArrayList<>();
        for (String operation : Set.of("ValidatePolicy", "ListAnalyzers", "CreateAnalyzer", "GetAnalyzer",
                "DeleteAnalyzer")) {
            JsonNode http = model.path("operations").path(operation);
            assertFalse(http.isMissingNode(), "no model route for " + operation);
            Route route = new Route(scope, "accessanalyzer", operation,
                    http.path("method").asText(), http.path("requestUri").asText());
            tests.add(DynamicTest.dynamicTest("accessanalyzer " + operation,
                    () -> assertEquals("access-analyzer:" + operation, resolve(route), route.toString())));
        }
        return tests.stream();
    }

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
                String expected = entry.getValue().asText();
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

    /**
     * Each action the vendored file gives an operation for one model's requests is what a request on that model's route
     * resolves to: S3's GetPublicAccessBlock is s3:GetBucketPublicAccessBlock, S3 Control's account-level one
     * s3:GetAccountPublicAccessBlock, and S3's GetBucketLifecycleConfiguration s3:GetLifecycleConfiguration.
     */
    @TestFactory
    Stream<DynamicTest> eachVendoredActionOfAModelsRequestsIsWhatItsRouteResolvesTo() throws IOException {
        JsonNode models = load("/aws/rest-routes.json").path("models");
        JsonNode byModel = load("/aws/iam-authorized-actions.json").path("authorizedByModel");
        List<DynamicTest> tests = new ArrayList<>();
        for (String namespace : ROUTED_NAMESPACES) {
            Iterator<Map.Entry<String, JsonNode>> clients = byModel.path(namespace).fields();
            while (clients.hasNext()) {
                Map.Entry<String, JsonNode> client = clients.next();
                Iterator<Map.Entry<String, JsonNode>> entries = client.getValue().fields();
                while (entries.hasNext()) {
                    Map.Entry<String, JsonNode> entry = entries.next();
                    List<Route> routes = routesOf(models, namespace, entry.getKey()).stream()
                            .filter(route -> route.model().equals(client.getKey())).toList();
                    String expected = entry.getValue().asText();
                    tests.add(DynamicTest.dynamicTest(client.getKey() + " " + entry.getKey(), () -> {
                        assertFalse(routes.isEmpty(), "no " + client.getKey() + " route for " + entry.getKey());
                        for (Route route : routes) {
                            assertEquals(expected, resolve(route), route.toString());
                        }
                    }));
                }
            }
        }
        return tests.stream();
    }

    /**
     * Every operation of a scope the vendored routes do not leave to the rules alone resolves to its action when
     * Floci can check it as AWS does: its resources read by the vendored request resources, or none, AWS authorizing
     * its action on no resource type. Any other operation stays unmapped, unchecked rather than checked on {@code *},
     * until the resources in its path are read. An operation the authorized actions list as authorized by method is
     * the action its route's method names, and an alternative route a shared controller also serves it on is checked
     * as the operation, by that route's own method.
     */
    @TestFactory
    Stream<DynamicTest> everyOperationOfARoutedScopeResolvesToItsActionWhenFlociCanCheckIt() throws IOException {
        JsonNode document = load("/aws/rest-routes.json");
        JsonNode models = document.path("models");
        List<DynamicTest> tests = new ArrayList<>();
        for (String model : routedModels(document)) {
            String scope = models.path(model).path("credentialScope").asText();
            Iterator<Map.Entry<String, JsonNode>> operations = models.path(model).path("operations").fields();
            while (operations.hasNext()) {
                Map.Entry<String, JsonNode> operation = operations.next();
                String method = operation.getValue().path("method").asText();
                List<Route> routes = new ArrayList<>(List.of(new Route(scope, model, operation.getKey(), method,
                        operation.getValue().path("requestUri").asText(), operation.getValue().path("requires"))));
                operation.getValue().path("alternatives").forEach(alternative -> routes.add(new Route(scope, model,
                        operation.getKey(), alternative.path("method").asText(method),
                        alternative.path("requestUri").asText())));
                // An unread operation names what it acts on in a body the reader does not read, so it is left to its
                // service, as one whose resources nothing reads is.
                boolean read = RequestResources.reads(scope + ":" + operation.getKey());
                for (Route route : routes) {
                    tests.add(DynamicTest.dynamicTest(route.toString(), () -> {
                        ContainerRequestContext request = route.request();
                        String action = IamAuthorizedActions.actionFor(scope, operation.getKey(), route.method(),
                                model, request.getUriInfo().getQueryParameters());
                        String expected = read || IamAuthorizedActions.actsOnNoResourceType(action) ? action : null;
                        assertEquals(expected, registry.resolveRoute(scope, request), route.toString());
                    }));
                }
            }
        }
        return tests.stream();
    }

    /**
     * Every route of a scope the vendored routes do not leave to the rules alone is, by those routes alone, an
     * operation checked as its own operation's action: the most specific route a request on it matches is its own,
     * or a sibling model's of the same scope and action (Lambda's and Lambda MicroVMs' tagging routes).
     */
    @TestFactory
    Stream<DynamicTest> everyRouteOfARoutedScopeIsItsOperationByTheVendoredRoutesAlone() throws IOException {
        JsonNode document = load("/aws/rest-routes.json");
        JsonNode models = document.path("models");
        List<DynamicTest> tests = new ArrayList<>();
        for (String model : routedModels(document)) {
            String scope = models.path(model).path("credentialScope").asText();
            Iterator<Map.Entry<String, JsonNode>> operations = models.path(model).path("operations").fields();
            while (operations.hasNext()) {
                Map.Entry<String, JsonNode> operation = operations.next();
                Route route = new Route(scope, model, operation.getKey(), operation.getValue().path("method").asText(),
                        operation.getValue().path("requestUri").asText(), operation.getValue().path("requires"));
                tests.add(DynamicTest.dynamicTest(model + " " + operation.getKey(), () -> {
                    ContainerRequestContext request = route.request();
                    MultivaluedMap<String, String> query = request.getUriInfo().getQueryParameters();
                    String matched = IamRestRoutes.vendored().match(scope, route.method(),
                                    request.getUriInfo().getPath(), query, false, request.getHeaders())
                            .map(match -> IamAuthorizedActions.actionFor(scope, match.operation(), route.method(),
                                    match.model(), query))
                            .orElse(null);
                    assertEquals(IamAuthorizedActions.actionFor(scope, operation.getKey(), route.method(), model, query),
                            matched, route.toString());
                    // No request an SDK sends on a route is one two operations' routes take alike, which is refused.
                    assertFalse(IamRestRoutes.vendored().ambiguous(scope, route.method(), request.getUriInfo().getPath(),
                            query, false, request.getHeaders()), route.toString());
                }));
            }
        }
        return tests.stream();
    }

    /** The models of every credential scope the vendored routes read. */
    private static List<String> routedModels(JsonNode document) {
        return document.path("models").propertyStream().map(Map.Entry::getKey).toList();
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
                        route.path("requestUri").asText(), route.path("requires")));
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

    /**
     * A route of a model's operation, and what botocore requires its request to carry where several operations share
     * it ({@code requires}), which the request built on it carries as an SDK sends it.
     */
    private record Route(String scope, String model, String operation, String method, String requestUri,
                         JsonNode requires) {

        Route(String scope, String model, String operation, String method, String requestUri) {
            this(scope, model, operation, method, requestUri, MAPPER.createObjectNode());
        }

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
            requires.path("query").forEach(parameter -> query.add(parameter.asText(), "required-value"));
            ContainerRequestContext ctx = mock(ContainerRequestContext.class);
            MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
            requires.path("headers").forEach(header -> {
                headers.add(header.asText(), "required-value");
                when(ctx.getHeaderString(header.asText())).thenReturn("required-value");
            });
            when(ctx.getHeaders()).thenReturn(headers);
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
