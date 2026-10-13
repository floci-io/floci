package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

@QuarkusTest
class ElbV2SubnetDependencyIntegrationTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111122223333";

    @InjectSpy
    Ec2Service ec2Service;

    @InjectSpy
    ElbV2Service elbService;

    @ParameterizedTest
    @ValueSource(strings = {"application", "network"})
    void loadBalancerPinsSubnetsUntilDeletedButTargetGroupDoesNot(String type) {
        String vpc = vpc();
        String first = subnet(vpc, "10.92.1.0/24", "a");
        String second = subnet(vpc, "10.92.2.0/24", "b");
        String unused = subnet(vpc, "10.92.3.0/24", "c");
        String arn = loadBalancer(type, first, second);
        String targetGroup = elb("CreateTargetGroup", "Name", name(), "Protocol", "HTTP", "Port", "80",
                "VpcId", vpc).statusCode(200).extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        try {
            ec2("DeleteSubnet", "SubnetId", first).statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("DependencyViolation"));
            ec2("DeleteSubnet", "SubnetId", second).statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("DependencyViolation"));
            ec2("DeleteVpc", "VpcId", vpc).statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("DependencyViolation"));
            elb("DescribeLoadBalancers", "LoadBalancerArns.member.1", arn).statusCode(200)
                    .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member"
                            + ".AvailabilityZones.member.SubnetId", hasItems(first, second));
            ec2("DeleteSubnet", "SubnetId", unused).statusCode(200);
        } finally {
            elb("DeleteLoadBalancer", "LoadBalancerArn", arn).statusCode(200);
        }
        ec2("DeleteSubnet", "SubnetId", first).statusCode(200);
        ec2("DeleteSubnet", "SubnetId", second).statusCode(200);
        ec2("DeleteVpc", "VpcId", vpc).statusCode(200);
        elb("DescribeTargetGroups", "TargetGroupArns.member.1", targetGroup).statusCode(200);
        elb("DeleteTargetGroup", "TargetGroupArn", targetGroup).statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application", "network"})
    void changingSubnetsReleasesOnlyTheOldReferences(String type) {
        String vpc = vpc();
        String first = subnet(vpc, "10.92.1.0/24", "a");
        String second = subnet(vpc, "10.92.2.0/24", "b");
        String replacement = subnet(vpc, "10.92.3.0/24", "c");
        String arn = loadBalancer(type, first, second);
        try {
            elb("SetSubnets", "LoadBalancerArn", arn, "Subnets.member.1", first,
                    "Subnets.member.2", replacement).statusCode(200);
            ec2("DeleteSubnet", "SubnetId", second).statusCode(200);
            ec2("DeleteSubnet", "SubnetId", replacement).statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("DependencyViolation"));
        } finally {
            elb("DeleteLoadBalancer", "LoadBalancerArn", arn).statusCode(200);
        }
        ec2("DeleteSubnet", "SubnetId", first).statusCode(200);
        ec2("DeleteSubnet", "SubnetId", replacement).statusCode(200);
        ec2("DeleteVpc", "VpcId", vpc).statusCode(200);
    }

    /**
     * The other account is fresh for each run, since deleting its default subnet is permanent for
     * the rest of the suite's shared application.
     */
    @Test
    void referencesInAnotherAccountDoNotPinTheSameDefaultSubnetId() {
        String otherAccount = Long.toString(ThreadLocalRandom.current().nextLong(100_000_000_000L, 1_000_000_000_000L));
        String first = Ec2Service.defaultSubnetId(REGION, "a");
        String second = Ec2Service.defaultSubnetId(REGION, "b");
        String arn = loadBalancer("application", first, second);
        try {
            request("ec2", otherAccount, REGION, "DeleteSubnet", "SubnetId", first).statusCode(200);
            ec2("DeleteSubnet", "SubnetId", first).statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("DependencyViolation"));
        } finally {
            elb("DeleteLoadBalancer", "LoadBalancerArn", arn).statusCode(200);
        }
    }

    /**
     * A load balancer's subnets keep its VPC from being deleted anyway, so the VPC check is driven
     * directly: it stands for state such as a balancer stored before subnets were protected, whose
     * subnets could already be gone.
     */
    @Test
    void aVpcStillReferencedByALoadBalancerCannotBeDeletedEvenWithoutSubnets() {
        String vpc = vpc();
        doReturn(true).when(elbService).hasVpcDependency(REGION, vpc);
        try {
            ec2("DeleteVpc", "VpcId", vpc).statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("DependencyViolation"));
        } finally {
            reset(elbService);
        }
        ec2("DeleteVpc", "VpcId", vpc).statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionWaitsForCreateOrSetSubnetsToPublishItsReferences(boolean reassign) throws Exception {
        String vpc = vpc();
        String first = subnet(vpc, "10.92.1.0/24", "a");
        String second = subnet(vpc, "10.92.2.0/24", "b");
        String replacement = subnet(vpc, "10.92.3.0/24", "c");
        String existing = reassign ? loadBalancer("application", first, second) : null;
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        CountDownLatch deleting = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            List<Subnet> found = (List<Subnet>) invocation.callRealMethod();
            if (((List<?>) invocation.getArgument(1)).contains(replacement) && pauseOnce.getAndSet(false)) {
                validated.countDown();
                assertTrue(publish.await(10, TimeUnit.SECONDS));
            }
            return found;
        }).when(ec2Service).describeSubnets(eq(REGION), anyList(), anyMap());
        doAnswer(invocation -> {
            deleting.countDown();
            return invocation.callRealMethod();
        }).when(ec2Service).deleteSubnet(REGION, replacement);
        String arn = existing;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> attach = executor.submit(() -> {
                if (reassign) {
                    elb("SetSubnets", "LoadBalancerArn", existing, "Subnets.member.1", first,
                            "Subnets.member.2", replacement).statusCode(200);
                    return existing;
                }
                return loadBalancer("application", first, replacement);
            });
            try {
                assertTrue(validated.await(10, TimeUnit.SECONDS));
                Future<ValidatableResponse> deletion = executor.submit(
                        () -> ec2("DeleteSubnet", "SubnetId", replacement));
                assertTrue(deleting.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> deletion.get(200, TimeUnit.MILLISECONDS));
                publish.countDown();
                arn = attach.get(10, TimeUnit.SECONDS);
                deletion.get(10, TimeUnit.SECONDS).statusCode(400)
                        .body("Response.Errors.Error.Code", equalTo("DependencyViolation"));
            } finally {
                publish.countDown();
            }
        } finally {
            reset(ec2Service);
            if (arn != null) {
                elb("DeleteLoadBalancer", "LoadBalancerArn", arn).statusCode(200);
            }
        }
        ec2("DeleteSubnet", "SubnetId", first).statusCode(200);
        ec2("DeleteSubnet", "SubnetId", second).statusCode(200);
        ec2("DeleteSubnet", "SubnetId", replacement).statusCode(200);
        ec2("DeleteVpc", "VpcId", vpc).statusCode(200);
    }

    @Test
    void createCannotPublishAReferenceAfterSubnetDeletionWins() throws Exception {
        String vpc = vpc();
        String first = subnet(vpc, "10.92.1.0/24", "a");
        String second = subnet(vpc, "10.92.2.0/24", "b");
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch delete = new CountDownLatch(1);
        CountDownLatch creating = new CountDownLatch(1);
        doAnswer(invocation -> {
            boolean dependent = (boolean) invocation.callRealMethod();
            checked.countDown();
            assertTrue(delete.await(10, TimeUnit.SECONDS));
            return dependent;
        }).when(elbService).hasSubnetDependency(REGION, first);
        doAnswer(invocation -> {
            creating.countDown();
            return invocation.callRealMethod();
        }).when(elbService).createLoadBalancer(eq(REGION), anyString(),
                any(), eq("application"), any(),
                anyList(), any(), anyMap());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ValidatableResponse> deletion = executor.submit(() -> ec2("DeleteSubnet", "SubnetId", first));
            try {
                assertTrue(checked.await(10, TimeUnit.SECONDS));
                Future<ValidatableResponse> creation = executor.submit(() -> elb("CreateLoadBalancer", "Name", name(),
                        "Type", "application", "Subnets.member.1", first, "Subnets.member.2", second));
                assertTrue(creating.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> creation.get(200, TimeUnit.MILLISECONDS));
                delete.countDown();
                deletion.get(10, TimeUnit.SECONDS).statusCode(200);
                creation.get(10, TimeUnit.SECONDS).statusCode(400)
                        .body("ErrorResponse.Error.Code", equalTo("SubnetNotFound"));
            } finally {
                delete.countDown();
            }
        } finally {
            reset(elbService);
        }
        ec2("DeleteSubnet", "SubnetId", second).statusCode(200);
        ec2("DeleteVpc", "VpcId", vpc).statusCode(200);
    }

    private String vpc() {
        return ec2("CreateVpc", "CidrBlock", "10.92.0.0/16").statusCode(200).extract()
                .path("CreateVpcResponse.vpc.vpcId");
    }

    private String subnet(String vpc, String cidr, String zone) {
        return ec2("CreateSubnet", "VpcId", vpc, "CidrBlock", cidr,
                "AvailabilityZone", REGION + zone).statusCode(200).extract()
                .path("CreateSubnetResponse.subnet.subnetId");
    }

    private String loadBalancer(String type, String first, String second) {
        return elb("CreateLoadBalancer", "Name", name(), "Type", type,
                "Subnets.member.1", first, "Subnets.member.2", second).statusCode(200).extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    private static String name() {
        return "subnet-" + UUID.randomUUID().toString().substring(0, 20);
    }

    private ValidatableResponse ec2(String action, String... values) {
        return request("ec2", ACCOUNT, REGION, action, values);
    }

    private ValidatableResponse elb(String action, String... values) {
        return request("elasticloadbalancing", ACCOUNT, REGION, action, values);
    }

    private ValidatableResponse request(String service, String account, String region,
                                       String action, String... values) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("Action", action);
        for (int i = 0; i < values.length; i += 2) {
            parameters.put(values[i], values[i + 1]);
        }
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20261011/" + region + "/" + service + "/aws4_request")
                .formParams(parameters).when().post("/").then();
    }
}
