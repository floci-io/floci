package io.github.hectorvent.floci.services.lambdaweb;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebEnvironmentManager.Environment;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requests to an endpoint domain reach a Node.js server that Floci starts from the revision's S3
 * code, with the headers AWS adds and drops, the recorded error pages and a fresh environment after
 * a crash.
 */
@QuarkusTest
class LambdaWebDataPlaneDockerIntegrationTest {

    private static final String BASE = "/2025-03-07";
    private static final String BUCKET = "web-code-docker";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";
    private static final String REQUEST_ID = "[A-Z2-7]{26}";
    private static final String APP = """
            const http = require('node:http');
            const [host, port] = process.env.AWS_LAMBDA_HTTP_ENDPOINT.split(':');
            console.log('starting');
            if (process.env.MODE === 'throw') { throw new Error('init boom'); }
            if (process.env.MODE === 'exit') { process.exit(1); }
            if (process.env.MODE === 'missing') { require('no-such-module'); }
            http.createServer((req, res) => {
              if (req.url.startsWith('/log')) {
                console.info({route: 'log', main: require.main === module, argv: process.argv.slice(1)});
                console.debug('below the level');
                console.error(new Error('logged error'));
                process.stdout.write('raw line\\n');
                process.stdout.write('   \\n');
                process.stdout.write('last line\\n');
                res.end('logged');
                return;
              }
              if (req.url.startsWith('/slow')) { setTimeout(() => res.end('late'), 8000); return; }
              if (req.url.startsWith('/wait')) { setTimeout(() => res.end('waited'), 3000); return; }
              if (req.url.startsWith('/stream')) {
                res.writeHead(200, {'content-type': 'text/plain'});
                let i = 0;
                const timer = setInterval(() => {
                  res.write('chunk ' + (i++) + '\\n');
                  if (i >= 10) { clearInterval(timer); res.end('done\\n'); }
                }, 500);
                return;
              }
              if (req.url.startsWith('/status/')) {
                res.writeHead(Number(req.url.split('/')[2]), {'x-app': 'yes'}); res.end('status body'); return;
              }
              if (req.url.startsWith('/metadata')) {
                const url = 'http://' + process.env.AWS_LAMBDA_METADATA_API + '/2026-01-15/metadata/execution-environment';
                const auth = {headers: {authorization: 'Bearer ' + process.env.AWS_LAMBDA_METADATA_TOKEN}};
                Promise.all([fetch(url, auth), fetch(url)]).then(async ([ok, refused]) => {
                  res.end(JSON.stringify({status: ok.status, body: await ok.text(), refused: refused.status,
                    api: process.env.AWS_LAMBDA_METADATA_API, runtimeApi: process.env.AWS_LAMBDA_RUNTIME_API,
                    token: process.env.AWS_LAMBDA_METADATA_TOKEN}));
                }, (e) => { res.statusCode = 500; res.end(String(e)); });
                return;
              }
              if (req.url.startsWith('/crash')) { setTimeout(() => process.exit(1), 50); return; }
              if (req.url.startsWith('/exit')) { res.end('bye', () => process.exit(0)); return; }
              let body = '';
              req.on('data', (chunk) => { body += chunk; });
              req.on('end', () => {
                res.writeHead(200, {'content-type': 'application/json', 'connection': 'keep-alive', 'trailer': 'x-t'});
                res.end(JSON.stringify({method: req.method, url: req.url, headers: req.headers, bodyLength: body.length,
                  env: {endpoint: process.env.AWS_LAMBDA_HTTP_ENDPOINT, init: process.env.AWS_LAMBDA_INITIALIZATION_TYPE,
                        exec: process.env.AWS_EXECUTION_ENV, account: process.env.AWS_ACCOUNT_ID,
                        region: process.env.AWS_REGION, custom: process.env.CUSTOM},
                  execArgv: process.execArgv, heapSizeLimit: require('node:v8').getHeapStatistics().heap_size_limit}));
              });
            }).listen(Number(port), host);
            """;

    @Inject
    DockerClient dockerClient;

    @Inject
    S3Service s3Service;

    @Inject
    IamService iamService;

    @Inject
    LambdaWebEnvironmentManager environments;

    @Inject
    LambdaWebMetrics metrics;

    @Inject
    CloudFrontService cloudFrontService;

    private String functionName;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker must be available for the Lambda Web data plane");
    }

    @AfterEach
    void stopEnvironments() {
        environments.stopManagedContainers();
        if (functionName != null) {
            given().when().delete(BASE + "/web-functions/" + functionName);
        }
    }

    @Test
    @Timeout(180)
    void requestsReachTheServerWithTheHeadersAwsAddsAndDrops() throws Exception {
        String domain = createFunction(30);

        Response echo = given().header("Host", domain)
                .header("x-real-ip", "1.2.3.4")
                .header("x-http-method-override", "PUT")
                .header("lambda-web-request-id", "fake")
                .header("x-forwarded-for", "9.9.9.9")
                .header("x-custom", "1")
                .when().get("/a/b?x=1");
        echo.then().statusCode(200)
                .header("content-type", "application/json")
                .header("lambda-web-request-id", matchesPattern(REQUEST_ID))
                .header("lambda-web-logstream", matchesPattern("\\d{4}/\\d{2}/\\d{2}/" + functionName + "\\[rev-[a-z0-9]{5}\\][0-9a-f]{32}"))
                .header("trailer", nullValue())
                .body("method", equalTo("GET"))
                .body("url", equalTo("/a/b?x=1"))
                .body("headers.host", equalTo(domain))
                .body("headers.x-custom", equalTo("1"))
                .body("headers.x-real-ip", nullValue())
                .body("headers.x-http-method-override", nullValue())
                .body("headers.lambda-web-endpoint-name", equalTo("dev"))
                .body("headers.x-forwarded-proto", equalTo("http"))
                .body("headers.x-forwarded-port", equalTo("4566"))
                .body("headers.x-forwarded-for", startsWith("9.9.9.9, "))
                .body("headers.connection", equalTo("close"))
                .body("env.endpoint", equalTo("0.0.0.0:3000"))
                .body("env.init", equalTo("native-http"))
                .body("env.exec", equalTo("AWS_Lambda_nodejs24.x-lite"))
                .body("env.account", equalTo("000000000000"))
                .body("env.region", equalTo("us-east-1"))
                .body("env.custom", equalTo("yes"));
        assertEquals(echo.header("lambda-web-request-id"), echo.path("headers.lambda-web-request-id"),
                "the request id the app saw is the one in the response");

        given().header("Host", domain).body("hello body").when().post("/")
                .then().statusCode(200).body("bodyLength", equalTo(10));
        given().header("Host", domain).when().get("/status/418")
                .then().statusCode(418).header("x-app", "yes").body(equalTo("status body"));

        // A body without a Content-Length reaches the app chunked, as on AWS.
        String chunked = raw("POST /upload HTTP/1.1\r\nHost: " + domain + "\r\nTransfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n11\r\nchunked body here\r\n0\r\n\r\n");
        assertTrue(chunked.startsWith("HTTP/1.1 200 "), chunked);
        assertTrue(chunked.contains("\"bodyLength\":17"), chunked);
        assertTrue(chunked.contains("\"transfer-encoding\":\"chunked\""), chunked);

        // The app gets the path without dot segments, as on AWS. Repeated slashes stay.
        String dotted = raw("GET /a//b/./c/../d?x=1 HTTP/1.1\r\nHost: " + domain + "\r\nConnection: close\r\n\r\n");
        assertTrue(dotted.contains("\"url\":\"/a//b/d?x=1\""), dotted);

        // An AWS signature and its session token never reach the app. Other credentials do.
        given().header("Host", domain).header("Authorization", "Bearer abc").header("X-Amz-Security-Token", "tok")
                .when().get("/")
                .then().statusCode(200)
                .body("headers.authorization", equalTo("Bearer abc"))
                .body("headers.x-amz-security-token", equalTo("tok"));
        given().header("Host", domain).header("Authorization", "AWS4-HMAC-SHA256 Credential=x")
                .header("X-Amz-Security-Token", "tok").header("X-Amz-Date", "20260101T000000Z")
                .when().get("/")
                .then().statusCode(200)
                .body("headers.authorization", nullValue())
                .body("headers.x-amz-security-token", nullValue())
                .body("headers.x-amz-date", equalTo("20260101T000000Z"));
    }

    @Test
    @Timeout(180)
    void aCloudFrontDistributionReachesTheEndpointAsItsOrigin() throws Exception {
        String domain = createFunction(30);
        Distribution created = distributionInFrontOf(domain, null);
        try {
            given().header("Host", created.getDomainName()).header("x-custom", "1").when().get("/a/b?x=1")
                    .then().statusCode(200)
                    .header("lambda-web-request-id", matchesPattern(REQUEST_ID))
                    .header("lambda-web-logstream", notNullValue())
                    .body("url", equalTo("/a/b?x=1"))
                    .body("headers.host", equalTo(domain))
                    .body("headers.x-custom", equalTo("1"))
                    .body("headers.via", endsWith(" (CloudFront)"))
                    .body("headers.x-amz-cf-id", notNullValue())
                    .body("headers.lambda-web-endpoint-name", equalTo("dev"));
        } finally {
            cloudFrontService.removeDistribution(created.getId());
        }
    }

    @Test
    @Timeout(180)
    void anAlwaysAmzAuthOriginAccessControlSignsForTheViewerInFrontOfAnIamAuthEndpoint() throws Exception {
        String domain = createFunction(30, 64, "INFO", "serve", "IamAuth");
        OriginAccessControl oac = new OriginAccessControl();
        oac.setName(functionName);
        oac.setSigningProtocol("sigv4");
        oac.setSigningBehavior("always-amz-auth");
        oac.setOriginAccessControlOriginType("lambda");
        OriginAccessControl createdOac = cloudFrontService.createOriginAccessControl(oac);
        Distribution created = distributionInFrontOf(domain, createdOac.getId());
        try {
            given().header("Host", domain).when().get("/").then().statusCode(403);
            given().header("Host", created.getDomainName()).header("Authorization", "Bearer app-token")
                    .body("{}").when().post("/a")
                    .then().statusCode(200)
                    .body("method", equalTo("POST"))
                    .body("headers.authorization", equalTo("Bearer app-token"))
                    .body("headers", not(hasKey("lambda-web-cloudfront-key")))
                    .body("headers", not(hasKey("lambda-web-cloudfront-source-arn")));
        } finally {
            cloudFrontService.removeDistribution(created.getId());
            cloudFrontService.deleteOriginAccessControl(createdOac.getId(), createdOac.getEtag());
        }
    }

    /** The endpoint behind a distribution, named as on AWS: its host without a port, over HTTPS on 443. */
    private Distribution distributionInFrontOf(String domain, String originAccessControlId) {
        Origin origin = new Origin();
        origin.setId("web");
        origin.setDomainName(domain.substring(0, domain.lastIndexOf(':')));
        origin.setOriginAccessControlId(originAccessControlId);
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", "80");
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "https-only");
        origin.setCustomOriginConfig(customOriginConfig);
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("web");
        behavior.setViewerProtocolPolicy("allow-all");
        behavior.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS", "PUT", "PATCH", "POST", "DELETE"));
        behavior.setCachePolicyId("4135ea2d-6df8-44a3-9df3-4b5a84be39ad");
        behavior.setOriginRequestPolicyId("b689b0a8-53d0-40ab-baf2-68738e2966ac");
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return cloudFrontService.createDistribution(distribution, Map.of());
    }

    @Test
    @Timeout(180)
    void theOutputGoesToTheLocalAndTheCentralizedLogGroupAsJsonEvents() throws Exception {
        String domain = createFunction(30);
        Response logged = given().header("Host", domain).when().get("/log");
        logged.then().statusCode(200);
        String logStream = logged.header("lambda-web-logstream");
        String group = "/aws/lambda/web/" + functionName;

        List<String> all = awaitLogEvents(group + ".local", logStream);
        assertEquals(List.of("platform.initStart", "platform.initReport", "platform.invokeStart"),
                types(all), all.toString());
        List<String> local = all.stream().filter(event -> !isPlatformEvent(event)).toList();
        assertEquals(5, local.size(), local.toString());
        JsonPath starting = JsonPath.from(local.getFirst());
        assertEquals("INFO", starting.getString("level"));
        assertEquals("starting", starting.getString("message"));
        assertEquals("us-east-1", starting.getString("region"));
        assertFalse(local.getFirst().contains("requestId"), local.getFirst());
        JsonPath info = JsonPath.from(local.stream().filter(event -> event.contains("\"route\"")).findFirst().orElseThrow());
        assertEquals(logged.header("lambda-web-request-id"), info.getString("requestId"));
        assertEquals("dev", info.getString("endpointName"));
        assertEquals(false, info.getBoolean("message.main"));
        assertEquals(List.of("/var/runtime/index.js"), info.getList("message.argv"));
        JsonPath error = JsonPath.from(local.stream().filter(event -> event.contains("ERROR")).findFirst().orElseThrow());
        assertEquals("Error", error.getString("message.errorType"));
        assertEquals("logged error", error.getString("message.errorMessage"));
        assertEquals("Error: logged error", error.getList("message.stackTrace").getFirst());
        assertTrue(local.contains("raw line\n"), local.toString());
        assertTrue(local.stream().allMatch(event -> event.endsWith("\n")), local.toString());

        List<String> centralized = awaitLogEvents(group, logStream + "-000000000000-us-east-1");
        assertEquals(new HashSet<>(all), new HashSet<>(centralized));
    }

    @Test
    @Timeout(180)
    void anIamAuthEndpointServesSignedRequestsWithoutTheirSignature() throws Exception {
        String domain = createFunction(30);
        given().contentType("application/json").body("{\"authType\": \"IamAuth\"}")
                .when().patch(BASE + "/web-functions/" + functionName + "/endpoints/dev")
                .then().statusCode(202);
        given().header("Host", domain).when().get("/").then().statusCode(403);

        LambdaWebRequestSigner.Signed signed = new LambdaWebRequestSigner().sign("POST", domain, "/items?b=2&a=1");
        given().urlEncodingEnabled(false).header("Host", domain).headers(signed.headers()).body("hello body")
                .when().post(signed.pathAndQuery())
                .then().statusCode(200)
                .body("url", equalTo(signed.pathAndQuery()))
                .body("bodyLength", equalTo(10))
                .body("headers.authorization", nullValue())
                .body("headers.x-amz-content-sha256", equalTo("UNSIGNED-PAYLOAD"))
                .body("headers.x-amz-date", equalTo(signed.headers().get("X-Amz-Date")));

        // A presigned request reaches the app with its query as sent.
        LambdaWebRequestSigner.Signed presigned = new LambdaWebRequestSigner().presigned().sign("GET", domain, "/q");
        given().urlEncodingEnabled(false).header("Host", domain).headers(presigned.headers())
                .when().get(presigned.pathAndQuery())
                .then().statusCode(200)
                .body("url", equalTo(presigned.pathAndQuery()));

        // SigV4a for a region set that covers the region passes, and loses its signature the same way.
        LambdaWebRequestSigner.Signed asymmetric = new LambdaWebRequestSigner().sigV4a("us-*").sign("GET", domain, "/a");
        given().header("Host", domain).headers(asymmetric.headers())
                .when().get("/a")
                .then().statusCode(200)
                .body("headers.authorization", nullValue())
                .body("headers.x-amz-region-set", equalTo("us-*"));
    }

    @Test
    @Timeout(180)
    void aTimeoutAndACrashAnswerWithThePlatformPagesAndTheNextRequestGetsAFreshEnvironment() throws Exception {
        String domain = createFunction(3);

        Response first = given().header("Host", domain).when().get("/status/200");
        first.then().statusCode(200);
        String logStream = first.header("lambda-web-logstream");

        Response timeout = given().header("Host", domain).when().get("/slow");
        timeout.then().statusCode(504)
                .header("lambda-web-error-type", "Runtime.RequestTimeout")
                .body(equalTo(LambdaWebErrorPages.html(LambdaWebErrorPages.REQUEST_TIMEOUT,
                        timeout.header("lambda-web-request-id"))));

        // A response that is still streaming at the deadline is cut, as on AWS.
        String streamed = raw("GET /stream HTTP/1.1\r\nHost: " + domain + "\r\nConnection: close\r\n\r\n");
        assertTrue(streamed.startsWith("HTTP/1.1 200 "), streamed);
        assertTrue(streamed.contains("chunk 0"), streamed);
        assertFalse(streamed.contains("done"), streamed);

        Response crash = given().header("Host", domain).when().get("/crash");
        crash.then().statusCode(503)
                .header("lambda-web-error-type", "Runtime.OriginRequestError")
                .body(equalTo(LambdaWebErrorPages.html(LambdaWebErrorPages.ORIGIN_REQUEST_ERROR,
                        crash.header("lambda-web-request-id"))));

        Response after = given().header("Host", domain).when().get("/status/200");
        after.then().statusCode(200).header("lambda-web-logstream", not(equalTo(logStream)));
        assertNotEquals(logStream, after.header("lambda-web-logstream"), "a new environment serves the next request");
    }

    @Test
    @Timeout(180)
    void anEnvironmentThatExitsBetweenRequestsIsReplacedUnseen() throws Exception {
        String domain = createFunction(30);
        String logStream = given().header("Host", domain).when().get("/status/200")
                .then().statusCode(200).extract().header("lambda-web-logstream");
        Environment environment = environments.environmentOf(logStream);

        given().header("Host", domain).when().get("/exit").then().statusCode(200).body(equalTo("bye"));
        environment.awaitExit(Duration.ofSeconds(30)).get();
        assertTrue(environment.exitedBefore(System.nanoTime()), "Floci did not see the environment exit");

        given().header("Host", domain).when().get("/status/200")
                .then().statusCode(200).header("lambda-web-logstream", not(equalTo(logStream)));
    }

    @Test
    @Timeout(180)
    void theMetadataEndpointAnswersTheZoneToTheEnvironmentToken() throws Exception {
        String domain = createFunction(30);

        JsonPath answer = given().header("Host", domain).when().get("/metadata").then().statusCode(200)
                .extract().jsonPath();
        assertEquals(200, answer.getInt("status"));
        assertEquals("{\"AvailabilityZoneID\":\"use1-az1\"}\n", answer.getString("body"));
        assertEquals(401, answer.getInt("refused"));
        assertEquals(answer.getString("api"), answer.getString("runtimeApi"));
        assertTrue(answer.getString("token").matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
    }

    @Test
    @Timeout(240)
    void aPerRegionEndpointServesEachRegionFromItsOwnEnvironments() throws Exception {
        createFunction(30, 1);
        String endpoints = BASE + "/web-functions/" + functionName + "/endpoints";
        String revisionId = given().when().get(endpoints + "/dev").then().statusCode(200)
                .extract().path("revisionWeights[0].revisionId");
        JsonPath created = given().contentType("application/json")
                .body("""
                        {"endpointName": "per", "endpointType": "PerRegion", "regions": ["us-west-2"],
                         "authType": "ApplicationManaged", "scalingConfig": {"maxEnvironments": 2},
                         "revisionWeights": [{"revisionId": "%s", "weight": 100}]}""".formatted(revisionId))
                .when().put(endpoints)
                .then().statusCode(202)
                .extract().jsonPath();
        String east = created.getString("regionalEndpoints.us-east-1.domainName");
        String west = created.getString("regionalEndpoints.us-west-2.domainName");

        Response westEcho = given().header("Host", west).when().get("/");
        westEcho.then().statusCode(200).body("env.region", equalTo("us-west-2"));
        String westStream = westEcho.header("lambda-web-logstream");
        given().header("Host", east).when().get("/").then().statusCode(200).body("env.region", equalTo("us-east-1"))
                .header("lambda-web-logstream", not(equalTo(westStream)));
        JsonPath zone = given().header("Host", west).when().get("/metadata").then().statusCode(200)
                .extract().jsonPath();
        assertEquals("{\"AvailabilityZoneID\":\"usw2-az1\"}\n", zone.getString("body"));

        // maxEnvironments holds in each region on its own, as on AWS.
        AtomicInteger next = new AtomicInteger();
        CyclicBarrier together = new CyclicBarrier(4);
        List<String> waited = parallel(4, () -> raw("GET /wait HTTP/1.1\r\nHost: "
                + (next.getAndIncrement() % 2 == 0 ? east : west) + "\r\nConnection: close\r\n\r\n", together));
        assertTrue(waited.stream().allMatch(response -> response.startsWith("HTTP/1.1 200 ")),
                String.join("\n----\n", waited));

        // The .local group is in the region that served and the centralized one in the home region. Each batch
        // reaches the .local group first.
        String group = "/aws/lambda/web/" + functionName;
        Predicate<List<String>> started = events -> events.stream().anyMatch(event -> event.contains("\"starting\""));
        assertTrue(started.test(awaitLogEvents(group, westStream + "-000000000000-us-west-2", started)));
        List<String> local = logEvents("us-west-2", group + ".local", westStream);
        JsonPath starting = JsonPath.from(local.stream().filter(event -> event.contains("\"starting\""))
                .findFirst().orElseThrow());
        assertEquals("us-west-2", starting.getString("region"));
        JsonPath initStart = platformEvent(local, "platform.initStart");
        assertEquals("us-west-2", initStart.getString("region"));
        assertTrue(initStart.getString("record.runtimeVersionArn").startsWith(
                "arn:aws:lambda:us-west-2::runtime/nodejs24.x/version/"), initStart.prettify());
        assertEquals(List.of(), logEvents("us-east-1", group + ".local", westStream));

        // AWS/Lambda/Web is also centralized in the home region. The usage metrics are not.
        Map<String, String> endpoint = Map.of("FunctionName", functionName, "EndpointName", "per");
        Map<String, String> westUsage = Map.of("FunctionName", functionName, "EndpointName", "per",
                "ExecutionRegion", "us-west-2");
        metrics.awaitPublished(Duration.ofSeconds(5));
        assertEquals(4, LambdaWebMetricStatistics.of("us-west-2", LambdaWebMetrics.NAMESPACE, "ExecutedRequests",
                endpoint, "Sum"));
        assertEquals(7, LambdaWebMetricStatistics.of("us-east-1", LambdaWebMetrics.NAMESPACE, "ExecutedRequests",
                endpoint, "Sum"));
        assertEquals(4, LambdaWebMetricStatistics.of("us-west-2", LambdaWebMetrics.USAGE_NAMESPACE,
                "ExecutedRequests", westUsage, "Sum"));
        assertEquals(0, LambdaWebMetricStatistics.of("us-east-1", LambdaWebMetrics.USAGE_NAMESPACE,
                "ExecutedRequests", westUsage, "Sum"));

        given().when().delete(endpoints + "/per").then().statusCode(204);
        given().header("Host", west).when().get("/").then().statusCode(404)
                .header("lambda-web-error-type", "Platform.InvalidEndpoint");
    }

    @Test
    @Timeout(180)
    void anEnvironmentRunsTheCodeTheRevisionWasCreatedFrom() throws Exception {
        String domain = createFunction(30);
        s3Service.putObject(BUCKET, functionName + ".zip", zip("process.exit(1);"), "application/zip", Map.of());

        given().header("Host", domain).when().get("/status/200").then().statusCode(200).body(equalTo("status body"));
    }

    @Test
    @Timeout(240)
    void environmentsScaleUpToMaxEnvironmentsAndTheRateLimitThrottles() throws Exception {
        String domain = createFunction(30, 1);
        given().contentType("application/json").body("{\"scalingConfig\": {\"maxEnvironments\": 2}}")
                .when().patch(BASE + "/web-functions/" + functionName + "/endpoints/dev")
                .then().statusCode(202);

        // One request per environment: two run, the rest are refused, as recorded on AWS.
        List<String> responses = parallel(5, () -> raw("GET /wait HTTP/1.1\r\nHost: " + domain
                + "\r\nConnection: close\r\n\r\n"));
        long served = responses.stream().filter(response -> response.startsWith("HTTP/1.1 200 ")).count();
        List<String> throttled = responses.stream().filter(response -> response.startsWith("HTTP/1.1 429 ")).toList();
        assertEquals(2, served, String.join("\n----\n", responses));
        assertEquals(3, throttled.size(), String.join("\n----\n", responses));
        for (String response : throttled) {
            assertTrue(response.contains("\r\nlambda-web-error-type: Platform.EndpointMaxEnvThrottle\r\n"), response);
            assertTrue(response.contains("<p>Request rate exceeded. Please try again later.</p>"), response);
        }
        Map<String, String> usage = Map.of("FunctionName", functionName, "EndpointName", "dev",
                "ExecutionRegion", "us-east-1");
        metrics.awaitPublished(Duration.ofSeconds(5));
        assertEquals(3, LambdaWebMetricStatistics.of(LambdaWebMetrics.USAGE_NAMESPACE,
                "EndpointActiveEnvironmentThrottles", usage, "Sum"));
        assertEquals(2, LambdaWebMetricStatistics.of(LambdaWebMetrics.USAGE_NAMESPACE, "EndpointActiveEnvironments",
                usage, "Maximum"));

        // Requests one after another rotate over the two environments.
        Set<String> logStreams = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            logStreams.add(given().header("Host", domain).when().get("/status/200")
                    .then().statusCode(200).extract().header("lambda-web-logstream"));
        }
        assertEquals(2, logStreams.size(), logStreams.toString());

        // A new revision takes the place of an idle environment of the old one.
        String revisionId = given().contentType("application/json")
                .body("""
                        {"buildConfig": {"codeConfig": {"s3Object": {"bucket": "%s", "key": "%s.zip"}},
                                         "runtimeConfig": {"runtime": "nodejs24.x"}},
                         "serviceConfig": {"executionRoleArn": "%s", "timeoutSeconds": 30}}
                        """.formatted(BUCKET, functionName, ROLE))
                .when().post(BASE + "/web-functions/" + functionName + "/revisions")
                .then().statusCode(202)
                .extract().path("revisionId");
        String moved = given().header("Host", domain).when().get("/status/200")
                .then().statusCode(200).extract().header("lambda-web-logstream");
        assertTrue(moved.contains("[" + revisionId + "]"), moved);
        logStreams.add(moved);

        // A recreated endpoint shares no environment or rate with the deleted one.
        String endpoints = BASE + "/web-functions/" + functionName + "/endpoints";
        given().when().delete(endpoints + "/dev").then().statusCode(204);
        String recreated = given().contentType("application/json")
                .body("{\"endpointName\": \"dev\", \"endpointType\": \"HomeRegion\","
                        + " \"authType\": \"ApplicationManaged\", \"autoDeploymentMode\": \"LatestRevision\","
                        + " \"throttleConfig\": {\"rateLimit\": 100}}")
                .when().put(endpoints)
                .then().statusCode(202)
                .extract().path("domainName");
        String fresh = given().header("Host", recreated).when().get("/status/200")
                .then().statusCode(200).extract().header("lambda-web-logstream");
        assertFalse(logStreams.contains(fresh), fresh + " " + logStreams);

        // Requests that all arrive at once are only admitted up to the rate.
        int count = 200;
        CyclicBarrier together = new CyclicBarrier(count);
        List<String> burst = parallel(count, () -> raw("GET /status/200 HTTP/1.1\r\nHost: " + recreated
                + "\r\nConnection: close\r\n\r\n", together));
        List<String> limited = burst.stream().filter(response -> response.startsWith("HTTP/1.1 429 ")).toList();
        assertFalse(limited.isEmpty(), "a burst well past the rate is throttled");
        for (String response : limited) {
            assertTrue(response.contains("\r\nlambda-web-error-type: Platform.EndpointRequestThrottle\r\n"), response);
        }
        metrics.awaitPublished(Duration.ofSeconds(5));
        assertEquals(limited.size(), LambdaWebMetricStatistics.of(LambdaWebMetrics.USAGE_NAMESPACE,
                "EndpointRequestThrottles", usage, "Sum"));
    }

    private static List<String> parallel(int count, Callable<String> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(call));
            }
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private String createFunction(int timeoutSeconds) throws IOException {
        return createFunction(timeoutSeconds, 64);
    }

    private String createFunction(int timeoutSeconds, int maxConcurrencyPerEnvironment) throws IOException {
        return createFunction(timeoutSeconds, maxConcurrencyPerEnvironment, "INFO", "serve");
    }

    private String createFunction(int timeoutSeconds, int maxConcurrencyPerEnvironment, String systemLogLevel,
                                  String mode) throws IOException {
        return createFunction(timeoutSeconds, maxConcurrencyPerEnvironment, systemLogLevel, mode, "ApplicationManaged");
    }

    private String createFunction(int timeoutSeconds, int maxConcurrencyPerEnvironment, String systemLogLevel,
                                  String mode, String authType) throws IOException {
        return createFunction(timeoutSeconds, maxConcurrencyPerEnvironment, systemLogLevel, mode, authType, null);
    }

    private String createFunction(int timeoutSeconds, int maxConcurrencyPerEnvironment, String systemLogLevel,
                                  String mode, String authType, String nodeOptions) throws IOException {
        functionName = "dp-docker-" + Long.toString(System.nanoTime(), 36);
        LambdaWebRoles.trustLambda(iamService, ROLE);
        LambdaWebCodeBuckets.create(s3Service, BUCKET);
        s3Service.putObject(BUCKET, functionName + ".zip", zip(APP), "application/zip", Map.of());
        return given().contentType("application/json")
                .body("""
                        {"functionName": "%s",
                         "revisionConfig": {
                           "buildConfig": {"codeConfig": {"s3Object": {"bucket": "%s", "key": "%s.zip"}},
                                           "runtimeConfig": {"runtime": "nodejs24.x"}},
                           "serviceConfig": {"executionRoleArn": "%s", "timeoutSeconds": %d,
                                             "maxConcurrencyPerEnvironment": %d,
                                             "environmentVariables": {"CUSTOM": "yes", "MODE": "%s"%s},
                                             "telemetryConfig": {"loggingConfig": {"systemLogLevel": "%s"}}}},
                         "endpointConfig": {"endpointName": "dev", "endpointType": "HomeRegion",
                                            "authType": "%s", "autoDeploymentMode": "LatestRevision"}}
                        """.formatted(functionName, BUCKET, functionName, ROLE, timeoutSeconds,
                        maxConcurrencyPerEnvironment, mode,
                        nodeOptions == null ? "" : ", \"NODE_OPTIONS\": \"" + nodeOptions + "\"",
                        systemLogLevel, authType))
                .when().put(BASE + "/web-functions")
                .then().statusCode(202)
                .extract().path("endpoint.domainName");
    }

    @Test
    @Timeout(180)
    void platformEventsFollowAnEnvironmentFromItsStartToItsEnd() throws Exception {
        String domain = createFunction(30, 64, "DEBUG", "serve");
        String group = "/aws/lambda/web/" + functionName + ".local";
        Response served = given().header("Host", domain).when().get("/status/201");
        served.then().statusCode(201);
        String crashed = served.header("lambda-web-logstream");
        given().header("Host", domain).when().get("/crash").then().statusCode(503);

        List<String> crash = awaitLogEvents(group, crashed, events -> types(events).contains("platform.spinDownReport")
                && types(events).stream().filter("platform.requestComplete"::equals).count() == 2);
        JsonPath initStart = platformEvent(crash, "platform.initStart");
        String instanceId = initStart.getString("record.instanceId");
        assertEquals("INFO", initStart.getString("level"));
        assertEquals("us-east-1", initStart.getString("region"));
        assertEquals(functionName, initStart.getString("record.functionName"));
        assertEquals("nodejs24.x:nodejs.web24.v30", initStart.getString("record.runtimeVersion"));
        assertEquals("arn:aws:lambda:us-east-1::runtime/nodejs24.x/version/nodejs.web24.v30",
                initStart.getString("record.runtimeVersionArn"));
        assertEquals("native-http", initStart.getString("record.initializationType"));
        JsonPath initReport = platformEvent(crash, "platform.initReport");
        assertEquals("success", initReport.getString("record.status"));
        assertEquals(instanceId, initReport.getString("record.instanceId"));
        assertTrue(initReport.getInt("record.metrics.maxMemoryUsageMb") > 0, initReport.prettify());
        List<JsonPath> requests = crash.stream().filter(event -> event.contains("platform.requestComplete"))
                .map(JsonPath::from).toList();
        assertEquals(2, requests.size(), crash.toString());
        JsonPath ok = requests.stream().filter(event -> event.getInt("record.result") == 201).findFirst().orElseThrow();
        assertEquals("DEBUG", ok.getString("level"));
        assertEquals(served.header("lambda-web-request-id"), ok.getString("record.requestId"));
        assertEquals("dev", ok.getString("record.endpointName"));
        assertEquals(11, ok.getInt("record.metrics.responseBytes"));
        assertFalse(ok.getMap("record").containsKey("errorType"), ok.prettify());
        JsonPath failed = requests.stream().filter(event -> event.getInt("record.result") == 503).findFirst().orElseThrow();
        assertEquals("Runtime.OriginRequestError", failed.getString("record.errorType"));
        assertEquals(0, failed.getInt("record.metrics.responseBytes"));
        JsonPath crashReport = platformEvent(crash, "platform.invokeReport");
        assertEquals("WARN", crashReport.getString("level"));
        assertEquals("failure", crashReport.getString("record.status"));
        assertEquals("Runtime.ExitError", crashReport.getString("record.errorType"));
        assertEquals(2, crashReport.getInt("record.metrics.totalRequests"));
        assertEquals("success", platformEvent(crash, "platform.spinDownReport").getString("record.status"));

        Response echoed = given().header("Host", domain).when().get("/");
        echoed.then().statusCode(200);
        String stopped = echoed.header("lambda-web-logstream");
        environments.discard(environments.environmentOf(stopped));
        List<String> stop = awaitLogEvents(group, stopped, events -> types(events).contains("platform.spinDownReport"));
        assertEquals(List.of("platform.initStart", "platform.initReport", "platform.invokeStart",
                "platform.requestComplete", "platform.invokeSummary", "platform.invokeReport",
                "platform.spinDownStart", "platform.spinDownReport"), types(stop), stop.toString());
        JsonPath stopReport = platformEvent(stop, "platform.invokeReport");
        assertEquals("INFO", stopReport.getString("level"));
        assertEquals("success", stopReport.getString("record.status"));
        assertEquals(1, stopReport.getInt("record.metrics.totalRequests"));
        assertEquals(1, platformEvent(stop, "platform.invokeSummary").getInt("record.metrics.requests"));
        assertEquals("success", platformEvent(stop, "platform.spinDownReport").getString("record.status"));

        // Each environment has a datapoint at init and one when it ends, with the body bytes of its requests.
        Map<String, String> function = Map.of("FunctionName", functionName);
        Map<String, String> endpoint = Map.of("FunctionName", functionName, "EndpointName", "dev");
        Map<String, String> usage = Map.of("FunctionName", functionName, "EndpointName", "dev",
                "ExecutionRegion", "us-east-1");
        metrics.awaitPublished(Duration.ofSeconds(5));
        assertEquals(4, LambdaWebMetricStatistics.of(LambdaWebMetrics.NAMESPACE, "EnvironmentActiveTime", function,
                "SampleCount"));
        assertEquals(11 + echoed.asByteArray().length, LambdaWebMetricStatistics.of(LambdaWebMetrics.NAMESPACE,
                "ResponseBytes", function, "Sum"));
        assertEquals(1, LambdaWebMetricStatistics.of(LambdaWebMetrics.NAMESPACE, "EnvironmentConcurrentRequests",
                function, "Maximum"));
        assertTrue(LambdaWebMetricStatistics.of(LambdaWebMetrics.NAMESPACE, "EnvironmentMaxMemoryUsage", function,
                "Maximum") > 0);
        assertEquals(3, LambdaWebMetricStatistics.of(LambdaWebMetrics.NAMESPACE, "ExecutedRequests", endpoint, "Sum"));
        assertEquals(1, LambdaWebMetricStatistics.of(LambdaWebMetrics.NAMESPACE, "HTTPResponseStatusClass5XX",
                endpoint, "Sum"));
        assertEquals(0, LambdaWebMetricStatistics.of(LambdaWebMetrics.NAMESPACE, "HTTPResponseStatusClass4XX",
                endpoint, "Sum"));
        assertEquals(3, LambdaWebMetricStatistics.of(LambdaWebMetrics.USAGE_NAMESPACE, "ExecutedRequests", usage,
                "Sum"));
        assertEquals(3, LambdaWebMetricStatistics.of(LambdaWebMetrics.USAGE_NAMESPACE, "EndpointActiveEnvironments",
                usage, "SampleCount"));
    }

    @Test
    @Timeout(180)
    void anAppThatFailsAtImportWritesItsOutputAndAFailedInitReport() throws Exception {
        String domain = createFunction(30, 64, "WARN", "throw");
        String group = "/aws/lambda/web/" + functionName + ".local";
        given().header("Host", domain).when().get("/").then().statusCode(502)
                .header("lambda-web-error-type", "Application.InitCodeError");

        List<String> events = awaitLogEvents(group, awaitLogStream(group), each -> types(each).contains(
                "platform.initReport") && each.stream().anyMatch(event -> event.contains("init boom")));
        assertEquals(List.of("platform.initReport"), types(events), events.toString());
        JsonPath initReport = platformEvent(events, "platform.initReport");
        assertEquals("WARN", initReport.getString("level"));
        assertEquals("failure", initReport.getString("record.status"));
        assertEquals("Application.InitCodeError", initReport.getString("record.errorType"));
        List<JsonPath> output = events.stream().filter(event -> !isPlatformEvent(event)).map(JsonPath::from).toList();
        assertEquals(List.of("ERROR Application.InitCodeError: could not load customer code: init boom", "INFO starting"),
                output.stream().map(event -> event.getString("level") + " " + event.getString("message")).sorted()
                        .toList(), events.toString());
    }

    @Test
    @Timeout(180)
    void heapFlagsInNodeOptionsReplaceTheDefaultOnes() throws Exception {
        String domain = createFunction(30, 64, "INFO", "serve", "ApplicationManaged",
                "--expose-gc --max-old-space-size=1536");

        given().header("Host", domain).when().get("/").then().statusCode(200)
                .body("execArgv", contains("--expose-gc", "--max-http-header-size", "135168",
                        "--no-experimental-require-module", "--max-old-space-size=1536"))
                .body("heapSizeLimit", equalTo(1811939328));
    }

    @Test
    @Timeout(180)
    void anAppThatExitsWithAnErrorAtStartGetsTheExitErrorPage() throws Exception {
        String domain = createFunction(30, 64, "WARN", "exit");
        String group = "/aws/lambda/web/" + functionName + ".local";
        given().header("Host", domain).when().get("/").then().statusCode(502)
                .header("lambda-web-error-type", "Runtime.ExitError");

        List<String> events = awaitLogEvents(group, awaitLogStream(group),
                each -> types(each).contains("platform.initReport"));
        assertEquals("Runtime.ExitError", platformEvent(events, "platform.initReport").getString("record.errorType"));
    }

    @Test
    @Timeout(180)
    void anAppThatCannotFindAModuleGetsTheImportCodeErrorPage() throws Exception {
        String domain = createFunction(30, 64, "WARN", "missing");
        String group = "/aws/lambda/web/" + functionName + ".local";
        given().header("Host", domain).when().get("/").then().statusCode(502)
                .header("lambda-web-error-type", "Runtime.ImportCodeError");

        List<String> events = awaitLogEvents(group, awaitLogStream(group), each -> types(each).contains(
                "platform.initReport") && each.stream().anyMatch(event -> event.contains("ImportCodeError:")));
        assertEquals("Runtime.ImportCodeError",
                platformEvent(events, "platform.initReport").getString("record.errorType"));
        assertTrue(events.stream().anyMatch(event -> event.contains("\"message\":\"Runtime.ImportCodeError: could not"
                + " load customer code: Cannot find module 'no-such-module'\\nRequire stack:\\n- /var/task/index.js."
                + " The entrypoint must be")), events.toString());
    }

    private static boolean isPlatformEvent(String event) {
        return event.startsWith("{") && event.contains("\"type\":\"platform.");
    }

    private static List<String> types(List<String> events) {
        return events.stream().filter(LambdaWebDataPlaneDockerIntegrationTest::isPlatformEvent)
                .map(event -> JsonPath.from(event).getString("type")).toList();
    }

    private static JsonPath platformEvent(List<String> events, String type) {
        return events.stream().filter(LambdaWebDataPlaneDockerIntegrationTest::isPlatformEvent)
                .map(JsonPath::from).filter(event -> type.equals(event.getString("type")))
                .findFirst().orElseThrow(() -> new AssertionError("No " + type + " in " + events));
    }

    /** The messages of a log stream once they are {@code complete}, or what it holds after 15 seconds. */
    private static List<String> awaitLogEvents(String logGroup, String logStream, Predicate<List<String>> complete)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<String> messages = logEvents(logGroup, logStream);
        while (!complete.test(messages) && System.nanoTime() < deadline) {
            Thread.sleep(200);
            messages = logEvents(logGroup, logStream);
        }
        return messages;
    }

    private static String awaitLogStream(String logGroup) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            List<String> streams = RestAssuredJsonUtils.awsAction("Logs_20140328", "DescribeLogStreams",
                            "{\"logGroupName\": \"%s\"}".formatted(logGroup))
                    .then().extract().jsonPath().getList("logStreams.logStreamName", String.class);
            if (streams != null && !streams.isEmpty()) {
                return streams.getFirst();
            }
            assertTrue(System.nanoTime() < deadline, "No log stream in " + logGroup);
            Thread.sleep(200);
        }
    }

    /**
     * The messages of a log stream once the last output of {@code /log} on stdout and on stderr is in it, or what it
     * holds after 15 seconds. The output of one stream arrives in order, so nothing written before comes later.
     */
    private static List<String> awaitLogEvents(String logGroup, String logStream) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<String> messages = logEvents(logGroup, logStream);
        while (!(messages.contains("last line\n") && messages.stream().anyMatch(event -> event.contains("\"ERROR\"")))
                && System.nanoTime() < deadline) {
            Thread.sleep(200);
            messages = logEvents(logGroup, logStream);
        }
        return messages;
    }

    private static List<String> logEvents(String logGroup, String logStream) {
        return logEvents("us-east-1", logGroup, logStream);
    }

    private static List<String> logEvents(String region, String logGroup, String logStream) {
        List<String> messages = given()
                .header("Authorization", auth("test", region, "logs"))
                .header("X-Amz-Target", "Logs_20140328.GetLogEvents")
                .contentType("application/x-amz-json-1.1")
                .body("{\"logGroupName\": \"%s\", \"logStreamName\": \"%s\", \"startFromHead\": true}"
                        .formatted(logGroup, logStream))
                .when().post("/")
                .then().extract().jsonPath().getList("events.message", String.class);
        return messages == null ? List.of() : messages;
    }

    /** Sends one raw HTTP/1.1 request with {@code Connection: close} and reads until the server closes. */
    private static String raw(String request) throws Exception {
        return raw(request, null);
    }

    /** As {@link #raw(String)}, sent once every party of {@code together} has connected. */
    private static String raw(String request, CyclicBarrier together) throws Exception {
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout(30_000);
            if (together != null) {
                together.await(30, TimeUnit.SECONDS);
            }
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] zip(String indexJs) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(indexJs.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
