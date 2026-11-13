package io.floci.aws.services.iam;

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
import java.util.Map;
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

    @ParameterizedTest
    @ValueSource(strings = {"controltower", "signer"})
    void sharedTagAlternativesCarryMemberLocations(String scope) {
        IamRestRoutes vendored = IamRestRoutes.vendored();
        Map<String, String> operations = Map.of("POST", "TagResource", "GET", "ListTagsForResource",
                "DELETE", "UntagResource");
        operations.forEach((method, operation) -> {
            IamRestRoutes.Match bare = vendored.match(scope, method, "/tags/", null).orElseThrow();
            assertEquals(operation, bare.operation());
            assertEquals(Map.of(), bare.labels());
            assertEquals(Map.of("resourceArn", "POST".equals(method) ? "body" : "?resourceArn"), bare.located());
            IamRestRoutes.Match path = vendored.match(scope, method, "/tags/arn%3Aresource%2Fitem", null).orElseThrow();
            assertEquals(operation, path.operation());
            assertEquals(Map.of("resourceArn", "arn:resource/item"), path.labels());
            assertEquals(Map.of(), path.located());
        });
    }

    /**
     * API Gateway v1 tags with a PUT on {@code /tags/{resource_arn}}, a label spanning the slashes of an ARN sent
     * unencoded, and the shared tags controller's bare path tags with a POST naming the ARN in its body: that
     * alternative is matched by its own method, never the operation's.
     */
    @Test
    void anAlternativeIsMatchedByItsOwnMethod() {
        IamRestRoutes vendored = IamRestRoutes.vendored();
        IamRestRoutes.Match bare = vendored.match("apigateway", "POST", "/tags", null).orElseThrow();
        assertEquals("TagResource", bare.operation());
        assertEquals(Map.of("resourceArn", "body"), bare.located());
        assertEquals(Optional.empty(), vendored.operation("apigateway", "PUT", "/tags", null));
        assertEquals(Optional.of("TagResource"),
                vendored.operation("apigateway", "PUT", "/tags/arn:aws:apigateway:us-east-1::/restapis/abc", null));
        assertEquals(Optional.of("GetTags"), vendored.operation("apigateway", "GET", "/tags", null));
        assertEquals(Optional.of("TagResource"),
                vendored.operation("apigateway", "POST", "/v2/tags/arn:aws:apigateway:us-east-1::/apis/abc", null));

        IamRestRoutes own = routes(ROUTES.replace("\"requestUri\": \"/things/{ThingName}?tagging\"",
                "\"requestUri\": \"/things/{ThingName}?tagging\", \"alternatives\": [{\"method\": \"PUT\", "
                        + "\"requestUri\": \"/tagging\", \"located\": {\"ThingName\": \"body\"}}]"));
        assertEquals(Optional.of("TagThing"), own.operation("svc", "PUT", "/tagging", null));
        assertEquals(Optional.empty(), own.operation("svc", "POST", "/tagging", null));
    }

    @Test
    void anAlternativeOfNoKnownMethodIsRejected() {
        String alternative = ROUTES.replace("\"requestUri\": \"/things/\"",
                "\"requestUri\": \"/things/\", \"alternatives\": [{\"method\": \"FETCH\", \"requestUri\": \"/other\", "
                        + "\"located\": {\"member\": \"body\"}}]");
        assertThrows(IllegalStateException.class, () -> routes(alternative));
    }

    @Test
    void malformedAlternativeMemberSourceIsRejected() {
        String alternative = ROUTES.replace("\"requestUri\": \"/things/\"",
                "\"requestUri\": \"/things/\", \"alternatives\": [{\"requestUri\": \"/other\", "
                        + "\"located\": {\"member\": \"header\"}}]");
        assertThrows(IllegalStateException.class, () -> routes(alternative));
    }

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
    void denialStatusComesFromTheModelAndFallsBackWhenMissing() {
        IamRestRoutes modelled = routes(ROUTES.replace("\"credentialScope\": \"svc\"", "\"credentialScope\": \"svc\", \"accessDeniedStatus\": 402"));
        assertEquals(402, modelled.accessDeniedStatus("svc", 403));
        assertEquals(403, modelled.accessDeniedStatus("hand", 403));
        assertEquals(400, modelled.accessDeniedStatus("unknown", 400));
        assertEquals(403, modelled.accessDeniedStatus(null, 403));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"401\"", "401.5", "399", "600", "null", "{}"})
    void invalidModelledDenialStatusesAreRejected(String status) {
        String document = ROUTES.replace("\"credentialScope\": \"svc\"", "\"credentialScope\": \"svc\", \"accessDeniedStatus\": " + status);
        assertThrows(IllegalStateException.class, () -> routes(document));
    }

    @Test
    void modelsSharingAScopeMustAgreeOnTheDenialStatus() {
        String document = ROUTES.replace("\"credentialScope\": \"svc\"", "\"credentialScope\": \"svc\", \"accessDeniedStatus\": 401")
                .replace("\"credentialScope\": \"hand\"", "\"credentialScope\": \"svc\", \"accessDeniedStatus\": 403");
        assertThrows(IllegalStateException.class, () -> routes(document));
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

    /**
     * The values the path holds are read under the names the route gives its labels, so that a
     * service which names a resource in the path reads the resource from it. A label holding an
     * ARN spans the slashes the ARN holds, so the route names it with a {@code +}.
     */
    @Test
    void theValuesThePathHoldsAreReadUnderTheNamesItsLabelsCarry() {
        IamRestRoutes table = routes(ROUTES.replace("\"/things/{ThingName}\"", "\"/keys/{KeyIdentifier+}\"")
                .replace("\"GetThing\"", "\"GetKey\""));
        assertEquals(Map.of("KeyIdentifier", "arn:aws:service:region:account:key/abc"),
                table.match("svc", "GET", "/keys/arn:aws:service:region:account:key/abc", null)
                        .map(IamRestRoutes.Match::labels).orElse(Map.of()));
        assertEquals(Map.of(), table.match("svc", "GET", "/things/web", null)
                .map(IamRestRoutes.Match::labels).orElse(Map.of()));
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
     * A path a route filter moved under an internal prefix, given without it, was matched decoded, so a label's value
     * may hold the slashes its encoding hid: when the path matches no route with each label taking one segment, its
     * labels take the segments their values span. A route that matches with one segment a label still wins, and an
     * ordinary path's labels never span.
     */
    @Test
    void aPathMovedUnderAPrefixMayHoldTheSlashesItsLabelsWereDecodedTo() {
        assertEquals(Optional.of("GetThing"), routes.operation("svc", "GET", "/things/web/part", null, true));
        assertEquals(Optional.empty(), routes.operation("svc", "GET", "/things/web/part", null, false));
        assertEquals(Map.of("ThingName", "a/b", "PartPath", "c"),
                routes.match("svc", "GET", "/things/a/b/parts/c", null, true)
                        .map(IamRestRoutes.Match::labels).orElse(Map.of()));
        assertEquals(Optional.of("ListParts"), routes.operation("svc", "GET", "/things/web/parts/list", null, true));
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
