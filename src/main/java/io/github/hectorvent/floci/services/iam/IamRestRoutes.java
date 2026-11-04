package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.VendoredResources;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Runtime IAM routes read from the pinned botocore snapshot. */
final class IamRestRoutes {
    private static final String RESOURCE = "aws/rest-routes.json";
    // Enroll operations independently of their HTTP spelling or IAM namespace. Analyzer CRUD
    // needs resource-ARN resolution before it can join this stateless operation.
    private static final Map<String, Set<String>> ENROLLED_OPERATIONS =
            Map.of("accessanalyzer", Set.of("ValidatePolicy"));
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Route(String scope, String method, Pattern path, String action) {}

    private static final class Holder {
        static final IamRestRoutes TABLE = VendoredResources.read(RESOURCE, "IAM REST routes", IamRestRoutes::parse);
    }

    private final List<Route> routes;

    private IamRestRoutes(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }

    static String actionFor(String scope, String method, String path) {
        return Holder.TABLE.resolve(scope, method, path);
    }

    String resolve(String scope, String method, String path) {
        for (Route route : routes) {
            if (route.scope().equals(scope) && route.method().equals(method) && route.path().matcher(path).matches()) {
                return route.action();
            }
        }
        return null;
    }

    static IamRestRoutes parse(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        if (root == null || !root.path("models").isObject()) {
            throw new IOException("Missing REST route models");
        }
        List<Route> routes = new ArrayList<>();
        for (Map.Entry<String, Set<String>> enrolled : ENROLLED_OPERATIONS.entrySet()) {
            JsonNode model = root.path("models").path(enrolled.getKey());
            String scope = text(model, "credentialScope");
            for (String operation : enrolled.getValue()) {
                JsonNode http = model.path("operations").path(operation);
                String method = text(http, "method");
                String uri = text(http, "requestUri");
                if (!METHODS.contains(method) || !uri.startsWith("/") || uri.contains("{")
                        || uri.contains("}") || uri.contains("?") || uri.contains("#") || uri.contains(";")) {
                    throw new IOException("Enrolled REST operation must have a supported method and literal path: "
                            + enrolled.getKey() + ":" + operation);
                }
                routes.add(new Route(scope, method, literalPath(uri), IamAuthorizedActions.actionFor(scope, operation)));
            }
        }
        return new IamRestRoutes(routes);
    }

    private static Pattern literalPath(String uri) {
        StringBuilder pattern = new StringBuilder("^");
        for (String segment : uri.substring(1).split("/", -1)) {
            // RESTEasy can retain ignored matrix suffixes in the path it matched.
            pattern.append('/').append(Pattern.quote(segment)).append("(?:;[^/]*)?");
        }
        return Pattern.compile(pattern.append('$').toString());
    }

    private static String text(JsonNode node, String name) throws IOException {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IOException("Missing REST route field: " + name);
        }
        return value.textValue();
    }
}
