package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A REST request is authorized as the route the router matched, whose path has its encoded dot segments
 * resolved, so a path written with {@code %2E%2E} is checked as the operation and resource it reaches.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class RoutedPathIamEnforcementIntegrationTest {

    private static final String ADMIN = "test";

    @TestHTTPResource("/")
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    /** Each user's access key ID, by user name. */
    private final Map<String, String> keys = new HashMap<>();

    @Test
    void aLambdaPathWithADotSegmentIsCheckedAsTheFunctionItReaches() throws Exception {
        String function = "routed-" + suffix();
        String user = user("routed-lambda");
        policy(user, allow("lambda:*", "*") + ","
                + deny("lambda:GetFunction", "arn:aws:lambda:*:*:function:" + function));

        for (String path : List.of(
                "/2015-03-31/functions/" + function,
                "/2015-03-31/functions/x/%2E%2E/" + function,
                "/2015-03-31/functions/" + function + "/x/%2E%2E",
                "/2015-03-31/functions/x//%2E%2E/%2E%2E/" + function)) {
            HttpResponse<String> response = get(user, "lambda", path);
            assertEquals(403, response.statusCode(), path + ": " + response.body());
            assertThat(response.body(), containsString("lambda:GetFunction"));
        }

        HttpResponse<String> other = get(user, "lambda", "/2015-03-31/functions/x/%2E%2E/other-" + function);
        assertEquals(404, other.statusCode(), other.body());
        assertThat(other.body(), containsString("Function not found: other-" + function));
    }

    /** Sends {@code path} as written: RestAssured and the AWS SDKs resolve its dot and empty segments first. */
    private HttpResponse<String> get(String user, String service, String path) throws Exception {
        URI uri = URI.create(baseUri.getScheme() + "://" + baseUri.getRawAuthority() + path);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(uri)
                            .header("Authorization", auth(keys.get(user), service)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String allow(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String deny(String action, String resource) {
        return "{\"Effect\":\"Deny\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    /** Replaces the user's one inline policy. */
    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ADMIN, "iam"), user, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private String user(String name) {
        String userName = name + "-" + suffix();
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"), userName)
                .accessKeyId();
        keys.put(userName, accessKeyId);
        return userName;
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
