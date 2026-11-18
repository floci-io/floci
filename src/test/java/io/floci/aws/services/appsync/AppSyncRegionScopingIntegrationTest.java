package io.floci.aws.services.appsync;

import io.floci.aws.testing.AppSyncRequests;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * A GraphQL API and a custom domain name are Regional, as on AWS: each is listed and reached only in the Region it was
 * created in, and so are an API's data sources and API keys. Another Region's is answered for as one that doesn't
 * exist: {@code NotFoundException}, and an empty list of its resources. Each test works in an account of its own.
 */
@QuarkusTest
class AppSyncRegionScopingIntegrationTest {

    private static final AtomicLong ACCOUNTS = new AtomicLong(733100000000L);
    private static final String HOME = "eu-west-1";
    private static final String OTHER = "eu-central-1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private String account;

    @BeforeEach
    void accountOfItsOwn() {
        account = Long.toString(ACCOUNTS.incrementAndGet());
    }

    @Test
    void anApiAndItsResourcesAreListedAndReachedOnlyInItsRegion() {
        String apiId = in(HOME).body("{\"name\":\"orders\",\"authenticationType\":\"API_KEY\"}").post("/v1/apis")
                .then().statusCode(200).body("graphqlApi.arn", startsWith("arn:aws:appsync:" + HOME + ":"))
                .extract().path("graphqlApi.apiId");
        cleanup.register(() -> in(HOME).delete("/v1/apis/" + apiId));
        String apis = "/v1/apis/" + apiId;
        in(HOME).body("{\"name\":\"none_ds\",\"type\":\"NONE\"}").post(apis + "/datasources").then().statusCode(200);
        String keyId = in(HOME).body("{}").post(apis + "/apikeys").then().statusCode(200).extract().path("apiKey.id");

        in(OTHER).get("/v1/apis").then().statusCode(200).body("graphqlApis.apiId", not(hasItem(apiId)));
        notFound(in(OTHER).get(apis));
        in(OTHER).get(apis + "/datasources").then().statusCode(200).body("dataSources", empty());
        notFound(in(OTHER).get(apis + "/datasources/none_ds"));
        notFound(in(OTHER).body("{\"name\":\"other_ds\",\"type\":\"NONE\"}").post(apis + "/datasources"));
        in(OTHER).get(apis + "/apikeys").then().statusCode(200).body("apiKeys", empty());
        notFound(in(OTHER).delete(apis));

        in(HOME).get("/v1/apis").then().statusCode(200).body("graphqlApis.apiId", hasItem(apiId));
        in(HOME).get(apis + "/datasources/none_ds").then().statusCode(200);
        in(HOME).get(apis + "/apikeys").then().statusCode(200).body("apiKeys.id", hasItem(keyId));
    }

    @Test
    void aDomainNameIsListedAndReachedOnlyInItsRegion() {
        String name = "api-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        in(HOME).body("{\"domainName\":\"" + name + "\",\"certificateArn\":\"arn:aws:acm:us-east-1:" + account
                + ":certificate/abc\"}").post("/v1/domainnames").then().statusCode(200)
                .body("domainNameConfig.domainNameArn", startsWith("arn:aws:appsync:" + HOME + ":"));
        cleanup.register(() -> in(HOME).delete("/v1/domainnames/" + name));

        in(OTHER).get("/v1/domainnames").then().statusCode(200)
                .body("domainNameConfigs.domainName", not(hasItem(name)));
        notFound(in(OTHER).get("/v1/domainnames/" + name));
        notFound(in(OTHER).delete("/v1/domainnames/" + name));

        in(HOME).get("/v1/domainnames").then().statusCode(200).body("domainNameConfigs.domainName", hasItem(name));
        in(HOME).get("/v1/domainnames/" + name).then().statusCode(200).body("domainNameConfig.domainName", equalTo(name));
    }

    private RequestSpecification in(String region) {
        return AppSyncRequests.signed(account, region);
    }

    private static void notFound(Response response) {
        response.then().statusCode(404).body("__type", equalTo("NotFoundException"));
    }
}
