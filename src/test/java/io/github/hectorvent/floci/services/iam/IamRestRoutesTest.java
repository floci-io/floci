package io.github.hectorvent.floci.services.iam;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IamRestRoutesTest {

    private static final String ROUTES = """
            {"handRouted": {"hand": "the rules route it alone"},
             "models": {
               "svc": {"credentialScope": "svc", "operations": {
                 "ListThings": {"method": "GET", "requestUri": "/things/"},
                 "GetThing": {"method": "GET", "requestUri": "/things/{ThingName}"},
                 "GetThingPolicy": {"method": "GET", "requestUri": "/things/policy"},
                 "GetThingVersion": {"method": "GET", "requestUri": "/things/{ThingName}:{Version}"},
                 "GetPart": {"method": "GET", "requestUri": "/things/{ThingName}/parts/{PartPath+}"},
                 "ListParts": {"method": "GET", "requestUri": "/things/{ThingName}/parts/list"},
                 "UpdateThing": {"method": "POST", "requestUri": "/things/{ThingName}"},
                 "TagThing": {"method": "POST", "requestUri": "/things/{ThingName}?tagging"},
                 "ListVersions": {"method": "GET", "requestUri": "/versions"},
                 "ListVersionsV2": {"method": "GET", "requestUri": "/versions?list-type=2"},
                 "ValidateThing": {"method": "GET", "requestUri": "/from.data/validation"},
                 "GetRoot": {"method": "GET", "requestUri": "/"}
               }},
               "hand": {"credentialScope": "hand", "operations": {
                 "Ping": {"method": "GET", "requestUri": "/ping"}
               }}
             }}""";

    private final IamRestRoutes routes = routes(ROUTES);

    @Test
    void routeDataIsBundledWithTheApplicationRatherThanOnlyWithTests() throws Exception {
        URL application = IamActionRegistry.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader resources = new URLClassLoader(new URL[]{application}, null);
                InputStream data = resources.getResourceAsStream("aws/rest-routes.json")) {
            assertNotNull(data, "Runtime IAM authorization needs the generated routes in the application artifact");
        }
    }

    @Test
    void aLiteralSegmentOutranksALabel() {
        assertEquals(Optional.of("GetThingPolicy"), get("/things/policy"));
        assertEquals(Optional.of("GetThing"), get("/things/web"));
    }

    @Test
    void aSegmentMixingALiteralAndALabelOutranksALabel() {
        assertEquals(Optional.of("GetThingVersion"), get("/things/web:3"));
    }

    /** A greedy label takes the rest of the path, one segment or more, and ranks below a literal there. */
    @Test
    void aGreedyLabelTakesTheRestOfThePath() {
        assertEquals(Optional.of("GetPart"), get("/things/web/parts/a/b/c"));
        assertEquals(Optional.of("GetPart"), get("/things/web/parts/a"));
        assertEquals(Optional.of("ListParts"), get("/things/web/parts/list"));
        assertEquals(Optional.empty(), get("/things/web/parts"));
    }

    /** A query the route names must be on the request, with the value it gives, if any. */
    @Test
    void aQueryTheRouteNamesMustBeOnTheRequest() {
        assertEquals(Optional.of("TagThing"), routes.operation("svc", "POST", "/things/web", query("tagging", "")));
        assertEquals(Optional.of("UpdateThing"), routes.operation("svc", "POST", "/things/web", query("other", "")));
        assertEquals(Optional.of("UpdateThing"), routes.operation("svc", "POST", "/things/web", null));
        assertEquals(Optional.of("ListVersionsV2"), routes.operation("svc", "GET", "/versions",
                query("list-type", "2")));
        assertEquals(Optional.of("ListVersions"), routes.operation("svc", "GET", "/versions",
                query("list-type", "1")));
    }

    /** A trailing slash is dropped, as the router drops it, and an encoded slash stays inside its label. */
    @Test
    void aPathIsReadAsTheRouterMatchedIt() {
        assertEquals(Optional.of("ListThings"), get("/things"));
        assertEquals(Optional.of("ListThings"), get("/things/"));
        assertEquals(Optional.of("GetThing"), get("/things/web%2Fpart"));
        assertEquals(Optional.of("GetRoot"), get("/"));
    }

    /**
     * A literal segment matches itself alone, a dot in it no other character, and may end in a matrix parameter,
     * which RESTEasy keeps in the path it matched.
     */
    @Test
    void aLiteralSegmentKeepsItsMatrixParameters() {
        assertEquals(Optional.of("ValidateThing"), get("/from.data/validation"));
        assertEquals(Optional.of("ValidateThing"), get("/from.data;ignored=yes/validation;ignored=yes"));
        assertEquals(Optional.empty(), get("/fromXdata/validation"));
        assertEquals(Optional.empty(), get("/from.data%3Bignored/validation"));
    }

    @Test
    void aRequestOfAnotherMethodOrScopeOrOfAHandRoutedScopeMatchesNothing() {
        assertEquals(Optional.empty(), routes.operation("svc", "DELETE", "/things/web", null));
        assertEquals(Optional.empty(), routes.operation("other", "GET", "/things/web", null));
        assertEquals(Optional.empty(), routes.operation("hand", "GET", "/ping", null));
        assertEquals(Optional.empty(), get("/nothing"));
    }

    /** The vendored routes load, a model routed under the scope it signs with, and S3 is left to its rules. */
    @Test
    void theVendoredRoutesAreRead() {
        assertEquals(Optional.of("GetFunction"),
                IamRestRoutes.vendored().operation("lambda", "GET", "/2015-03-31/functions/fn", null));
        assertEquals(Optional.of("ListNetworkConnectors"),
                IamRestRoutes.vendored().operation("lambda", "GET", "/2026-04-04/network-connectors", null));
        assertEquals(Optional.of("DescribeDomain"),
                IamRestRoutes.vendored().operation("es", "GET", "/2021-01-01/opensearch/domain/logs", null));
        assertEquals(Optional.of("DescribeElasticsearchDomain"),
                IamRestRoutes.vendored().operation("es", "GET", "/2015-01-01/es/domain/logs", null));
        assertEquals(Optional.empty(), IamRestRoutes.vendored().operation("s3", "GET", "/bucket", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"models\":[]}", "{\"handRouted\": {}, \"models\":{}}"})
    void missingRouteDataFailsRatherThanProvidingAnEmptyAuthorizationTable(String document) {
        assertThrows(IllegalStateException.class, () -> routes(document));
    }

    @Test
    void aMalformedFileFailsToLoad() {
        for (String bad : List.of("{\"models\": {\"svc\": {\"credentialScope\": \"svc\", \"operations\": {}}}}",
                "{\"handRouted\": {}, \"models\": {\"svc\": {\"credentialScope\": \"svc\", \"operations\": {}}}}",
                "{\"handRouted\": {}, \"models\": {\"svc\": {\"operations\": {}}}}",
                "{\"handRouted\": {}, \"models\": {\"svc\": {\"credentialScope\": \"svc\"}}}",
                "{\"handRouted\": {}, \"models\": {\"svc\": {\"credentialScope\": \"svc\", \"operations\": "
                        + "{\"Op\": {\"requestUri\": \"/op\"}}}}}",
                "{\"handRouted\": {}, \"models\": {\"svc\": {\"credentialScope\": \"svc\", \"operations\": "
                        + "{\"Op\": {\"method\": \"INVALID\", \"requestUri\": \"/op\"}}}}}",
                "{\"handRouted\": {}, \"models\": {\"svc\": {\"credentialScope\": \"svc\", \"operations\": "
                        + "{\"Op\": {\"method\": \"GET\", \"requestUri\": \"op\"}}}}}")) {
            assertThrows(IllegalStateException.class, () -> routes(bad), bad);
        }
    }

    private Optional<String> get(String path) {
        return routes.operation("svc", "GET", path, null);
    }

    private static MultivaluedMap<String, String> query(String name, String value) {
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add(name, value);
        return query;
    }

    private static IamRestRoutes routes(String json) {
        try {
            return IamRestRoutes.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
