package io.github.hectorvent.floci.services.ce;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class CostAnomalyIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String REGION = "us-east-1";
    private static final Map<String, Object> THRESHOLD_EXPRESSION = Map.of("Dimensions", Map.of(
            "Key", "ANOMALY_TOTAL_IMPACT_ABSOLUTE", "Values", List.of("100"),
            "MatchOptions", List.of("GREATER_THAN_OR_EQUAL")));

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void monitorAndSubscriptionLifecyclePreservesSettingsDuringPartialUpdates() {
        String account = "941000000001";
        String monitorArn = request(account, REGION, "CreateAnomalyMonitor", Map.of(
                "AnomalyMonitor", Map.of("MonitorName", "service-costs", "MonitorType", "DIMENSIONAL",
                        "MonitorDimension", "SERVICE"),
                "ResourceTags", List.of(Map.of("Key", "Environment", "Value", "test"))))
                .statusCode(200)
                .body("MonitorArn", startsWith("arn:aws:ce::" + account + ":anomalymonitor/"))
                .extract().path("MonitorArn");

        request(account, REGION, "GetAnomalyMonitors", Map.of("MonitorArnList", List.of(monitorArn)))
                .statusCode(200)
                .body("AnomalyMonitors", hasSize(1))
                .body("AnomalyMonitors[0].MonitorArn", equalTo(monitorArn))
                .body("AnomalyMonitors[0].MonitorName", equalTo("service-costs"))
                .body("AnomalyMonitors[0].MonitorType", equalTo("DIMENSIONAL"))
                .body("AnomalyMonitors[0].MonitorDimension", equalTo("SERVICE"));

        request(account, REGION, "UpdateAnomalyMonitor", Map.of(
                "MonitorArn", monitorArn, "MonitorName", "renamed-service-costs")).statusCode(200);
        request(account, REGION, "GetAnomalyMonitors", Map.of("MonitorArnList", List.of(monitorArn)))
                .statusCode(200)
                .body("AnomalyMonitors[0].MonitorName", equalTo("renamed-service-costs"))
                .body("AnomalyMonitors[0].MonitorDimension", equalTo("SERVICE"));

        String subscriptionArn = request(account, REGION, "CreateAnomalySubscription", Map.of(
                "AnomalySubscription", subscription("daily-cost-alerts", monitorArn),
                "ResourceTags", List.of(Map.of("Key", "Environment", "Value", "test"))))
                .statusCode(200)
                .body("SubscriptionArn", startsWith("arn:aws:ce::" + account + ":anomalysubscription/"))
                .extract().path("SubscriptionArn");

        for (String resourceArn : List.of(monitorArn, subscriptionArn)) {
            request(account, REGION, "ListTagsForResource", Map.of("ResourceArn", resourceArn))
                    .statusCode(200)
                    .body("ResourceTags", hasSize(1))
                    .body("ResourceTags[0].Key", equalTo("Environment"))
                    .body("ResourceTags[0].Value", equalTo("test"));
            request(account, REGION, "TagResource", Map.of("ResourceArn", resourceArn,
                    "ResourceTags", List.of(Map.of("Key", "Environment", "Value", "production"),
                            Map.of("Key", "Owner", "Value", "billing"))))
                    .statusCode(200).body(equalTo("{}"));
            request(account, REGION, "ListTagsForResource", Map.of("ResourceArn", resourceArn))
                    .statusCode(200)
                    .body("ResourceTags", hasSize(2))
                    .body("ResourceTags.find { it.Key == 'Environment' }.Value", equalTo("production"))
                    .body("ResourceTags.find { it.Key == 'Owner' }.Value", equalTo("billing"));
            request(account, REGION, "UntagResource", Map.of("ResourceArn", resourceArn,
                    "ResourceTagKeys", List.of("Owner"))).statusCode(200).body(equalTo("{}"));
            request(account, REGION, "ListTagsForResource", Map.of("ResourceArn", resourceArn))
                    .statusCode(200)
                    .body("ResourceTags.Key", contains("Environment"));
        }

        request(account, REGION, "UpdateAnomalySubscription", Map.of(
                "SubscriptionArn", subscriptionArn, "SubscriptionName", "renamed-alerts")).statusCode(200);
        request(account, REGION, "GetAnomalySubscriptions", Map.of(
                "SubscriptionArnList", List.of(subscriptionArn), "MonitorArn", monitorArn))
                .statusCode(200)
                .body("AnomalySubscriptions", hasSize(1))
                .body("AnomalySubscriptions[0].SubscriptionName", equalTo("renamed-alerts"))
                .body("AnomalySubscriptions[0].SubscriptionArn", equalTo(subscriptionArn))
                .body("AnomalySubscriptions[0].AccountId", equalTo(account))
                .body("AnomalySubscriptions[0].Frequency", equalTo("DAILY"))
                .body("AnomalySubscriptions[0].MonitorArnList", contains(monitorArn))
                .body("AnomalySubscriptions[0].Subscribers[0].Address", equalTo("alerts@example.com"))
                .body("AnomalySubscriptions[0].Subscribers[0].Type", equalTo("EMAIL"))
                .body("AnomalySubscriptions[0].ThresholdExpression.Dimensions", equalTo(
                        THRESHOLD_EXPRESSION.get("Dimensions")));

        request(account, REGION, "UpdateAnomalySubscription", Map.of(
                "SubscriptionArn", subscriptionArn, "Frequency", "WEEKLY",
                "Subscribers", List.of(Map.of("Address", "weekly@example.com", "Type", "EMAIL"))))
                .statusCode(200);
        request(account, REGION, "GetAnomalySubscriptions", Map.of("SubscriptionArnList", List.of(subscriptionArn)))
                .statusCode(200)
                .body("AnomalySubscriptions[0].SubscriptionName", equalTo("renamed-alerts"))
                .body("AnomalySubscriptions[0].Frequency", equalTo("WEEKLY"))
                .body("AnomalySubscriptions[0].Subscribers[0].Address", equalTo("weekly@example.com"))
                .body("AnomalySubscriptions[0].MonitorArnList", contains(monitorArn));

        request(account, REGION, "DeleteAnomalySubscription", Map.of("SubscriptionArn", subscriptionArn))
                .statusCode(200).body(equalTo("{}"));
        request(account, REGION, "DeleteAnomalyMonitor", Map.of("MonitorArn", monitorArn))
                .statusCode(200).body(equalTo("{}"));
        request(account, REGION, "GetAnomalySubscriptions", Map.of())
                .statusCode(200).body("AnomalySubscriptions", hasSize(0));
        request(account, REGION, "GetAnomalyMonitors", Map.of())
                .statusCode(200).body("AnomalyMonitors", hasSize(0));
    }

    @Test
    void resourcesAreAccountScopedAndSharedAcrossRequestRegions() {
        String firstAccount = "941000000002";
        String secondAccount = "941000000003";
        String firstMonitor = createMonitor(firstAccount, "same-name", false);
        String secondMonitor = createMonitor(secondAccount, "same-name", false);
        String firstSubscription = createSubscription(firstAccount, "same-name", firstMonitor);
        String secondSubscription = createSubscription(secondAccount, "same-name", secondMonitor);

        request(firstAccount, "eu-west-1", "GetAnomalyMonitors", Map.of())
                .statusCode(200).body("AnomalyMonitors.MonitorArn", contains(firstMonitor));
        request(firstAccount, "eu-west-1", "GetAnomalySubscriptions", Map.of())
                .statusCode(200).body("AnomalySubscriptions.SubscriptionArn", contains(firstSubscription));
        request(secondAccount, REGION, "GetAnomalyMonitors", Map.of())
                .statusCode(200).body("AnomalyMonitors.MonitorArn", contains(secondMonitor));
        request(secondAccount, REGION, "GetAnomalySubscriptions", Map.of())
                .statusCode(200).body("AnomalySubscriptions.SubscriptionArn", contains(secondSubscription));
        request(secondAccount, REGION, "GetAnomalyMonitors", Map.of("MonitorArnList", List.of(firstMonitor)))
                .statusCode(400).body("__type", equalTo("UnknownMonitorException"));

        request(firstAccount, "eu-west-1", "UpdateAnomalyMonitor", Map.of(
                "MonitorArn", firstMonitor, "MonitorName", "renamed-first-account")).statusCode(200);
        request(firstAccount, REGION, "GetAnomalyMonitors", Map.of())
                .statusCode(200).body("AnomalyMonitors[0].MonitorName", equalTo("renamed-first-account"));
        request(secondAccount, REGION, "GetAnomalyMonitors", Map.of())
                .statusCode(200).body("AnomalyMonitors[0].MonitorName", equalTo("same-name"));

        request(firstAccount, REGION, "DeleteAnomalySubscription", Map.of("SubscriptionArn", firstSubscription))
                .statusCode(200);
        request(secondAccount, REGION, "DeleteAnomalySubscription", Map.of("SubscriptionArn", secondSubscription))
                .statusCode(200);
        request(firstAccount, REGION, "DeleteAnomalyMonitor", Map.of("MonitorArn", firstMonitor)).statusCode(200);
        request(secondAccount, REGION, "DeleteAnomalyMonitor", Map.of("MonitorArn", secondMonitor)).statusCode(200);
    }

    @Test
    void listOperationsPageWithoutOmittingOrRepeatingResources() {
        String account = "941000000004";
        String firstMonitor = createMonitor(account, "first-custom", true);
        String secondMonitor = createMonitor(account, "second-custom", true);
        String firstSubscription = createSubscription(account, "first-alert", firstMonitor);
        String secondSubscription = createSubscription(account, "second-alert", firstMonitor);

        ValidatableResponse monitorsPage = request(account, REGION, "GetAnomalyMonitors", Map.of("MaxResults", 1))
                .statusCode(200).body("AnomalyMonitors", hasSize(1)).body("NextPageToken", notNullValue())
                .body("AnomalyMonitors[0].MonitorSpecification.Dimensions.Key", equalTo("LINKED_ACCOUNT"))
                .body("AnomalyMonitors[0].MonitorSpecification.Dimensions.Values", contains("111111111111"));
        String monitorToken = monitorsPage.extract().path("NextPageToken");
        String firstPageMonitor = monitorsPage.extract().path("AnomalyMonitors[0].MonitorArn");
        String secondPageMonitor = request(account, REGION, "GetAnomalyMonitors", Map.of(
                "MaxResults", 1, "NextPageToken", monitorToken))
                .statusCode(200).body("AnomalyMonitors", hasSize(1)).body("NextPageToken", nullValue())
                .extract().path("AnomalyMonitors[0].MonitorArn");
        assertThat(List.of(firstPageMonitor, secondPageMonitor), containsInAnyOrder(firstMonitor, secondMonitor));

        ValidatableResponse subscriptionsPage = request(account, REGION, "GetAnomalySubscriptions", Map.of(
                "MonitorArn", firstMonitor, "MaxResults", 1))
                .statusCode(200).body("AnomalySubscriptions", hasSize(1)).body("NextPageToken", notNullValue());
        String subscriptionToken = subscriptionsPage.extract().path("NextPageToken");
        String firstPageSubscription = subscriptionsPage.extract().path("AnomalySubscriptions[0].SubscriptionArn");
        String secondPageSubscription = request(account, REGION, "GetAnomalySubscriptions", Map.of(
                "MonitorArn", firstMonitor, "MaxResults", 1, "NextPageToken", subscriptionToken))
                .statusCode(200).body("AnomalySubscriptions", hasSize(1)).body("NextPageToken", nullValue())
                .extract().path("AnomalySubscriptions[0].SubscriptionArn");
        assertThat(List.of(firstPageSubscription, secondPageSubscription),
                containsInAnyOrder(firstSubscription, secondSubscription));
        request(account, REGION, "GetAnomalySubscriptions", Map.of("MonitorArn", secondMonitor))
                .statusCode(200).body("AnomalySubscriptions", hasSize(0));

        request(account, REGION, "DeleteAnomalySubscription", Map.of("SubscriptionArn", firstSubscription))
                .statusCode(200);
        request(account, REGION, "DeleteAnomalySubscription", Map.of("SubscriptionArn", secondSubscription))
                .statusCode(200);
        request(account, REGION, "DeleteAnomalyMonitor", Map.of("MonitorArn", firstMonitor)).statusCode(200);
        request(account, REGION, "DeleteAnomalyMonitor", Map.of("MonitorArn", secondMonitor)).statusCode(200);
    }

    @Test
    void malformedDefinitionsAndUnknownMonitorsReturnAwsErrors() {
        String account = "941000000005";
        request(account, REGION, "CreateAnomalyMonitor", Map.of("AnomalyMonitor", Map.of(
                "MonitorType", "DIMENSIONAL", "MonitorDimension", "SERVICE")))
                .statusCode(400).body("__type", equalTo("ValidationException"));
        request(account, REGION, "CreateAnomalySubscription", Map.of("AnomalySubscription", Map.of(
                "SubscriptionName", "missing-settings")))
                .statusCode(400).body("__type", equalTo("ValidationException"));
        request(account, REGION, "GetAnomalyMonitors", Map.of("MonitorArnList", List.of(
                "arn:aws:ce::" + account + ":anomalymonitor/00000000-0000-0000-0000-000000000000")))
                .statusCode(400).body("__type", equalTo("UnknownMonitorException"));
    }

    private String createMonitor(String account, String name, boolean custom) {
        Map<String, Object> definition = custom
                ? Map.of("MonitorName", name, "MonitorType", "CUSTOM", "MonitorSpecification",
                        Map.of("Dimensions", Map.of("Key", "LINKED_ACCOUNT", "Values", List.of("111111111111"))))
                : Map.of("MonitorName", name, "MonitorType", "DIMENSIONAL", "MonitorDimension", "SERVICE");
        return request(account, REGION, "CreateAnomalyMonitor", Map.of("AnomalyMonitor", definition))
                .statusCode(200).extract().path("MonitorArn");
    }

    private String createSubscription(String account, String name, String monitorArn) {
        return request(account, REGION, "CreateAnomalySubscription", Map.of(
                "AnomalySubscription", subscription(name, monitorArn)))
                .statusCode(200).extract().path("SubscriptionArn");
    }

    private Map<String, Object> subscription(String name, String monitorArn) {
        return Map.of("SubscriptionName", name, "Frequency", "DAILY", "MonitorArnList", List.of(monitorArn),
                "Subscribers", List.of(Map.of("Address", "alerts@example.com", "Type", "EMAIL")),
                "ThresholdExpression", THRESHOLD_EXPRESSION);
    }

    private ValidatableResponse request(String account, String region, String action, Map<String, Object> body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSInsightsIndexService." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20260101/" + region + "/ce/aws4_request")
                .body(body)
                .when().post("/")
                .then();
    }
}
