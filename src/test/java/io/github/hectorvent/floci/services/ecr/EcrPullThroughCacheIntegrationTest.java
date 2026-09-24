package io.github.hectorvent.floci.services.ecr;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.parsing.Parser;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/** Create, Describe and Delete for an ECR pull through cache rule. */
@QuarkusTest
class EcrPullThroughCacheIntegrationTest {

    @BeforeAll
    static void readTheJson11ResponsesAsJson() {
        RestAssured.registerParser("application/x-amz-json-1.1", Parser.JSON);
    }

    private static Response call(String action, String body) {
        return given()
            .header("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921." + action)
            .contentType("application/x-amz-json-1.1")
            .body(body.getBytes(StandardCharsets.UTF_8))
        .when().post("/");
    }

    private static String prefix(String name) {
        return name + Long.toString(System.nanoTime(), 36).substring(0, 6);
    }

    @Test
    void createDescribeAndDeleteARule() {
        String p = prefix("dh");
        call("CreatePullThroughCacheRule", "{\"ecrRepositoryPrefix\":\"" + p + "\","
                + "\"upstreamRegistryUrl\":\"registry-1.docker.io\",\"upstreamRegistry\":\"docker-hub\"}")
            .then().statusCode(200)
            .body("ecrRepositoryPrefix", is(p))
            .body("upstreamRegistryUrl", is("registry-1.docker.io"))
            .body("upstreamRegistry", is("docker-hub"))
            .body("registryId", is("000000000000"));

        call("DescribePullThroughCacheRules", "{}")
            .then().statusCode(200)
            .body("pullThroughCacheRules.ecrRepositoryPrefix", hasItem(p));

        call("DescribePullThroughCacheRules", "{\"ecrRepositoryPrefixes\":[\"" + p + "\"]}")
            .then().statusCode(200)
            .body("pullThroughCacheRules.size()", is(1))
            .body("pullThroughCacheRules[0].upstreamRegistry", is("docker-hub"));

        call("DeletePullThroughCacheRule", "{\"ecrRepositoryPrefix\":\"" + p + "\"}")
            .then().statusCode(200)
            .body("ecrRepositoryPrefix", is(p));

        call("DescribePullThroughCacheRules", "{}")
            .then().statusCode(200)
            .body("pullThroughCacheRules.ecrRepositoryPrefix", not(hasItem(p)));
    }

    @Test
    void theUpstreamRegistryIsDerivedFromItsUrlWhenTheRequestOmitsIt() {
        String p = prefix("k8");
        call("CreatePullThroughCacheRule", "{\"ecrRepositoryPrefix\":\"" + p + "\","
                + "\"upstreamRegistryUrl\":\"registry.k8s.io\"}")
            .then().statusCode(200)
            .body("upstreamRegistry", is("k8s"));
    }

    @Test
    void theAssumedTrailingSlashIsNotStored() {
        String p = prefix("sl");
        call("CreatePullThroughCacheRule", "{\"ecrRepositoryPrefix\":\"" + p + "/\","
                + "\"upstreamRegistryUrl\":\"quay.io\"}")
            .then().statusCode(200)
            .body("ecrRepositoryPrefix", is(p));

        // The same prefix with and without the slash names one rule, so the second create clashes.
        call("CreatePullThroughCacheRule", "{\"ecrRepositoryPrefix\":\"" + p + "\","
                + "\"upstreamRegistryUrl\":\"quay.io\"}")
            .then().statusCode(400)
            .body("__type", is("PullThroughCacheRuleAlreadyExistsException"));
    }

    @Test
    void anUnmodelledUpstreamRegistryIsRefused() {
        call("CreatePullThroughCacheRule", "{\"ecrRepositoryPrefix\":\"" + prefix("bad") + "\","
                + "\"upstreamRegistryUrl\":\"example.com\",\"upstreamRegistry\":\"example\"}")
            .then().statusCode(400)
            .body("__type", is("UnsupportedUpstreamRegistryException"));
    }

    @Test
    void thePrefixIsBoundedAndPatterned() {
        call("CreatePullThroughCacheRule", "{\"ecrRepositoryPrefix\":\"a\","
                + "\"upstreamRegistryUrl\":\"quay.io\"}")
            .then().statusCode(400).body("__type", is("InvalidParameterException"));

        call("CreatePullThroughCacheRule", "{\"ecrRepositoryPrefix\":\"" + "a".repeat(31) + "\","
                + "\"upstreamRegistryUrl\":\"quay.io\"}")
            .then().statusCode(400).body("__type", is("InvalidParameterException"));

        call("CreatePullThroughCacheRule", "{\"ecrRepositoryPrefix\":\"Upper\","
                + "\"upstreamRegistryUrl\":\"quay.io\"}")
            .then().statusCode(400).body("__type", is("InvalidParameterException"));
    }

    @Test
    void deletingAndDescribingARuleThatIsNotThereIsNotFound() {
        call("DeletePullThroughCacheRule", "{\"ecrRepositoryPrefix\":\"absent-rule\"}")
            .then().statusCode(400)
            .body("__type", is("PullThroughCacheRuleNotFoundException"));

        call("DescribePullThroughCacheRules", "{\"ecrRepositoryPrefixes\":[\"absent-rule\"]}")
            .then().statusCode(400)
            .body("__type", is("PullThroughCacheRuleNotFoundException"));
    }

    @Test
    void upstreamRegistryIsAbsentForAUrlTheModelDoesNotName() {
        String p = prefix("cu");
        call("CreatePullThroughCacheRule", "{\"ecrRepositoryPrefix\":\"" + p + "\","
                + "\"upstreamRegistryUrl\":\"123456789012.dkr.ecr.us-east-1.amazonaws.com\"}")
            .then().statusCode(200)
            .body("upstreamRegistry", nullValue());
    }
}
