package io.floci.aws.services.ec2;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ec2.model.InstanceOperator;
import io.floci.aws.testing.Ec2Requests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A managed instance, one a service provider such as EKS Auto Mode runs for the account, is hidden from list and
 * filter calls by default, as on AWS: a plain DescribeInstances or DescribeInstanceStatus leaves it out,
 * {@code IncludeManagedResources} shows it with its {@code operator}, and naming it by ID returns it either way.
 */
@QuarkusTest
class Ec2ManagedInstanceVisibilityIntegrationTest {

    private static final String REGION = "us-east-1";

    @Inject
    Ec2Service service;

    private final String account = String.valueOf(ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L));
    private String managed;
    private String plain;

    @BeforeEach
    void launch() {
        managed = run();
        plain = run();
        RequestScopes.runAs(account, REGION, () -> service.setInstanceOperator(REGION, managed,
                new InstanceOperator(true, "eks.amazonaws.com", true)));
    }

    @AfterEach
    void terminate() {
        RequestScopes.runAs(account, REGION, () -> service.terminateInstances(REGION, List.of(managed, plain)));
    }

    @Test
    void aPlainListLeavesAHiddenManagedInstanceOutAndIncludeManagedResourcesShowsItsOperator() {
        assertEquals(List.of(plain), instanceIds(describe("DescribeInstances", null)));

        XmlPath all = describe("DescribeInstances", "true");

        assertEquals(Set.of(managed, plain), Set.copyOf(instanceIds(all)));
        String operator = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.find { it.instanceId == '"
                + managed + "' }.operator";
        assertEquals("true", all.getString(operator + ".managed"));
        assertEquals("eks.amazonaws.com", all.getString(operator + ".principal"));
        assertEquals("true", all.getString(operator + ".hiddenByDefault"));
    }

    @Test
    void aFilterLeavesItOutTooButNamingItByIdReturnsIt() {
        XmlPath filtered = Ec2Requests.ec2("DescribeInstances", account, REGION)
                .formParam("Filter.1.Name", "operator.managed").formParam("Filter.1.Value.1", "true")
                .when().post("/").then().statusCode(200).extract().xmlPath();
        XmlPath filteredIncluded = Ec2Requests.ec2("DescribeInstances", account, REGION)
                .formParam("Filter.1.Name", "operator.principal").formParam("Filter.1.Value.1", "eks.amazonaws.com")
                .formParam("IncludeManagedResources", "true")
                .when().post("/").then().statusCode(200).extract().xmlPath();
        XmlPath byId = Ec2Requests.ec2("DescribeInstances", account, REGION).formParam("InstanceId.1", managed)
                .when().post("/").then().statusCode(200).extract().xmlPath();

        assertEquals(List.of(), instanceIds(filtered));
        assertEquals(List.of(managed), instanceIds(filteredIncluded));
        assertEquals(List.of(managed), instanceIds(byId));
    }

    @Test
    void describeInstanceStatusFollowsTheSameRule() {
        List<String> plainStatus = describe("DescribeInstanceStatus", null)
                .getList("DescribeInstanceStatusResponse.instanceStatusSet.item.instanceId");
        List<String> allStatus = describe("DescribeInstanceStatus", "true")
                .getList("DescribeInstanceStatusResponse.instanceStatusSet.item.instanceId");

        assertEquals(List.of(plain), plainStatus);
        assertEquals(2, allStatus.size());
    }

    @Test
    void anIncludeManagedResourcesThatIsntABooleanIsRefused() {
        for (String value : List.of("maybe", " ")) {
            Ec2Requests.ec2("DescribeInstances", account, REGION).formParam("IncludeManagedResources", value)
                    .when().post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        }
    }

    private String run() {
        return Ec2Requests.ec2("RunInstances", account, REGION)
                .formParam("ImageId", "ami-0abcdef1234567891").formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1")
                .when().post("/").then().statusCode(200).extract().xmlPath()
                .getString("RunInstancesResponse.instancesSet.item[0].instanceId");
    }

    private XmlPath describe(String action, String includeManaged) {
        RequestSpecification request = Ec2Requests.ec2(action, account, REGION);
        if (includeManaged != null) {
            request.formParam("IncludeManagedResources", includeManaged);
        }
        return request.when().post("/").then().statusCode(200).extract().xmlPath();
    }

    private static List<String> instanceIds(XmlPath response) {
        return response.getList("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId");
    }
}
