package io.github.hectorvent.floci.services.apigatewayv2;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/** End-to-end: HTTP API HTTP_PROXY over a VPC link, integrationUri = Cloud Map service ARN. */
@QuarkusTest
class ApiGatewayV2CloudMapIntegrationTest {

    private static final String CLOUD_MAP_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String CLOUD_MAP_TARGET_PREFIX = "Route53AutoNaming_v20170314.";
    private static final int REQUESTS_PER_SPREAD_CHECK = 40;

    private static final String REGION = "us-east-1";

    private static String namespaceId;

    @Inject
    ApiGatewayV2Service apiGatewayV2Service;

    private final List<Stub> stubs = new ArrayList<>();
    private final List<String> apiIds = new ArrayList<>();
    private final List<String> vpcLinkIds = new ArrayList<>();

    private record Received(String method, String path, String query, String body, String header) {}

    /** An in-test backend on 127.0.0.1 that answers with its own name and records what it received. */
    private static final class Stub {
        final String name;
        final HttpServer server;
        final List<Received> received = new CopyOnWriteArrayList<>();
        final List<String> rawRequestTargets = new CopyOnWriteArrayList<>();

        Stub(String name) throws IOException {
            this.name = name;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                rawRequestTargets.add(exchange.getRequestURI().getRawPath());
                received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestURI().getRawQuery(), body,
                        exchange.getRequestHeaders().getFirst("X-Probe")));
                byte[] reply = ("{\"stub\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply);
                exchange.close();
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }
    }

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static synchronized String namespaceId() {
        if (namespaceId == null) {
            String name = "apigw-" + shortId();
            cloudMap("CreateHttpNamespace", "{\"Name\":\"" + name + "\"}").then().statusCode(200);
            namespaceId = cloudMap("ListNamespaces", "{}").then().statusCode(200)
                    .extract().jsonPath().getString("Namespaces.find { it.Name == '" + name + "' }.Id");
        }
        return namespaceId;
    }

    @AfterEach
    void cleanup() {
        stubs.forEach(stub -> stub.server.stop(0));
        stubs.clear();
        apiIds.forEach(id -> given().when().delete("/v2/apis/" + id));
        apiIds.clear();
        vpcLinkIds.forEach(id -> given().when().delete("/v2/vpclinks/" + id));
        vpcLinkIds.clear();
    }

    @Test
    void requestReachesRegisteredInstanceWithMethodPathQueryBodyAndHeaders() throws IOException {
        Stub stub = startStub("only");
        String serviceId = createService(null);
        register(serviceId, "i-1", stub, "");
        String apiId = createApi(serviceArn(serviceId), "VPC_LINK", createVpcLink());

        given().header("X-Probe", "probe-value").contentType(ContentType.TEXT).body("hello")
                .when().post("/execute-api/" + apiId + "/$default/orders?tag=a&tag=b")
                .then().statusCode(200).body("stub", equalTo("only"));

        assertThat(stub.received, hasSize(1));
        assertThat(stub.received.get(0), equalTo(
                new Received("POST", "/orders", "tag=a&tag=b", "hello", "probe-value")));
    }

    @Test
    void encodedPathAndTrailingSlashReachTheInstanceAsTheCallerSentThem() throws IOException {
        Stub stub = startStub("raw");
        String serviceId = createService(null);
        register(serviceId, "i-1", stub, "");
        String apiId = createApi(serviceArn(serviceId), "VPC_LINK", createVpcLink());

        given().urlEncodingEnabled(false).when().get("/execute-api/" + apiId + "/$default/a%3Fb")
                .then().statusCode(200);
        given().urlEncodingEnabled(false).when().get("/execute-api/" + apiId + "/$default/x/")
                .then().statusCode(200);
        given().urlEncodingEnabled(false).when().get("/execute-api/" + apiId + "/$default/a%20b?k=v")
                .then().statusCode(200);

        assertThat(stub.rawRequestTargets, contains("/a%3Fb", "/x/", "/a%20b"));
        assertThat(stub.received.get(0).query(), nullValue());
        assertThat(stub.received.get(2).query(), equalTo("k=v"));
    }

    @Test
    void namedStageStaysInThePathTheInstanceReceives() throws IOException {
        Stub stub = startStub("staged");
        String serviceId = createService(null);
        register(serviceId, "i-1", stub, "");
        String apiId = createApi(serviceArn(serviceId), "VPC_LINK", createVpcLink());

        given().urlEncodingEnabled(false).when().get("/execute-api/" + apiId + "/prod/a%3Fb")
                .then().statusCode(200);
        given().urlEncodingEnabled(false).when().get("/execute-api/" + apiId + "/prod/x/")
                .then().statusCode(200);
        given().urlEncodingEnabled(false).when().get("/execute-api/" + apiId + "/prod/a%20b?k=v")
                .then().statusCode(200);

        assertThat(stub.rawRequestTargets, contains("/prod/a%3Fb", "/prod/x/", "/prod/a%20b"));
        assertThat(stub.received.get(2).query(), equalTo("k=v"));
    }

    @Test
    void defaultStageIsNotInThePathTheInstanceReceives() throws IOException {
        Stub stub = startStub("unstaged");
        String serviceId = createService(null);
        register(serviceId, "i-1", stub, "");
        String apiId = createApi(serviceArn(serviceId), "VPC_LINK", createVpcLink());

        given().when().get("/execute-api/" + apiId + "/$default/orders").then().statusCode(200);

        assertThat(stub.rawRequestTargets, contains("/orders"));
    }

    @Test
    void serviceWithoutInstancesAnswers503() {
        String serviceId = createService(null);
        String apiId = createApi(serviceArn(serviceId), "VPC_LINK", createVpcLink());

        given().when().get("/execute-api/" + apiId + "/$default/anything")
                .then().statusCode(503).body("message", equalTo("Service Unavailable"));
    }

    @Test
    void serviceOfOnlyUnroutableInstancesAnswers503() {
        String serviceId = createService(null);
        cloudMap("RegisterInstance", "{\"ServiceId\":\"" + serviceId + "\",\"InstanceId\":\"no-port\","
                + "\"Attributes\":{\"AWS_INSTANCE_IPV4\":\"127.0.0.1\"}}").then().statusCode(200);
        String apiId = createApi(serviceArn(serviceId), "VPC_LINK", createVpcLink());

        given().when().get("/execute-api/" + apiId + "/$default/anything").then().statusCode(503);
    }

    @Test
    void requestsSpreadOverEveryRegisteredHealthyInstance() throws IOException {
        Stub first = startStub("first");
        Stub second = startStub("second");
        String serviceId = createService("{\"FailureThreshold\":1}");
        register(serviceId, "i-1", first, "");
        register(serviceId, "i-2", second, "");
        String apiId = createApi(serviceArn(serviceId), "VPC_LINK", createVpcLink());

        List<String> answers = new ArrayList<>();
        for (int i = 0; i < REQUESTS_PER_SPREAD_CHECK; i++) {
            answers.add(given().when().get("/execute-api/" + apiId + "/$default/ping")
                    .then().statusCode(200).extract().path("stub"));
        }

        assertThat(first.received.size() + second.received.size(), equalTo(REQUESTS_PER_SPREAD_CHECK));
        assertThat(answers.stream().distinct().toList(), containsInAnyOrder("first", "second"));
    }

    @Test
    void arnQueryStringSelectsInstancesByAttribute() throws IOException {
        Stub prod = startStub("prod");
        Stub dev = startStub("dev");
        String serviceId = createService(null);
        register(serviceId, "i-prod", prod, ",\"stage\":\"prod\"");
        register(serviceId, "i-dev", dev, ",\"stage\":\"dev\"");
        String apiId = createApi(serviceArn(serviceId) + "?stage=prod", "VPC_LINK", createVpcLink());

        for (int i = 0; i < 10; i++) {
            given().when().get("/execute-api/" + apiId + "/$default/ping")
                    .then().statusCode(200).body("stub", equalTo("prod"));
        }
        assertThat(dev.received, hasSize(0));
    }

    @Test
    void unknownCloudMapServiceAnswers502() {
        String missingArn = "arn:aws:servicediscovery:us-east-1:000000000000:service/srv-doesnotexist";
        String apiId = createApi(missingArn, "VPC_LINK", createVpcLink());

        given().when().get("/execute-api/" + apiId + "/$default/anything")
                .then().statusCode(502)
                .body("message", equalTo("Bad Gateway: cannot resolve Cloud Map service: " + missingArn));
    }

    @Test
    void unknownVpcLinkAnswers502BeforeAnyInstanceIsChosen() throws IOException {
        Stub stub = startStub("untouched");
        String serviceId = createService(null);
        register(serviceId, "i-1", stub, "");
        String apiId = createApi(serviceArn(serviceId), "VPC_LINK", "vpclink-missing");

        given().when().get("/execute-api/" + apiId + "/$default/anything")
                .then().statusCode(502)
                .body("message", equalTo("Invalid VPC link identifier specified: vpclink-missing"));
        assertThat(stub.received, hasSize(0));
    }

    @Test
    void vpcLinkIntegrationWithoutConnectionIdAnswers500() {
        String serviceId = createService(null);
        String apiId = createApi(serviceArn(serviceId), "VPC_LINK", null);

        given().when().get("/execute-api/" + apiId + "/$default/anything")
                .then().statusCode(500)
                .body("message", equalTo("VPC_LINK integration is missing a connectionId"));
    }

    @Test
    void vpcLinkNotAvailableAnswers502BeforeAnyInstanceIsChosen() throws IOException {
        Stub stub = startStub("untouched");
        String serviceId = createService(null);
        register(serviceId, "i-1", stub, "");
        String vpcLinkId = createVpcLink();
        apiGatewayV2Service.getVpcLink(REGION, vpcLinkId).setVpcLinkStatus("PENDING");
        String apiId = createApi(serviceArn(serviceId), "VPC_LINK", vpcLinkId);

        given().when().get("/execute-api/" + apiId + "/$default/anything")
                .then().statusCode(502).body("message", equalTo("VPC link is not available: PENDING"));
        assertThat(stub.received, hasSize(0));
    }

    @Test
    void plainUrlOverUnknownVpcLinkStillProxies() throws IOException {
        Stub stub = startStub("plain");
        String apiId = createApi("http://127.0.0.1:" + stub.port() + "/x", "VPC_LINK", "vpclink-missing");

        given().when().get("/execute-api/" + apiId + "/$default/anything")
                .then().statusCode(200).body("stub", equalTo("plain"));
    }

    @Test
    void cloudMapArnOverInternetConnectionIsNotResolvedThroughCloudMap() throws IOException {
        Stub stub = startStub("untouched");
        String serviceId = createService(null);
        register(serviceId, "i-1", stub, "");
        String apiId = createApi(serviceArn(serviceId), "INTERNET", null);

        given().when().get("/execute-api/" + apiId + "/$default/anything")
                .then().statusCode(502);
        assertThat(stub.received, hasSize(0));
    }

    @Test
    void cloudMapArnWithoutConnectionTypeIsNotResolvedThroughCloudMap() throws IOException {
        Stub stub = startStub("untouched");
        String serviceId = createService(null);
        register(serviceId, "i-1", stub, "");
        String apiId = createApi(serviceArn(serviceId), null, null);

        given().when().get("/execute-api/" + apiId + "/$default/anything")
                .then().statusCode(502);
        assertThat(stub.received, hasSize(0));
    }

    @Test
    void plainUrlWithoutVpcLinkStillProxies() throws IOException {
        Stub stub = startStub("plain");
        String apiId = createApi("http://127.0.0.1:" + stub.port() + "/x", null, null);

        given().when().get("/execute-api/" + apiId + "/$default/anything")
                .then().statusCode(200).body("stub", equalTo("plain"));
    }

    private Stub startStub(String name) throws IOException {
        Stub stub = new Stub(name);
        stubs.add(stub);
        return stub;
    }

    private static Response cloudMap(String action, String body) {
        return given().contentType(CLOUD_MAP_CONTENT_TYPE)
                .header("X-Amz-Target", CLOUD_MAP_TARGET_PREFIX + action)
                .body(body).when().post("/");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String createService(String healthCheckCustomConfig) {
        String extra = healthCheckCustomConfig == null ? ""
                : ",\"HealthCheckCustomConfig\":" + healthCheckCustomConfig;
        return cloudMap("CreateService", "{\"Name\":\"svc-" + shortId() + "\",\"NamespaceId\":\""
                + namespaceId() + "\"" + extra + "}")
                .then().statusCode(200).body("Service.Id", startsWith("srv-"))
                .extract().jsonPath().getString("Service.Id");
    }

    private static String serviceArn(String serviceId) {
        return cloudMap("GetService", "{\"Id\":\"" + serviceId + "\"}")
                .then().statusCode(200).extract().jsonPath().getString("Service.Arn");
    }

    private static void register(String serviceId, String instanceId, Stub stub, String extraAttributes) {
        cloudMap("RegisterInstance", "{\"ServiceId\":\"" + serviceId + "\",\"InstanceId\":\"" + instanceId
                + "\",\"Attributes\":{\"AWS_INSTANCE_IPV4\":\"127.0.0.1\",\"AWS_INSTANCE_PORT\":\""
                + stub.port() + "\"" + extraAttributes + "}}").then().statusCode(200);
    }

    private String createVpcLink() {
        String id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"cm-link\",\"subnetIds\":[\"subnet-1\"],\"securityGroupIds\":[\"sg-1\"]}")
                .when().post("/v2/vpclinks").then().statusCode(201).extract().path("vpcLinkId");
        vpcLinkIds.add(id);
        return id;
    }

    private String createApi(String integrationUri, String connectionType, String connectionId) {
        String apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"cm-api\",\"protocolType\":\"HTTP\"}")
                .when().post("/v2/apis").then().statusCode(201).extract().path("apiId");
        apiIds.add(apiId);

        String connection = (connectionType == null ? "" : ",\"connectionType\":\"" + connectionType + "\"")
                + (connectionId == null ? "" : ",\"connectionId\":\"" + connectionId + "\"");
        String integrationId = given().contentType(ContentType.JSON)
                .body("{\"integrationType\":\"HTTP_PROXY\",\"integrationUri\":\"" + integrationUri
                        + "\",\"payloadFormatVersion\":\"1.0\",\"integrationMethod\":\"ANY\"" + connection + "}")
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201).extract().path("integrationId");

        given().contentType(ContentType.JSON)
                .body("{\"routeKey\":\"ANY /{proxy+}\",\"target\":\"integrations/" + integrationId + "\"}")
                .when().post("/v2/apis/" + apiId + "/routes").then().statusCode(201);
        return apiId;
    }
}
