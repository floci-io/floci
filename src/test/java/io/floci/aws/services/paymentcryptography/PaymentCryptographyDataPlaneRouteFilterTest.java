package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every route of the data plane is rewritten onto the internal prefix.
 *
 * <p>The data plane's routes are the ones the model names, and they sit under paths that S3's
 * path-style routes would claim. A request for one of them is rewritten before the routes
 * match, and a request for one whose prefix the filter does not name is not rewritten: it
 * never reaches its controller, and the request is answered by whichever controller claims it
 * instead. The prefixes are therefore held to the generated routes, so a route that is added
 * without its prefix is caught here rather than by a client.
 */
class PaymentCryptographyDataPlaneRouteFilterTest {

    /** The routes the generated table holds. */
    private static final String ROUTES = "aws/rest-routes.json";

    /** The model the data plane's routes come from. */
    private static final String MODEL = "payment-cryptography-data";

    @Test
    void everyRouteOfTheDataPlaneSitsUnderAPrefixTheFilterNames() {
        for (String prefix : prefixesOfTheRoutes()) {
            assertTrue(PaymentCryptographyDataPlaneRouteFilter.CONTESTED_PATHS.contains(prefix),
                    "the route prefix " + prefix + " is not one the route filter rewrites, so a "
                            + "request for it would never reach its controller");
        }
    }

    @Test
    void everyPrefixTheFilterNamesIsARouteOfTheDataPlane() {
        Set<String> prefixes = prefixesOfTheRoutes();
        assertTrue(!prefixes.isEmpty(), "the data plane has no routes");
        for (String prefix : PaymentCryptographyDataPlaneRouteFilter.CONTESTED_PATHS) {
            assertTrue(prefixes.contains(prefix),
                    "the route filter rewrites the prefix " + prefix + ", which no route of the "
                            + "data plane sits under");
        }
    }

    /**
     * The path prefixes the data plane's routes sit under.
     *
     * @return the prefixes, each with its leading slash
     */
    private static Set<String> prefixesOfTheRoutes() {
        JsonNode routes = read(ROUTES).path("models").path(MODEL).path("operations");
        Set<String> prefixes = new LinkedHashSet<>();
        routes.fields().forEachRemaining(entry -> {
            String uri = entry.getValue().path("requestUri").asText();
            // The route is the model's path: IAM matches a moved request on its path without the prefix.
            assertTrue(!uri.startsWith(PaymentCryptographyDataPlaneRouteFilter.INTERNAL_PREFIX),
                    "the route " + uri + " sits under the internal prefix, not at the model's path");
            assertTrue(uri.startsWith("/"), "the route " + uri + " names no path");
            String rest = uri.substring(1);
            int slash = rest.indexOf('/');
            assertTrue(slash > 0, "the route " + uri + " names no path");
            prefixes.add("/" + rest.substring(0, slash));
        });
        return prefixes;
    }

    /** The generated table, read from the classpath. */
    private static JsonNode read(String resource) {
        try (InputStream in = PaymentCryptographyDataPlaneRouteFilterTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "the generated table " + resource + " is not on the classpath");
            return new ObjectMapper().readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("the generated table " + resource + " could not be read", e);
        }
    }
}
