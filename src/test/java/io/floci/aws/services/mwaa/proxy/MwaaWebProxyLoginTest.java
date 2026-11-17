package io.floci.aws.services.mwaa.proxy;

import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.testutil.FreePorts;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /aws_mwaa/login} against a fake Airflow that serves the same login form shape as
 * Airflow 2.x's Flask-AppBuilder: a GET that sets an anonymous {@code session} cookie and renders a
 * {@code csrf_token} hidden input, and a POST that only signs in when the cookie, the CSRF token
 * and the credentials all match, answering a {@code 302} to {@code /home} with a new cookie. Wrong
 * credentials get what Flask-AppBuilder answers them with, a {@code 302} back to the login form with
 * a session cookie holding the flashed message, and a form that fails validation gets the form again.
 */
class MwaaWebProxyLoginTest {

    private static final String CSRF = "IjJiOWM0MDA2YWFmMzJhMTBhMzlkNGM2NDkzZDFiM2QzYjVlYTQ1NDYi.avj96w.5B4Sc88q";
    private static final String SIGNED_IN_COOKIE = "session=signed-in.abc; Expires=Tue, 15 Dec 2026 00:33:56 GMT; "
            + "HttpOnly; Path=/; SameSite=Lax";

    private Vertx vertx;
    private HttpServer backend;
    private MwaaWebProxy proxy;
    private int proxyPort;
    private final List<String> backendRequests = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        backend = vertx.createHttpServer()
                .requestHandler(this::fakeAirflow)
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (proxy != null) {
            proxy.stop();
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private void fakeAirflow(HttpServerRequest req) {
        backendRequests.add(req.method() + " " + req.uri());
        if (req.method() == HttpMethod.GET && "/login/".equals(req.path())) {
            req.response()
                    .putHeader("Set-Cookie", "session=anonymous.xyz; HttpOnly; Path=/; SameSite=Lax")
                    .putHeader("Content-Type", "text/html; charset=utf-8")
                    .end("<form method=\"post\"><input id=\"csrf_token\" name=\"csrf_token\" type=\"hidden\" value=\""
                            + CSRF + "\"><input name=\"username\"><input name=\"password\"></form>");
            return;
        }
        if (req.method() == HttpMethod.POST && "/login/".equals(req.path())) {
            req.body().onSuccess(body -> {
                Map<String, String> form = parseForm(body.toString(StandardCharsets.UTF_8));
                boolean validForm = "session=anonymous.xyz".equals(req.getHeader("Cookie"))
                        && CSRF.equals(form.get("csrf_token"));
                boolean signedIn = "admin".equals(form.get("username")) && "s3cret&=".equals(form.get("password"));
                if (!validForm) {
                    req.response().setStatusCode(200).end("<form>The CSRF token is missing.</form>");
                } else if (signedIn) {
                    req.response().setStatusCode(302).putHeader("Location", "/home")
                            .putHeader("Set-Cookie", SIGNED_IN_COOKIE).end();
                } else {
                    req.response().setStatusCode(302).putHeader("Location", "/login/?next=")
                            .putHeader("Set-Cookie", "session=flashed-invalid-login.xyz; HttpOnly; Path=/").end();
                }
            });
            return;
        }
        req.response().setStatusCode(200).end("forwarded " + req.path());
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return form;
    }

    private void startProxy(String password) throws Exception {
        proxy = new MwaaWebProxy("env1", vertx, "127.0.0.1", backend.actualPort(),
                (environmentName, token) -> false,
                command -> new ContainerExec.Result(0, "", "", false),
                (environmentName, token) -> "env1".equals(environmentName) && "good-token".equals(token),
                new MwaaWebProxy.AirflowAccount("admin", password));
        proxyPort = FreePorts.anyInterfacePort();
        proxy.start(proxyPort);
    }

    private LoginResponse postLogin(String contentType, String body) throws Exception {
        HttpClient client = vertx.createHttpClient();
        return client.request(new RequestOptions()
                        .setHost("127.0.0.1")
                        .setPort(proxyPort)
                        .setMethod(HttpMethod.POST)
                        .setURI("/aws_mwaa/login"))
                .compose(request -> {
                    request.putHeader("Content-Type", contentType);
                    return request.send(Buffer.buffer(body));
                })
                .compose(response -> response.body().map(responseBody -> new LoginResponse(response.statusCode(),
                        response.getHeader("Location"), response.cookies(), responseBody.toString())))
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void aValidWebTokenSignsInAndReturnsAirflowsSessionCookie() throws Exception {
        startProxy("s3cret&=");

        LoginResponse response = postLogin("application/x-www-form-urlencoded", "token=good-token");

        assertEquals(302, response.statusCode());
        assertEquals("/home", response.location());
        assertEquals(List.of(SIGNED_IN_COOKIE), response.cookies());
        assertEquals(List.of("GET /login/", "POST /login/"), backendRequests);
    }

    @Test
    void anUnknownOrExpiredWebTokenIsRefusedWithoutReachingAirflow() throws Exception {
        startProxy("s3cret&=");

        LoginResponse wrongToken = postLogin("application/x-www-form-urlencoded", "token=other-token");
        LoginResponse noToken = postLogin("application/x-www-form-urlencoded", "something=else");

        assertEquals(401, wrongToken.statusCode());
        assertEquals(401, noToken.statusCode());
        assertTrue(wrongToken.cookies().isEmpty());
        assertEquals(List.of(), backendRequests);
    }

    @Test
    void anAirflowThatRefusesTheSignInAnswersBadGateway() throws Exception {
        startProxy("wrong-password");

        LoginResponse response = postLogin("application/x-www-form-urlencoded", "token=good-token");

        assertEquals(502, response.statusCode());
        assertTrue(response.cookies().isEmpty());
        assertTrue(response.body().contains("HTTP 302") && response.body().contains("/login/"), response.body());
    }

    @Test
    void aSignInWhoseRedirectLeadsBackToTheLoginFormIsRefused() {
        assertTrue(MwaaWebProxy.leavesTheLoginForm("/home"));
        assertTrue(MwaaWebProxy.leavesTheLoginForm("http://airflow:8080/"));
        assertFalse(MwaaWebProxy.leavesTheLoginForm("/login/?next="));
        assertFalse(MwaaWebProxy.leavesTheLoginForm("http://airflow:8080/login/"));
        assertFalse(MwaaWebProxy.leavesTheLoginForm(null));
    }

    @Test
    void otherRequestsAreStillForwarded() throws Exception {
        startProxy("s3cret&=");
        HttpClient client = vertx.createHttpClient();

        String body = client.request(new RequestOptions().setHost("127.0.0.1").setPort(proxyPort)
                        .setMethod(HttpMethod.GET).setURI("/aws_mwaa/login"))
                .compose(request -> request.send())
                .compose(response -> response.body())
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).toString();

        assertEquals("forwarded /aws_mwaa/login", body);
    }

    @Test
    void theLoginWorksOverHttpsToo() throws Exception {
        proxy = new MwaaWebProxy("env1", vertx, "127.0.0.1", backend.actualPort(),
                (environmentName, token) -> false,
                command -> new ContainerExec.Result(0, "", "", false),
                (environmentName, token) -> "good-token".equals(token),
                new MwaaWebProxy.AirflowAccount("admin", "s3cret&="));
        proxyPort = FreePorts.anyInterfacePort();
        proxy.start(proxyPort, SelfSignedCertificate.create("localhost").keyCertOptions());
        HttpClient client = vertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true)
                .setVerifyHost(false));

        int status = client.request(new RequestOptions().setHost("127.0.0.1").setPort(proxyPort)
                        .setMethod(HttpMethod.POST).setURI("/aws_mwaa/login"))
                .compose(request -> {
                    request.putHeader("Content-Type", "application/x-www-form-urlencoded");
                    return request.send(Buffer.buffer("token=good-token"));
                })
                .map(response -> response.statusCode())
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(302, status);
    }

    @Test
    void csrfTokenIsReadFromTheHiddenInputWhateverItsAttributeOrder() {
        assertEquals("abc", MwaaWebProxy.csrfToken("<input id=\"csrf_token\" name=\"csrf_token\" type=\"hidden\" "
                + "value=\"abc\">"));
        assertEquals("abc", MwaaWebProxy.csrfToken("<input value=\"abc\" type=\"hidden\" name=\"csrf_token\">"));
        assertNull(MwaaWebProxy.csrfToken("<input name=\"username\" value=\"abc\">"));
    }

    @Test
    void sessionCookieIsTheNameValuePairOfTheSessionSetCookie() {
        assertEquals("session=abc", MwaaWebProxy.sessionCookie(List.of("other=1; Path=/", "session=abc; HttpOnly")));
        assertNull(MwaaWebProxy.sessionCookie(List.of("other=1; Path=/")));
    }

    private record LoginResponse(int statusCode, String location, List<String> cookies, String body) {
    }
}
