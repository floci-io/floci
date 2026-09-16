package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MarketplaceDiscoveryIntegrationTest {
    @Inject
    StorageFactory storageFactory;

    @Inject
    ObjectMapper mapper;

    private AccountAwareStorageBackend<JsonNode> entities;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void setUp() {
        entities = storageFactory.create("marketplace", "marketplace-entities.json",
                new TypeReference<Map<String, JsonNode>>() {});
        entities.clear();
    }

    @Test
    void storedCatalogProductIsDiscoverableThroughBuyerApis() {
        String productId = createProduct("Discovery product");

        given().contentType("application/json").header("Authorization", auth())
                .body("{\"productId\":\"" + productId + "\"}")
                .post("/2026-02-05/getProduct").then().statusCode(200)
                .body("productId", equalTo(productId)).body("productName", equalTo("Discovery product"));
        given().contentType("application/json").header("Authorization", auth())
                .body("{\"listingId\":\"" + productId + "\"}")
                .post("/2026-02-05/getListing").then().statusCode(200)
                .body("listingId", equalTo(productId)).body("publisher.displayName", notNullValue());
        given().contentType("application/json").header("Authorization", auth())
                .body("{\"searchText\":\"Discovery product\"}")
                .post("/2026-02-05/searchListings").then().statusCode(200)
                .body("totalResults", greaterThanOrEqualTo(1));
    }

    @Test
    void unsupportedDiscoveryRegionIsRejected() {
        given().contentType("application/json").header("Authorization", auth("ap-southeast-2"))
                .body("{\"searchText\":\"anything\"}")
                .post("/2026-02-05/searchListings").then().statusCode(400);
    }

    private String createProduct(String title) {
        String productId = "prod-discovery-local";
        ObjectNode entity = mapper.createObjectNode();
        entity.put("EntityType", "SaaSProduct@1.0");
        entity.put("EntityIdentifier", productId);
        entity.put("EntityId", productId);
        entity.put("EntityArn", "arn:aws:aws-marketplace:us-east-1:000000000000:AWSMarketplace/SaaSProduct/" + productId);
        entity.put("LastModifiedDate", Instant.now().toString());
        ObjectNode details = entity.putObject("DetailsDocument");
        details.put("ProductTitle", title);
        details.put("ShortDescription", "Local product");
        details.put("FulfillmentType", "SAAS");
        entities.put(productId, entity);
        return productId;
    }

    private static String auth() {
        return auth("us-east-1");
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260908/" + region
                + "/aws-marketplace/aws4_request";
    }
}
