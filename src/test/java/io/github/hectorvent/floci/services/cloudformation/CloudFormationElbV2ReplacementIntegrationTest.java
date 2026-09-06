package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Replacing updates of ELBv2 resources through the whole engine: the displaced listener and target
 * group are deleted after the update commits, dependents first, and a replacement is rolled back
 * when a later resource fails the update.
 */
@QuarkusTest
class CloudFormationElbV2ReplacementIntegrationTest {

    private static final String REGION = "us-east-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /**
     * One update renames the target group and moves the listener to the second load balancer. The
     * old listener still forwards to the old target group, so the group can only go once the
     * listener is gone: the cleanup walks dependents first, and both displaced entities end up
     * deleted with DELETE_COMPLETE events on their prior ARNs.
     */
    @Test
    void aRenamedTargetGroupAndAMovedListenerAreBothCleanedUpAfterTheUpdateCommits() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "elb-replace-" + suffix;

        createStack(stackName, template(suffix, "AlbA", "tg-a-" + suffix, ""));
        String describeBefore = describeStack(stackName);
        String oldListener = output(describeBefore, "ListenerRef");
        String oldTg = output(describeBefore, "TgRef");

        updateStack(stackName, template(suffix, "AlbB", "tg-b-" + suffix, ""));
        String describeAfter = describeStack(stackName);
        assertStatus(describeAfter, "UPDATE_COMPLETE");
        String newListener = output(describeAfter, "ListenerRef");
        String newTg = output(describeAfter, "TgRef");
        assertNotEquals(oldListener, newListener);
        assertNotEquals(oldTg, newTg);

        assertListenerMissing(oldListener);
        assertTargetGroupMissing(oldTg);
        assertListenerPresent(newListener);

        String events = describeEvents(stackName);
        assertEvent(events, "DELETE_COMPLETE", oldListener);
        assertEvent(events, "DELETE_COMPLETE", oldTg);

        deleteStack(stackName);
    }

    /**
     * The listener is moved, then a later resource fails, so the update rolls back: the listener
     * resource names the prior listener again and the replacement is gone.
     */
    @Test
    void aFailedUpdateRollsAMovedListenerBackAndDeletesTheReplacement() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "elb-rollback-" + suffix;

        createStack(stackName, template(suffix, "AlbA", "tg-" + suffix, ""));
        String oldListener = output(describeStack(stackName), "ListenerRef");

        String failingSecret = """
            ,
                "BadSecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "DependsOn": "Listener",
                  "Properties": {
                    "Name": "elb-rollback-secret-%s",
                    "SecretString": "explicit",
                    "GenerateSecretString": {"PasswordLength": 32}
                  }
                }""".formatted(suffix);
        updateStack(stackName, template(suffix, "AlbB", "tg-" + suffix, failingSecret));

        String describe = describeStack(stackName);
        assertStatus(describe, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(oldListener, output(describe, "ListenerRef"));
        assertListenerPresent(oldListener);

        String listeners = given()
            .formParam("Action", "DescribeListeners")
            .formParam("Version", "2015-12-01")
            .formParam("LoadBalancerArn", output(describe, "AlbBRef"))
        .when().post("/")
        .then().statusCode(200).extract().asString();
        assertEquals(false, listeners.contains("<ListenerArn>"),
                "the replacement listener on the second balancer is gone: " + listeners);

        deleteStack(stackName);
    }

    /**
     * The target group arrives in a later update than the listener, so it sits after the listener
     * in the stack's resource map while the listener depends on it. Cleanup has to follow the
     * template's dependency order, not the map's: reversing the map would delete the old target
     * group first, while the old listener still forwards to it.
     */
    @Test
    void aDependencyAddedByALaterUpdateIsStillCleanedUpAfterItsDependent() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "elb-appended-" + suffix;

        createStack(stackName, fixedResponseTemplate(suffix));
        updateStack(stackName, template(suffix, "AlbA", "tg-a-" + suffix, ""));
        String describeBefore = describeStack(stackName);
        assertStatus(describeBefore, "UPDATE_COMPLETE");
        String oldListener = output(describeBefore, "ListenerRef");
        String oldTg = output(describeBefore, "TgRef");

        updateStack(stackName, template(suffix, "AlbB", "tg-b-" + suffix, ""));
        String describeAfter = describeStack(stackName);
        assertStatus(describeAfter, "UPDATE_COMPLETE");
        assertNotEquals(oldListener, output(describeAfter, "ListenerRef"));
        assertNotEquals(oldTg, output(describeAfter, "TgRef"));

        assertListenerMissing(oldListener);
        assertTargetGroupMissing(oldTg);
        String events = describeEvents(stackName);
        assertEvent(events, "DELETE_COMPLETE", oldListener);
        assertEvent(events, "DELETE_COMPLETE", oldTg);

        deleteStack(stackName);
    }

    /** The same two balancers with a listener that needs no target group. */
    private static String fixedResponseTemplate(String suffix) {
        return """
            {
              "Resources": {
                "AlbA": {
                  "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties": {"Name": "alb-a-%1$s", "Type": "application", "Subnets": ["%2$s", "%3$s"]}
                },
                "AlbB": {
                  "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties": {"Name": "alb-b-%1$s", "Type": "application", "Subnets": ["%2$s", "%3$s"]}
                },
                "Listener": {
                  "Type": "AWS::ElasticLoadBalancingV2::Listener",
                  "Properties": {
                    "LoadBalancerArn": {"Ref": "AlbA"},
                    "Protocol": "HTTP",
                    "Port": 80,
                    "DefaultActions": [{"Type": "fixed-response", "FixedResponseConfig": {"StatusCode": "404"}}]
                  }
                }
              },
              "Outputs": {
                "AlbARef": {"Value": {"Ref": "AlbA"}},
                "AlbBRef": {"Value": {"Ref": "AlbB"}},
                "ListenerRef": {"Value": {"Ref": "Listener"}}
              }
            }
            """.formatted(suffix, Ec2Service.defaultSubnetId(REGION, "a"), Ec2Service.defaultSubnetId(REGION, "b"));
    }

    private static String template(String suffix, String listenerLb, String tgName, String extraResources) {
        return """
            {
              "Resources": {
                "AlbA": {
                  "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties": {"Name": "alb-a-%1$s", "Type": "application", "Subnets": ["%2$s", "%3$s"]}
                },
                "AlbB": {
                  "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties": {"Name": "alb-b-%1$s", "Type": "application", "Subnets": ["%2$s", "%3$s"]}
                },
                "Tg": {
                  "Type": "AWS::ElasticLoadBalancingV2::TargetGroup",
                  "Properties": {"Name": "%4$s", "Protocol": "HTTP", "Port": 80, "VpcId": "%5$s", "TargetType": "ip"}
                },
                "Listener": {
                  "Type": "AWS::ElasticLoadBalancingV2::Listener",
                  "Properties": {
                    "LoadBalancerArn": {"Ref": "%6$s"},
                    "Protocol": "HTTP",
                    "Port": 80,
                    "DefaultActions": [{"Type": "forward", "TargetGroupArn": {"Ref": "Tg"}}]
                  }
                }%7$s
              },
              "Outputs": {
                "AlbARef": {"Value": {"Ref": "AlbA"}},
                "AlbBRef": {"Value": {"Ref": "AlbB"}},
                "TgRef": {"Value": {"Ref": "Tg"}},
                "ListenerRef": {"Value": {"Ref": "Listener"}}
              }
            }
            """.formatted(suffix, Ec2Service.defaultSubnetId(REGION, "a"), Ec2Service.defaultSubnetId(REGION, "b"),
                tgName, Ec2Service.defaultVpcId(REGION), listenerLb, extraResources);
    }

    private static void createStack(String stackName, String body) {
        given().formParam("Action", "CreateStack").formParam("Version", "2010-05-15")
            .formParam("StackName", stackName).formParam("TemplateBody", body)
        .when().post("/").then().statusCode(200);
        assertStatus(describeStack(stackName), "CREATE_COMPLETE");
    }

    private static void updateStack(String stackName, String body) {
        given().formParam("Action", "UpdateStack").formParam("Version", "2010-05-15")
            .formParam("StackName", stackName).formParam("TemplateBody", body)
        .when().post("/").then().statusCode(200);
    }

    private static void deleteStack(String stackName) {
        given().formParam("Action", "DeleteStack").formParam("Version", "2010-05-15")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
    }

    private static String describeStack(String stackName) {
        return given().formParam("Action", "DescribeStacks").formParam("Version", "2010-05-15")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static String describeEvents(String stackName) {
        return given().formParam("Action", "DescribeStackEvents").formParam("Version", "2010-05-15")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static void assertStatus(String describeXml, String status) {
        assertEquals(true, describeXml.contains("<StackStatus>" + status + "</StackStatus>"),
                "expected " + status + " in " + describeXml);
    }

    private static void assertEvent(String eventsXml, String status, String physicalId) {
        Pattern p = Pattern.compile("<member>(.*?)</member>", Pattern.DOTALL);
        Matcher m = p.matcher(eventsXml);
        while (m.find()) {
            String member = m.group(1);
            if (member.contains("<ResourceStatus>" + status + "</ResourceStatus>")
                    && member.contains("<PhysicalResourceId>" + physicalId + "</PhysicalResourceId>")) {
                return;
            }
        }
        throw new AssertionError("no " + status + " event for " + physicalId + " in " + eventsXml);
    }

    private static String output(String describeXml, String key) {
        Matcher m = Pattern.compile("<OutputKey>" + key + "</OutputKey>\\s*<OutputValue>(.*?)</OutputValue>", Pattern.DOTALL)
                .matcher(describeXml);
        if (!m.find()) {
            m = Pattern.compile("<OutputValue>(.*?)</OutputValue>\\s*<OutputKey>" + key + "</OutputKey>", Pattern.DOTALL)
                    .matcher(describeXml);
            assertNotNull(m.find() ? m : null, "output " + key + " missing in " + describeXml);
        }
        return m.group(1);
    }

    /** DescribeListeners filters by ARN and answers an empty list for one that is gone. */
    private static void assertListenerMissing(String listenerArn) {
        given().formParam("Action", "DescribeListeners").formParam("Version", "2015-12-01")
            .formParam("ListenerArns.member.1", listenerArn)
        .when().post("/").then().statusCode(200).body(not(containsString(listenerArn)));
    }

    private static void assertListenerPresent(String listenerArn) {
        given().formParam("Action", "DescribeListeners").formParam("Version", "2015-12-01")
            .formParam("ListenerArns.member.1", listenerArn)
        .when().post("/").then().statusCode(200).body(containsString(listenerArn));
    }

    private static void assertTargetGroupMissing(String tgArn) {
        given().formParam("Action", "DescribeTargetGroups").formParam("Version", "2015-12-01")
            .formParam("TargetGroupArns.member.1", tgArn)
        .when().post("/").then().statusCode(400).body(containsString("TargetGroupNotFound")).body(not(containsString("<TargetGroupArn>" + tgArn)));
    }
}
