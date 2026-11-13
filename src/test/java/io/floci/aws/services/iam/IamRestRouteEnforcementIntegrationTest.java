package io.floci.aws.services.iam;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.OrganizationsRequests;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * REST requests whose route no rule mapped to an IAM action were let through under enforcement
 * unchecked. These routes are mapped now, and a caller needs the action AWS checks for them.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamRestRouteEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String ACCOUNT = "000000000000";
    /** The management account of an organization of this class's own, which no other test uses. */
    private static final String MANAGEMENT = "731000000154";
    /** Another account, owning the sinks and shares this class's own account reaches across accounts. */
    private static final String PEER = "731000000162";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    /** Each user's access key ID, by user name. */
    private final Map<String, String> keys = new HashMap<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void apiGatewayManagementRoutesBeyondRestApisAreCheckedByMethod() {
        String user = user("route-apigateway");
        String plan = "{\"name\":\"route-plan-" + suffix() + "\"}";

        policy(user, allow("apigateway:GET", "*"));
        refused(as(user, "apigateway").body(plan).post("/usageplans").then(), "apigateway:POST");
        as(user, "apigateway").get("/usageplans").then().statusCode(200);

        policy(user, allow("apigateway:POST", "*"));
        String id = as(user, "apigateway").body(plan).post("/usageplans").then().statusCode(201).extract().path("id");
        cleanup.register(() -> given().header("Authorization", auth(ADMIN, "apigateway")).delete("/usageplans/" + id));
    }

    @Test
    void getLayerVersionByArnIsCheckedAsGetLayerVersion() {
        String user = user("route-layer");
        String arn = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":layer:route-layer-" + suffix() + ":1";

        policy(user, allow("lambda:ListLayers", "*"));
        refused(as(user, "lambda").queryParam("find", "LayerVersion").queryParam("Arn", arn)
                .get("/2018-10-31/layers").then(), "lambda:GetLayerVersion");
        policy(user, allow("lambda:GetLayerVersion", "*"));
        // Allowed, the request reaches Lambda, which has no such layer.
        as(user, "lambda").queryParam("find", "LayerVersion").queryParam("Arn", arn)
                .get("/2018-10-31/layers").then().statusCode(404);
    }

    @Test
    void cloudFrontKeyValueStoreDataPlaneRoutesAreCheckedByOperation() {
        String user = user("route-kvs");
        String arn = "arn:aws:cloudfront::" + ACCOUNT + ":key-value-store/" + UUID.randomUUID();
        String path = "/key-value-stores/" + URLEncoder.encode(arn, StandardCharsets.UTF_8);

        policy(user, allow("cloudfront-keyvaluestore:ListKeys", "*"));
        refused(as(user, "cloudfront-keyvaluestore").urlEncodingEnabled(false).get(path).then(),
                "cloudfront-keyvaluestore:DescribeKeyValueStore");
        refused(as(user, "cloudfront-keyvaluestore").urlEncodingEnabled(false).header("If-Match", "etag")
                .body("{\"Value\":\"v\"}").put(path + "/keys/k").then(), "cloudfront-keyvaluestore:PutKey");

        policy(user, allow("cloudfront-keyvaluestore:DescribeKeyValueStore", arn));
        // Allowed on that store, the request reaches the data plane, which has no such store.
        as(user, "cloudfront-keyvaluestore").urlEncodingEnabled(false).get(path).then().statusCode(404);
    }
    @Test
    void cloudFrontKeyValueStoreControlPlaneIsCheckedAgainstTheStoresReturnedArn() {
        String user = user("route-kvs-control");
        String name = "kvs-" + suffix();
        String namespace = "http://cloudfront.amazonaws.com/doc/2020-05-31/";
        Response created = given().header("Authorization", auth(ADMIN, "cloudfront"))
                .contentType("application/xml")
                .body("<CreateKeyValueStoreRequest xmlns=\"" + namespace + "\"><Name>" + name
                        + "</Name></CreateKeyValueStoreRequest>")
                .post("/2020-05-31/key-value-store");
        created.then().statusCode(201);
        String arn = XmlParser.extractFirst(created.asString(), "ARN", null);
        String etag = created.header("ETag");

        policy(user, allow("cloudfront:*", "*") + "," +
                "{\"Effect\":\"Deny\",\"Action\":\"cloudfront:DeleteKeyValueStore\",\"Resource\":\"" + arn + "\"}");
        refused(as(user, "cloudfront").header("If-Match", etag).delete("/2020-05-31/key-value-store/" + name).then(),
                "cloudfront:DeleteKeyValueStore");
        as(user, "cloudfront").get("/2020-05-31/key-value-store/" + name).then().statusCode(200);

        policy(user, allow("cloudfront:DescribeKeyValueStore", "arn:aws:cloudfront::" + ACCOUNT
                + ":key-value-store/not-this-one"));
        refused(as(user, "cloudfront").get("/2020-05-31/key-value-store/" + name).then(),
                "cloudfront:DescribeKeyValueStore");
        policy(user, allow("cloudfront:DescribeKeyValueStore", arn));
        as(user, "cloudfront").get("/2020-05-31/key-value-store/" + name).then().statusCode(200);
    }
    @Test
    void aScheduleRequestSignedForAnotherServiceIsStillCheckedAsScheduler() {
        // Checked by the route it reached, not by the credential scope it was signed with: an S3
        // grant does not let the request through as an S3 request.
        String user = user("route-scheduler-scope");
        policy(user, allow("s3:*", "*"));
        refused(as(user, "s3").get("/schedules/route-schedule-" + suffix()).then(), "scheduler:GetSchedule");
    }

    @Test
    void schedulerTaggingIsCheckedOnTheBareTagsRouteToo() {
        // The shared tagging routes also take the ARN in the body of POST /tags.
        String user = user("route-scheduler-tags");
        String group = scheduleGroup("route-group-" + suffix());

        policy(user, allow("scheduler:ListTagsForResource", "*"));
        refused(as(user, "scheduler").body(tagBody(group)).post("/tags").then(), "scheduler:TagResource");
        policy(user, allow("scheduler:TagResource", group));
        // Allowed on that group, the request reaches Scheduler, which has no such group.
        as(user, "scheduler").body(tagBody(group)).post("/tags").then().statusCode(not(403));
    }

    @Test
    void postTagsIsCheckedAgainstTheArnItsBodyNames() {
        // POST /tags tags the ARN its body names: one named in the query does not move the check.
        String user = user("route-tags-body");
        String allowed = scheduleGroup("route-allowed-" + suffix());
        String other = scheduleGroup("route-other-" + suffix());

        policy(user, allow("scheduler:TagResource", allowed));
        refused(as(user, "scheduler").queryParam("resourceArn", allowed).body(tagBody(other)).post("/tags").then(),
                "scheduler:TagResource");
    }

    @Test
    void sharedTaggingIsCheckedAsTheServiceItsArnNames() {
        // The shared tagging routes hand the request to the service its ARN names, whatever scope it is
        // signed with: a Pipes grant does not let the caller tag a schedule group, and a scope with no
        // tagging rule does not let the request through unchecked.
        String user = user("route-tags-scope");
        String group = scheduleGroup("route-group-" + suffix());
        String queue = "arn:aws:batch:us-east-1:" + ACCOUNT + ":job-queue/route-queue-" + suffix();

        // Nor is an ARN of a service with no tagging rule checked as the signing scope's action.
        as(user, "pipes").body(tagBody(queue)).post("/tags").then().body(not(containsString("pipes:TagResource")));
        policy(user, allow("pipes:TagResource", "*"));
        refused(as(user, "pipes").body(tagBody(group)).post("/tags").then(), "scheduler:TagResource");
        refused(as(user, "sqs").body(tagBody(group)).post("/tags").then(), "scheduler:TagResource");
    }

    @Test
    void eksRoutesAreCheckedAgainstTheClusterAndTheNodeGroupEksHolds() {
        // Every EKS route was let through unchecked. A cluster route is checked against the cluster, and a node
        // group route against the ARN EKS holds the node group under, which ends in an id the request does not
        // carry, so a Deny on that node group applies where the action is allowed on every resource.
        String user = user("route-eks");
        String name = "route-eks-" + suffix();
        String cluster = eksCluster(name);
        String nodegroup = eks().body("{\"nodegroupName\":\"ng\",\"subnets\":[\"subnet-abc\"],"
                + "\"nodeRole\":\"arn:aws:iam::" + ACCOUNT + ":role/route-node\"}")
                .post("/clusters/" + name + "/node-groups").then().statusCode(200)
                .extract().path("nodegroup.nodegroupArn");
        cleanup.register(() -> eks().delete("/clusters/" + name + "/node-groups/ng"));

        policy(user, allow("eks:DescribeCluster", cluster + "-other"));
        refused(as(user, "eks").get("/clusters/" + name).then(), "eks:DescribeCluster");
        policy(user, allow("eks:DescribeCluster", cluster));
        as(user, "eks").get("/clusters/" + name).then().statusCode(200);

        String path = "/clusters/" + name + "/node-groups/ng";
        policy(user, allow("eks:DescribeNodegroup", "*") + "," + deny("eks:DescribeNodegroup", nodegroup));
        refused(as(user, "eks").get(path).then(), "eks:DescribeNodegroup");
        policy(user, allow("eks:DescribeNodegroup", cluster.replace(":cluster/", ":nodegroup/") + "/ng/*"));
        as(user, "eks").get(path).then().statusCode(200).body("nodegroup.nodegroupArn", equalTo(nodegroup));
    }

    @Test
    void eachResourceAnEksClusterHoldsIsCheckedAgainstTheArnEksHoldsItUnder() {
        // Each is looked up as EKS looks it up, so a Deny on its own ARN applies where the action is allowed
        // on every resource, and a grant on that ARN alone covers it.
        String user = user("route-eks-held");
        String name = "route-held-" + suffix();
        String base = "/clusters/" + name;
        eksCluster(name);
        String role = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", "route-held-" + suffix(),
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + allow("s3:*", "*") + "]}");
        String profile = eks().body("{\"fargateProfileName\":\"fp\",\"podExecutionRoleArn\":\"" + role + "\"}")
                .post(base + "/fargate-profiles").then().statusCode(200)
                .extract().path("fargateProfile.fargateProfileArn");
        cleanup.register(() -> eks().delete(base + "/fargate-profiles/fp"));
        String addon = eks().body("{\"addonName\":\"vpc-cni\"}").post(base + "/addons").then().statusCode(200)
                .extract().path("addon.addonArn");
        String update = eks().body("{}").post(base + "/addons/vpc-cni/update").then().statusCode(200)
                .extract().path("update.id");
        String entry = eks().body("{\"principalArn\":\"" + role + "\"}").post(base + "/access-entries").then()
                .statusCode(200).extract().path("accessEntry.accessEntryArn");
        String association = eks().body("{\"namespace\":\"default\",\"serviceAccount\":\"app\",\"roleArn\":\"" + role
                + "\"}").post(base + "/pod-identity-associations").then().statusCode(200)
                .extract().path("association.associationArn");
        String[][] routes = {
            {"eks:DescribeFargateProfile", base + "/fargate-profiles/fp", profile},
            {"eks:DescribeAddon", base + "/addons/vpc-cni", addon},
            // EKS returns an add-on's update without its addonName, so the update is its add-on's.
            {"eks:DescribeUpdate", base + "/updates/" + update, addon},
            {"eks:DescribeAccessEntry", base + "/access-entries/" + encoded(role), entry},
            {"eks:DescribePodIdentityAssociation",
                base + "/pod-identity-associations/" + association.substring(association.lastIndexOf('/') + 1),
                association},
        };
        for (String[] route : routes) {
            policy(user, allow(route[0], "*") + "," + deny(route[0], route[2]));
            refused(as(user, "eks").urlEncodingEnabled(false).get(route[1]).then(), route[0]);
            policy(user, allow(route[0], route[2]));
            as(user, "eks").urlEncodingEnabled(false).get(route[1]).then().statusCode(200);
        }
    }

    @Test
    void anEksClusterRouteIsCheckedAgainstTheClusterEksActsOn() {
        // EKS clusters are Regional: a request signed for the cluster's Region is checked against the cluster it
        // acts on, and one signed for another Region acts on that Region's cluster of the name, which is not there.
        String user = user("route-eks-region");
        String name = "route-west-" + suffix();
        String cluster = given().header("Authorization", auth(ADMIN, "us-west-2", "eks"))
                .contentType("application/json")
                .body("{\"name\":\"" + name + "\",\"roleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/route-cluster\"}")
                .post("/clusters").then().statusCode(200).extract().path("cluster.arn");
        cleanup.register(() -> given().header("Authorization", auth(ADMIN, "us-west-2", "eks"))
                .delete("/clusters/" + name));

        policy(user, allow("eks:DescribeCluster", "*") + "," + deny("eks:DescribeCluster", cluster));
        refused(as(user, "us-west-2", "eks").get("/clusters/" + name).then(), "eks:DescribeCluster");
        as(user, "eks").get("/clusters/" + name).then().statusCode(404);
        policy(user, allow("eks:DescribeCluster", cluster));
        as(user, "us-west-2", "eks").get("/clusters/" + name).then().statusCode(200);
    }

    @Test
    void anEksAccessEntrysPolicyRoutesAreCheckedAsTheirOwnActionsOnTheEntry() {
        // Associating, listing and disassociating an entry's access policies act on that entry, so a grant on
        // another entry does not cover them, and one on the entry's own ARN does.
        String user = user("route-eks-entry");
        String name = "route-entry-" + suffix();
        String base = "/clusters/" + name + "/access-entries";
        eksCluster(name);
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[" + allow("s3:*", "*") + "]}";
        String role = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/", "route-entry-" + suffix(),
                trust);
        String otherRole = EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/",
                "route-other-" + suffix(), trust);
        String entry = eks().body("{\"principalArn\":\"" + role + "\"}").post(base).then().statusCode(200)
                .extract().path("accessEntry.accessEntryArn");
        String other = eks().body("{\"principalArn\":\"" + otherRole + "\"}").post(base).then().statusCode(200)
                .extract().path("accessEntry.accessEntryArn");
        String policies = base + "/" + encoded(role) + "/access-policies";
        String admin = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy";
        String associate = "{\"policyArn\":\"" + admin + "\",\"accessScope\":{\"type\":\"cluster\"}}";
        String actions = "[\"eks:AssociateAccessPolicy\",\"eks:ListAssociatedAccessPolicies\","
                + "\"eks:DisassociateAccessPolicy\"]";

        policy(user, "{\"Effect\":\"Allow\",\"Action\":" + actions + ",\"Resource\":\"" + other + "\"}");
        refused(as(user, "eks").urlEncodingEnabled(false).body(associate).post(policies).then(),
                "eks:AssociateAccessPolicy");
        refused(as(user, "eks").urlEncodingEnabled(false).get(policies).then(), "eks:ListAssociatedAccessPolicies");
        refused(as(user, "eks").urlEncodingEnabled(false).delete(policies + "/" + encoded(admin)).then(),
                "eks:DisassociateAccessPolicy");

        policy(user, "{\"Effect\":\"Allow\",\"Action\":" + actions + ",\"Resource\":\"" + entry + "\"}");
        as(user, "eks").urlEncodingEnabled(false).body(associate).post(policies).then().statusCode(200);
        as(user, "eks").urlEncodingEnabled(false).get(policies).then().statusCode(200)
                .body("associatedAccessPolicies[0].policyArn", equalTo(admin));
        as(user, "eks").urlEncodingEnabled(false).delete(policies + "/" + encoded(admin)).then().statusCode(200);
        // A principal whose IAM path holds access-policies is an entry, so deleting it is DeleteAccessEntry.
        refused(as(user, "eks").urlEncodingEnabled(false)
                .delete(base + "/arn:aws:iam::" + ACCOUNT + ":role/ci/access-policies/runner").then(),
                "eks:DeleteAccessEntry");
    }

    @Test
    void eksTaggingIsCheckedAgainstTheCallersClusterOfThatName() {
        // EKS tags the caller's own cluster named after the ARN's last /, whatever account the ARN names, so a
        // grant on another account's cluster of that name does not cover it, and one on the caller's own does.
        String user = user("route-eks-tags");
        String cluster = eksCluster("route-tagged-" + suffix());
        String foreign = cluster.replace(ACCOUNT, "111122223333");
        String body = "{\"tags\":{\"team\":\"a\"}}";

        policy(user, allow("eks:TagResource", foreign));
        refused(as(user, "eks").body(body).post("/tags/" + foreign).then(), "eks:TagResource");
        policy(user, allow("eks:TagResource", cluster));
        as(user, "eks").body(body).post("/tags/" + foreign).then().statusCode(not(403));
        eks().get("/tags/" + cluster).then().statusCode(200).body("tags.team", equalTo("a"));
    }

    @Test
    void controlTowerTaggingIsChecked() {
        // Control Tower requests name their resources in the path, and its routes are not vendored, so the request
        // resources read none of them and its tagging is checked on *.
        String user = user("route-controltower-tags");
        String landingZone = "arn:aws:controltower:us-east-1:" + ACCOUNT + ":landingzone/ROUTE" + suffix();

        refused(as(user, "controltower").get("/tags/" + landingZone).then(), "controltower:ListTagsForResource");
        refused(as(user, "controltower").body("{\"tags\":{\"team\":\"a\"}}").post("/tags/" + landingZone).then(),
                "controltower:TagResource");
        refused(as(user, "controltower").queryParam("tagKeys", "team").delete("/tags/" + landingZone).then(),
                "controltower:UntagResource");
        // The shared controller also serves the bare /tags path, naming the ARN in the query or the body.
        refused(as(user, "controltower").queryParam("resourceArn", landingZone).get("/tags").then(),
                "controltower:ListTagsForResource");
        refused(as(user, "controltower").body("{\"resourceArn\":\"" + landingZone + "\",\"tags\":{\"team\":\"a\"}}")
                .post("/tags").then(), "controltower:TagResource");
        refused(as(user, "controltower").queryParam("resourceArn", landingZone).queryParam("tagKeys", "team")
                .delete("/tags").then(), "controltower:UntagResource");

        policy(user, allow("controltower:ListTagsForResource", "*"));
        as(user, "controltower").get("/tags/" + landingZone).then().statusCode(not(403));
    }

    @Test
    void aTrailingSlashDoesNotLetARouteThroughUnchecked() {
        // The router matches /clusters/ as /clusters, so the request is checked as the route it reaches.
        String user = user("route-trailing-slash");
        policy(user, allow("sqs:ListQueues", "*"));
        refused(as(user, "eks").body("{\"name\":\"route-slash-" + suffix() + "\"}").post("/clusters/").then(),
                "eks:CreateCluster");
        refused(as(user, "eks").get("/clusters/").then(), "eks:ListClusters");
        refused(as(user, "scheduler").get("/schedules/").then(), "scheduler:ListSchedules");
        refused(as(user, "lambda").get("/2015-03-31/functions/route-fn/").then(), "lambda:GetFunction");
    }

    /** Creates an EKS cluster as the account's admin, removed afterwards, and returns its ARN. */
    private String eksCluster(String name) {
        String arn = eks().body("{\"name\":\"" + name + "\",\"roleArn\":\"arn:aws:iam::" + ACCOUNT
                + ":role/route-cluster\",\"accessConfig\":{\"authenticationMode\":\"API\"}}")
                .post("/clusters").then().statusCode(200).extract().path("cluster.arn");
        cleanup.register(() -> eks().delete("/clusters/" + name));
        return arn;
    }

    private static RequestSpecification eks() {
        return given().header("Authorization", auth(ADMIN, "eks")).contentType("application/json");
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String scheduleGroup(String name) {
        return "arn:aws:scheduler:us-east-1:" + ACCOUNT + ":schedule-group/" + name;
    }

    private static String tagBody(String arn) {
        return "{\"resourceArn\":\"" + arn + "\",\"tags\":[{\"key\":\"team\",\"value\":\"a\"}]}";
    }

    @Test
    void lambdaTaggingIsCheckedAgainstTheTaggedFunction() {
        String user = user("route-lambda-tags");
        String function = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:route-fn-" + suffix();
        String path = "/2017-03-31/tags/" + function;
        String body = "{\"Tags\":{\"team\":\"a\"}}";

        policy(user, allow("lambda:TagResource", function + "-other"));
        refused(as(user, "lambda").body(body).post(path).then(), "lambda:TagResource");
        // A grant on the function itself covers it; the request reaches Lambda, which has no such function.
        policy(user, allow("lambda:TagResource", function));
        as(user, "lambda").body(body).post(path).then().statusCode(not(403));
    }

    @Test
    void lambdaTaggingIsCheckedAgainstTheCallersOwnResourceWhateverAccountTheArnNames() {
        // Lambda tags the caller's own function or MicroVM image of the ARN's name, so a grant on another
        // account's ARN of that name does not cover it, and a grant on the caller's own does.
        String user = user("route-lambda-tags-account");
        String name = "route-tagged-" + suffix();
        String body = "{\"Tags\":{\"team\":\"a\"}}";

        for (String type : new String[] {"function:", "microvm-image:"}) {
            String own = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":" + type + name;
            String foreign = "arn:aws:lambda:us-east-1:111122223333:" + type + name;
            policy(user, allow("lambda:TagResource", foreign));
            refused(as(user, "lambda").body(body).post("/2017-03-31/tags/" + foreign).then(), "lambda:TagResource");
            policy(user, allow("lambda:TagResource", own));
            as(user, "lambda").body(body).post("/2017-03-31/tags/" + foreign).then().statusCode(not(403));
        }
    }

    @Test
    void lambdaFunctionSettingsRoutesAreCheckedAgainstTheFunction() {
        // Each is checked as its own action against the function it names: a grant on another function
        // does not cover it, and one on the function does. The request then reaches Lambda, which has no
        // such function.
        String user = user("route-lambda-settings");
        String name = "route-settings-" + suffix();
        String function = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:" + name;
        String[][] routes = {
            {"GET", "/2015-03-31/functions/" + name + "/configuration", "lambda:GetFunctionConfiguration"},
            {"GET", "/2015-03-31/functions/" + name + "/versions", "lambda:ListVersionsByFunction"},
            {"POST", "/2015-03-31/functions/" + name + "/versions", "lambda:PublishVersion"},
            {"PUT", "/2017-10-31/functions/" + name + "/concurrency", "lambda:PutFunctionConcurrency"},
            {"GET", "/2019-09-30/functions/" + name + "/concurrency", "lambda:GetFunctionConcurrency"},
            {"PUT", "/2019-09-25/functions/" + name + "/event-invoke-config", "lambda:PutFunctionEventInvokeConfig"},
            {"GET", "/2020-06-30/functions/" + name + "/code-signing-config", "lambda:GetFunctionCodeSigningConfig"},
        };
        for (String[] route : routes) {
            policy(user, allow(route[2], function + "-other"));
            refused(send(user, route[0], route[1]), route[2]);
            policy(user, allow(route[2], function));
            send(user, route[0], route[1]).statusCode(not(403));
        }
    }

    @Test
    void lambdaCodeSigningNamespacedFunctionIsCheckedAgainstItsTarget() {
        String user = user("route-lambda-code-signing-namespace");
        String name = "route-protected-" + suffix();
        String function = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:" + name;
        String path = "/2020-06-30/functions/function:" + name + "/code-signing-config";

        policy(user, allow("lambda:GetFunctionCodeSigningConfig", function + "-other"));
        refused(send(user, "GET", path), "lambda:GetFunctionCodeSigningConfig");
        policy(user, allow("lambda:GetFunctionCodeSigningConfig", function));
        send(user, "GET", path).statusCode(not(403));
    }

    @Test
    void lambdaMappingConfigAndLayerRoutesAreCheckedAgainstWhatTheyName() {
        // A grant on the same resource in another account does not cover the caller's own.
        String user = user("route-lambda-resources");
        String uuid = "14e0db71-5d35-4eb5-b481-8945cf9d10c2";
        String layerName = "route-layer-" + suffix();
        String mapping = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":event-source-mapping:" + uuid;
        String config = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":code-signing-config:csc-0123456789abcdef0";
        String layer = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":layer:" + layerName;
        String[][] routes = {
            {"GET", "/2015-03-31/event-source-mappings/" + uuid, "lambda:GetEventSourceMapping", mapping},
            {"PUT", "/2015-03-31/event-source-mappings/" + uuid, "lambda:UpdateEventSourceMapping", mapping},
            {"GET", "/2020-04-22/code-signing-configs/" + config, "lambda:GetCodeSigningConfig", config},
            {"GET", "/2020-04-22/code-signing-configs/" + config + "/functions",
                "lambda:ListFunctionsByCodeSigningConfig", config},
            {"GET", "/2018-10-31/layers/" + layerName + "/versions/1", "lambda:GetLayerVersion", layer + ":1"},
            {"DELETE", "/2018-10-31/layers/" + layerName + "/versions/1", "lambda:DeleteLayerVersion", layer + ":1"},
            {"POST", "/2018-10-31/layers/" + layerName + "/versions", "lambda:PublishLayerVersion", layer},
        };
        for (String[] route : routes) {
            policy(user, allow(route[2], route[3].replace(ACCOUNT, "111122223333")));
            refused(send(user, route[0], route[1]), route[2]);
            policy(user, allow(route[2], route[3]));
            send(user, route[0], route[1]).statusCode(not(403));
        }
        // A config's function list ends in /functions, as ListFunctions does, and is not checked as it.
        policy(user, allow("lambda:ListFunctions", "*"));
        refused(send(user, "GET", "/2020-04-22/code-signing-configs/" + config + "/functions"),
                "lambda:ListFunctionsByCodeSigningConfig");
    }

    @Test
    void aLayerVersionIsCheckedAsLambdaReadsItsNameAndNumber() {
        // Lambda parses version 01 as version 1, and the router keeps an encoded / inside the layer's name, so
        // a Deny on that layer version applies however the request spells them.
        String user = user("route-layer-spelling");
        String name = "route-spelt-" + suffix();
        String layer = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":layer:" + name;
        policy(user, allow("lambda:GetLayerVersion", "*") + "," + deny("lambda:GetLayerVersion", layer + ":1")
                + "," + deny("lambda:GetLayerVersion", layer + "/x:1"));
        refused(as(user, "lambda").get("/2018-10-31/layers/" + name + "/versions/01").then(),
                "lambda:GetLayerVersion");
        refused(as(user, "lambda").queryParam("find", "LayerVersion").queryParam("Arn", layer + ":01")
                .get("/2018-10-31/layers").then(), "lambda:GetLayerVersion");
        refused(as(user, "lambda").urlEncodingEnabled(false).get("/2018-10-31/layers/" + name + "%2Fx/versions/1")
                .then(), "lambda:GetLayerVersion");
        // Allowed, each reaches Lambda, which looks up that version of that layer.
        policy(user, allow("lambda:GetLayerVersion", "*"));
        as(user, "lambda").get("/2018-10-31/layers/" + name + "/versions/01").then().statusCode(404)
                .body("message", containsString("Layer version 1 for layer " + name + " not found"));
        as(user, "lambda").urlEncodingEnabled(false).get("/2018-10-31/layers/" + name + "%2Fx/versions/1").then()
                .statusCode(404).body("message", containsString("for layer " + name + "/x not found"));
    }

    @Test
    void lambdaAccountSettingsAreChecked() {
        String user = user("route-lambda-account");
        policy(user, allow("lambda:ListFunctions", "*"));
        refused(as(user, "lambda").get("/2016-08-19/account-settings").then(), "lambda:GetAccountSettings");
        policy(user, allow("lambda:GetAccountSettings", "*"));
        as(user, "lambda").get("/2016-08-19/account-settings").then().statusCode(200);
    }

    @Test
    void aMicrovmIsCheckedAgainstTheImageItRuns() {
        // RunMicrovm and the actions on a running MicroVM act on its image, so a Deny on the image applies to
        // them even where the action is allowed on every resource, and a grant on the image alone covers them.
        String user = user("route-microvm-image");
        String image = microvmImage("route-image-" + suffix());
        String run = "{\"imageIdentifier\":\"" + image.substring(image.lastIndexOf(':') + 1) + "\"}";

        policy(user, allow("lambda:RunMicrovm", "*") + "," + deny("lambda:RunMicrovm", image));
        refused(as(user, "lambda").body(run).post("/2025-09-09/microvms").then(), "lambda:RunMicrovm");
        // It also passes the managed connectors a MicroVM gets when it names none.
        policy(user, allow("lambda:RunMicrovm", image) + "," + allow("lambda:PassNetworkConnector", "*"));
        String microvm = as(user, "lambda").body(run).post("/2025-09-09/microvms").then().statusCode(200)
                .extract().path("microvmId");
        cleanup.register(() -> admin().delete("/2025-09-09/microvms/" + microvm));

        policy(user, allow("lambda:GetMicrovm", "*") + "," + deny("lambda:GetMicrovm", image));
        refused(as(user, "lambda").get("/2025-09-09/microvms/" + microvm).then(), "lambda:GetMicrovm");
        policy(user, allow("lambda:GetMicrovm", image));
        as(user, "lambda").get("/2025-09-09/microvms/" + microvm).then().statusCode(200);
    }

    @Test
    void aMicrovmImageTagTargetWithoutTheArnPrefixIsRefusedAndTagsNothing() {
        // TagResource takes only an ARN, so a target without arn: names no image, whatever it holds.
        String user = user("route-image-tags");
        String image = microvmImage("route-tagged-image-" + suffix());
        String target = "/2017-03-31/tags/x:y:z:us-east-1:microvm-image:" + image.substring(image.lastIndexOf(':') + 1);

        policy(user, allow("lambda:TagResource", "*"));
        as(user, "lambda").body("{\"Tags\":{\"team\":\"a\"}}").post(target).then()
                .statusCode(400).body(containsString("InvalidParameterValueException"));
        admin().get("/2017-03-31/tags/" + image).then().statusCode(200).body("Tags.size()", equalTo(0));
    }

    /**
     * A route no rule maps is checked as the operation of its vendored route when AWS authorizes that operation's
     * action on no resource type, in both OpenSearch API versions and for Access Analyzer.
     */
    @Test
    void aVendoredRouteIsCheckedWhenAwsAuthorizesItsActionOnNoResourceType() {
        String user = user("route-vendored");

        policy(user, allow("es:ListVpcEndpoints", "*"));
        refused(as(user, "es").get("/2021-01-01/domain").then(), "es:ListDomainNames");
        refused(as(user, "es").get("/2015-01-01/domain").then(), "es:ListDomainNames");
        refused(as(user, "es").get("/2021-01-01/opensearch/versions").then(), "es:ListVersions");
        refused(as(user, "access-analyzer").get("/analyzer").then(), "access-analyzer:ListAnalyzers");

        policy(user, allow("es:ListDomainNames", "*"));
        as(user, "es").get("/2021-01-01/domain").then().statusCode(200);
        as(user, "es").get("/2015-01-01/domain").then().statusCode(200);
    }

    /**
     * A route of Lake Formation, Macie or Inspector that AWS authorizes on no resource type is checked as its vendored
     * route's operation, with no hand rule, Macie's on the paths it shares with other services among them: refused
     * without its action, past the check with it.
     */
    @Test
    void lakeFormationMacieAndInspectorAreCheckedByTheirRoutes() {
        String user = user("route-no-type");

        policy(user, allow("lakeformation:ListLFTags", "*"));
        refused(as(user, "lakeformation").body("{}").post("/GetDataLakeSettings").then(),
                "lakeformation:GetDataLakeSettings");
        refused(as(user, "macie2").get("/admin/configuration").then(), "macie2:DescribeOrganizationConfiguration");
        refused(as(user, "macie2").get("/members").then(), "macie2:ListMembers");
        refused(as(user, "macie2").get("/admin").then(), "macie2:ListOrganizationAdminAccounts");
        refused(as(user, "inspector2").body("{}").post("/status/batch/get").then(),
                "inspector2:BatchGetAccountStatus");
        refused(as(user, "inspector2").body("{}").post("/disable").then(), "inspector2:Disable");

        policy(user, allow("lakeformation:GetDataLakeSettings", "*") + ","
                + allow("macie2:DescribeOrganizationConfiguration", "*") + ","
                + allow("inspector2:BatchGetAccountStatus", "*"));
        as(user, "lakeformation").body("{}").post("/GetDataLakeSettings").then().statusCode(200);
        // Past the check, Macie answers that this account has no session.
        as(user, "macie2").get("/admin/configuration").then().statusCode(404);
        as(user, "inspector2").body("{}").post("/status/batch/get").then().statusCode(200);
    }

    /**
     * Macie's {@code CreateMember} is checked on the member its {@code account.accountId} names: a grant scoped to the
     * member lets the request reach Macie, which answers that this account has no session, a request naming another
     * member is refused, and a deny on the member holds under an allow on everything.
     */
    @Test
    void macieCreateMemberIsCheckedOnTheMemberItNames() {
        String user = user("route-macie-member");
        String member = "arn:aws:macie2:us-east-1:" + ACCOUNT + ":member/111122223333";

        policy(user, allow("macie2:CreateMember", member));
        createMember(user, "111122223333", null).statusCode(404);
        refused(createMember(user, "444455556666", null), "macie2:CreateMember");

        policy(user, allow("macie2:CreateMember", "*") + "," + deny("macie2:CreateMember", member));
        refused(createMember(user, "111122223333", null), "macie2:CreateMember");
        createMember(user, "444455556666", null).statusCode(404);
    }

    /**
     * A Macie {@code CreateMember} that carries tags also needs {@code macie2:TagResource} on the member it makes, and
     * a condition on {@code aws:RequestTag/<key>} sees those tags. One that carries none needs only its own action.
     */
    @Test
    void macieCreateMemberIsCheckedWithTheTagsItCarries() {
        String user = user("route-macie-tags");
        String member = "arn:aws:macie2:us-east-1:" + ACCOUNT + ":member/111122223333";

        policy(user, allow("macie2:CreateMember", member));
        createMember(user, "111122223333", null).statusCode(404);
        refused(createMember(user, "111122223333", "blue"), "macie2:TagResource");

        policy(user, allow("macie2:CreateMember", member) + "," + allow("macie2:TagResource", member));
        createMember(user, "111122223333", "blue").statusCode(404);

        policy(user, allow("macie2:*", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"macie2:CreateMember\","
                + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"red\"}}}");
        refused(createMember(user, "111122223333", "red"), "macie2:CreateMember");
        createMember(user, "111122223333", "blue").statusCode(404);
    }

    /**
     * An OpenSearch request naming its domain in its path, or its ARN in its query, is checked on that domain, in
     * both API versions: a grant scoped to the domain lets the request reach OpenSearch, which has no such domain, a
     * request naming another domain is refused, and a deny on the domain holds under an allow on everything. A domain
     * a request's body gives where OpenSearch reads it from the query names nothing: {@code GetCompatibleVersions}
     * with the domain in its body alone, which OpenSearch answers with every version, is checked on {@code *}.
     */
    @Test
    void anOpenSearchRequestIsCheckedOnTheDomainItsPathOrQueryNames() {
        String user = user("route-domain");
        String domain = "route-domain-" + suffix();
        String arn = "arn:aws:es:us-east-1:" + ACCOUNT + ":domain/" + domain;
        policy(user, allow("es:DescribeDomain", arn) + "," + allow("es:DescribeElasticsearchDomain", arn) + ","
                + allow("es:ListTags", arn) + "," + allow("es:GetCompatibleVersions", arn));
        as(user, "es").queryParam("domainName", domain).get("/2021-01-01/opensearch/compatibleVersions").then()
                .statusCode(409);
        refused(as(user, "es").body("{\"DomainName\":\"" + domain + "\"}").get("/2021-01-01/opensearch/compatibleVersions")
                .then(), "es:GetCompatibleVersions");
        as(user, "es").get("/2021-01-01/opensearch/domain/" + domain).then().statusCode(409);
        as(user, "es").get("/2015-01-01/es/domain/" + domain).then().statusCode(409);
        as(user, "es").queryParam("arn", arn).get("/2021-01-01/tags/").then().statusCode(not(403));
        refused(as(user, "es").get("/2021-01-01/opensearch/domain/other-" + domain).then(), "es:DescribeDomain");
        refused(as(user, "es").get("/2015-01-01/es/domain/other-" + domain).then(), "es:DescribeElasticsearchDomain");
        refused(as(user, "es").queryParam("arn", arn + "-other").get("/2021-01-01/tags/").then(), "es:ListTags");

        policy(user, allow("es:*", "*") + "," + deny("es:DescribeDomain", arn));
        refused(as(user, "es").get("/2021-01-01/opensearch/domain/" + domain).then(), "es:DescribeDomain");
        as(user, "es").get("/2021-01-01/opensearch/domain/other-" + domain).then().statusCode(409);
    }

    /**
     * An Access Analyzer request naming its analyzer, in its path or its body, is checked on that analyzer, the ARN
     * Access Analyzer gives it: a caller with no grant is refused, a grant scoped to the analyzer lets
     * {@code GetAnalyzer} read it and {@code CreateAnalyzer} reach its validation of the type, a request naming
     * another analyzer is refused, a deny on the analyzer holds under an allow on everything, and that allow lets
     * {@code DeleteAnalyzer} delete it.
     */
    @Test
    void anAccessAnalyzerRequestIsCheckedOnTheAnalyzerItsPathOrBodyNames() {
        String user = user("route-analyzer");
        String analyzer = "route-analyzer-" + suffix();
        String arn = "arn:aws:access-analyzer:us-east-1:" + ACCOUNT + ":analyzer/" + analyzer;
        refused(as(user, "access-analyzer").get("/analyzer/" + analyzer).then(), "access-analyzer:GetAnalyzer");
        accessAnalyzer().body("{\"analyzerName\":\"" + analyzer + "\",\"type\":\"ORGANIZATION\"}").put("/analyzer")
                .then().statusCode(200).body("arn", equalTo(arn));
        cleanup.register(() -> accessAnalyzer().delete("/analyzer/" + analyzer));

        policy(user, allow("access-analyzer:GetAnalyzer", arn) + "," + allow("access-analyzer:DeleteAnalyzer", arn)
                + "," + allow("access-analyzer:CreateAnalyzer", arn));
        as(user, "access-analyzer").get("/analyzer/" + analyzer).then().statusCode(200)
                .body("analyzer.arn", equalTo(arn));
        as(user, "access-analyzer").body(createAnalyzer(analyzer)).put("/analyzer").then().statusCode(400)
                .body(containsString("ValidationException"));
        refused(as(user, "access-analyzer").get("/analyzer/other-" + analyzer).then(), "access-analyzer:GetAnalyzer");
        refused(as(user, "access-analyzer").delete("/analyzer/other-" + analyzer).then(),
                "access-analyzer:DeleteAnalyzer");
        refused(as(user, "access-analyzer").body(createAnalyzer("other-" + analyzer)).put("/analyzer").then(),
                "access-analyzer:CreateAnalyzer");

        policy(user, allow("access-analyzer:*", "*") + "," + deny("access-analyzer:GetAnalyzer", arn));
        refused(as(user, "access-analyzer").get("/analyzer/" + analyzer).then(), "access-analyzer:GetAnalyzer");
        as(user, "access-analyzer").get("/analyzer/other-" + analyzer).then().statusCode(404);
        as(user, "access-analyzer").delete("/analyzer/" + analyzer).then().statusCode(200);
    }

    /**
     * A Security Hub request is checked on the caller's hub in the Region it is signed for, {@code hub/default}, the
     * ARN Security Hub gives it, or on the hub its {@code HubArn} names: a caller with no grant is refused, a grant
     * scoped to the hub lets {@code DescribeStandards} through and {@code DescribeHub} reach Security Hub, which is
     * not enabled there, with or without the hub's ARN, a request naming or signed for another Region's hub is
     * refused, and a deny on the hub holds under an allow on everything.
     */
    @Test
    void aSecurityHubRequestIsCheckedOnTheHub() {
        String user = user("route-hub");
        String region = "eu-west-3";
        String hub = "arn:aws:securityhub:" + region + ":" + ACCOUNT + ":hub/default";
        String otherHub = "arn:aws:securityhub:us-west-2:" + ACCOUNT + ":hub/default";
        refused(as(user, region, "securityhub").get("/standards").then(), "securityhub:DescribeStandards");
        refused(as(user, region, "securityhub").body("{}").post("/accounts").then(), "securityhub:EnableSecurityHub");

        policy(user, allow("securityhub:DescribeStandards", hub) + "," + allow("securityhub:DescribeHub", hub));
        as(user, region, "securityhub").get("/standards").then().statusCode(200);
        as(user, region, "securityhub").get("/accounts").then().statusCode(404)
                .body(containsString("ResourceNotFoundException"));
        as(user, region, "securityhub").queryParam("HubArn", hub).get("/accounts").then().statusCode(404)
                .body(containsString("ResourceNotFoundException"));
        refused(as(user, region, "securityhub").queryParam("HubArn", otherHub).get("/accounts").then(),
                "securityhub:DescribeHub");
        refused(as(user, "us-west-2", "securityhub").get("/standards").then(), "securityhub:DescribeStandards");

        policy(user, allow("securityhub:*", "*") + "," + deny("securityhub:DescribeStandards", hub));
        refused(as(user, region, "securityhub").get("/standards").then(), "securityhub:DescribeStandards");
        as(user, "us-west-2", "securityhub").get("/standards").then().statusCode(200);
    }

    /**
     * A GuardDuty request naming its detector in its path is checked on that detector, the ARN GuardDuty gives it,
     * and one naming none as its action on {@code *}: a caller with no grant is refused, a grant scoped to the
     * detector lets {@code GetDetector} read it, a request naming another detector is refused, and a deny on the
     * detector holds under an allow on everything.
     */
    @Test
    void aGuardDutyRequestIsCheckedOnTheDetectorItsPathNames() {
        String user = user("route-detector");
        String region = "eu-west-3";
        refused(as(user, region, "guardduty").get("/detector").then(), "guardduty:ListDetectors");
        String detector = given().header("Authorization", auth(ADMIN, region, "guardduty"))
                .contentType("application/json").body("{\"enable\":true}").post("/detector")
                .then().statusCode(200).extract().path("detectorId");
        cleanup.register(() -> given().header("Authorization", auth(ADMIN, region, "guardduty"))
                .delete("/detector/" + detector));
        String arn = "arn:aws:guardduty:" + region + ":" + ACCOUNT + ":detector/" + detector;
        refused(as(user, region, "guardduty").get("/detector/" + detector).then(), "guardduty:GetDetector");

        policy(user, allow("guardduty:GetDetector", arn));
        as(user, region, "guardduty").get("/detector/" + detector).then().statusCode(200)
                .body("status", equalTo("ENABLED"));
        refused(as(user, region, "guardduty").get("/detector/other" + detector).then(), "guardduty:GetDetector");
        refused(as(user, region, "guardduty").get("/detector").then(), "guardduty:ListDetectors");

        policy(user, allow("guardduty:*", "*") + "," + deny("guardduty:GetDetector", arn));
        refused(as(user, region, "guardduty").get("/detector/" + detector).then(), "guardduty:GetDetector");
        as(user, region, "guardduty").get("/detector").then().statusCode(200);
    }

    /**
     * A GuardDuty malware protection plan request, which GuardDutyRouteFilter moves onto {@code /_guardduty}, is
     * checked as the model's route for it, on the plan its path names, and so is one sent to {@code /_guardduty}
     * itself: a caller with no grant is refused before a plan is created, a grant scoped to the plan lets
     * {@code GetMalwareProtectionPlan} read it and refuses another, and a deny on the plan keeps it from being
     * deleted under an allow on everything.
     */
    @Test
    void aGuardDutyMalwareProtectionPlanRequestIsCheckedOnThePlanItsPathNames() {
        String user = user("route-plan");
        String region = "eu-west-3";
        String plan = "{\"role\":\"arn:aws:iam::" + ACCOUNT + ":role/malware-scan\","
                + "\"protectedResource\":{\"s3Bucket\":{\"bucketName\":\"route-plan-" + suffix() + "\"}}}";
        refused(as(user, region, "guardduty").body(plan).post("/malware-protection-plan").then(),
                "guardduty:CreateMalwareProtectionPlan");
        refused(as(user, region, "guardduty").get("/malware-protection-plan").then(),
                "guardduty:ListMalwareProtectionPlans");
        // GuardDuty refuses a second plan for one bucket, so this create also shows the refused one made none.
        String id = given().header("Authorization", auth(ADMIN, region, "guardduty"))
                .contentType("application/json").body(plan).post("/malware-protection-plan")
                .then().statusCode(200).extract().path("malwareProtectionPlanId");
        cleanup.register(() -> given().header("Authorization", auth(ADMIN, region, "guardduty"))
                .delete("/malware-protection-plan/" + id));
        String arn = "arn:aws:guardduty:" + region + ":" + ACCOUNT + ":malware-protection-plan/" + id;
        refused(as(user, region, "guardduty").get("/malware-protection-plan/" + id).then(),
                "guardduty:GetMalwareProtectionPlan");
        refused(as(user, region, "guardduty").get("/_guardduty/malware-protection-plan/" + id).then(),
                "guardduty:GetMalwareProtectionPlan");

        policy(user, allow("guardduty:GetMalwareProtectionPlan", arn));
        as(user, region, "guardduty").get("/malware-protection-plan/" + id).then().statusCode(200)
                .body("arn", equalTo(arn));
        as(user, region, "guardduty").get("/_guardduty/malware-protection-plan/" + id).then().statusCode(200)
                .body("arn", equalTo(arn));
        refused(as(user, region, "guardduty").get("/malware-protection-plan/other" + id).then(),
                "guardduty:GetMalwareProtectionPlan");

        policy(user, allow("guardduty:*", "*") + "," + deny("guardduty:DeleteMalwareProtectionPlan", arn));
        refused(as(user, region, "guardduty").delete("/malware-protection-plan/" + id).then(),
                "guardduty:DeleteMalwareProtectionPlan");
        as(user, region, "guardduty").get("/malware-protection-plan/" + id).then().statusCode(200);
    }

    /**
     * A segment ending in a matrix parameter, which the router ignores, is checked as the route it reaches, on the
     * resource its path names: the routed path carries no matrix parameter, so the rule matches and the ARN is the
     * named resource's, and a grant scoped to it lets the request through while a caller with none is refused.
     */
    @Test
    void aMatrixParameterLeavesARouteCheckedOnTheResourceItNames() {
        String scheduler = user("route-matrix-scheduler");
        String lambda = user("route-matrix-lambda");
        String nobody = user("route-matrix-nobody");
        policy(scheduler, allow("scheduler:GetScheduleGroup",
                "arn:aws:scheduler:us-east-1:" + ACCOUNT + ":schedule-group/default"));
        policy(lambda, allow("lambda:GetFunction", "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:route-matrix-fn"));
        for (String path : new String[]{"/schedule-groups;ignored=yes/default", "/schedule-groups/default;ignored=yes"}) {
            as(scheduler, "scheduler").urlEncodingEnabled(false).get(path).then().statusCode(200);
            refused(as(nobody, "scheduler").urlEncodingEnabled(false).get(path).then(), "scheduler:GetScheduleGroup");
        }
        for (String path : new String[]{"/2015-03-31/functions;ignored=yes/route-matrix-fn",
                "/2015-03-31/functions/route-matrix-fn;ignored=yes"}) {
            as(lambda, "lambda").urlEncodingEnabled(false).get(path).then().statusCode(404);
            refused(as(nobody, "lambda").urlEncodingEnabled(false).get(path).then(), "lambda:GetFunction");
        }
    }

    @Test
    void fisRamOamResourceExplorerAccountAndControlCatalogAreCheckedByTheirRoutes() {
        String user = user("route-governance");

        checkedAs(user, "account", "POST", "/getAlternateContact", "{\"AlternateContactType\":\"BILLING\"}",
                "account:GetAlternateContact");
        checkedAs(user, "controlcatalog", "POST", "/list-controls", "{}", "controlcatalog:ListControls");
        checkedAs(user, "fis", "GET", "/experimentTemplates", null, "fis:ListExperimentTemplates");
        checkedAs(user, "oam", "POST", "/ListSinks", "{}", "oam:ListSinks");
        checkedAs(user, "ram", "POST", "/getresourceshares", "{\"resourceOwner\":\"SELF\"}", "ram:GetResourceShares");
        checkedAs(user, "resource-explorer-2", "POST", "/ListViews", "{}", "resource-explorer-2:ListViews");
    }

    @Test
    void resourceExplorerRoutesItsFilterMovesOffSharedPathsAreChecked() {
        String user = user("route-re2-moved");

        checkedAs(user, "resource-explorer-2", "POST", "/ListIndexes", "{}", "resource-explorer-2:ListIndexes");
        // AWS authorizes ListResources as Search.
        checkedAs(user, "resource-explorer-2", "POST", "/ListResources", "{}", "resource-explorer-2:Search");
    }

    @Test
    void sharedTagRoutesAreCheckedForFisAndResourceExplorerOnBothPaths() {
        String user = user("route-shared-tags");
        String template = encoded("arn:aws:fis:us-east-1:" + ACCOUNT + ":experiment-template/EXTabsent");
        String view = encoded("arn:aws:resource-explorer-2:us-east-1:" + ACCOUNT + ":view/absent/1");

        checkedAs(user, "fis", "GET", "/tags/" + template, null, "fis:ListTagsForResource");
        policy(user, allow("sts:GetCallerIdentity", "*"));
        checkedAs(user, "fis", "GET", "/tags?resourceArn=" + template, null, "fis:ListTagsForResource");
        checkedAs(user, "resource-explorer-2", "GET", "/tags/" + view, null, "resource-explorer-2:ListTagsForResource");
        policy(user, allow("sts:GetCallerIdentity", "*"));
        checkedAs(user, "resource-explorer-2", "GET", "/tags?resourceArn=" + view, null,
                "resource-explorer-2:ListTagsForResource");
    }

    @Test
    void accountIsCheckedOnTheAccountARequestActsOn() {
        String user = user("route-account");
        String own = "{\"AlternateContactType\":\"BILLING\"}";
        String member = "{\"AccountId\":\"111122223333\",\"AlternateContactType\":\"BILLING\"}";
        String action = "account:GetAlternateContact";

        policy(user, allow(action, "arn:aws:account::" + ACCOUNT + ":account"));
        request(user, "account", "POST", "/getAlternateContact", own).statusCode(404);
        refused(request(user, "account", "POST", "/getAlternateContact", member), action);

        policy(user, allow(action, "arn:aws:account::*:account/o-*/111122223333"));
        // Past the check: the caller is in no organization, which Account Management refuses itself.
        request(user, "account", "POST", "/getAlternateContact", member)
                .statusCode(403).body(containsString("The specified account"));
        refused(request(user, "account", "POST", "/getAlternateContact", own), action);
    }

    @Test
    void accountIsCheckedOnTheMemberAccountOfTheCallersOrganization() {
        OrganizationsRequests.request(MANAGEMENT, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .when().post("/").then().statusCode(200);
        String organization = OrganizationsRequests.request(MANAGEMENT, "DescribeOrganization", "{}")
                .when().post("/").then().statusCode(200).extract().path("Organization.Id");
        String member = OrganizationsRequests.request(MANAGEMENT, "CreateAccount",
                        "{\"Email\":\"route-contact@example.com\",\"AccountName\":\"RouteContact\"}")
                .when().post("/").then().statusCode(200).extract().path("CreateAccountStatus.AccountId");
        OrganizationsRequests.request(MANAGEMENT, "EnableAWSServiceAccess",
                        "{\"ServicePrincipal\":\"account.amazonaws.com\"}")
                .when().post("/").then().statusCode(200);
        String userName = "route-account-member-" + suffix();
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(MANAGEMENT, "iam"),
                userName).accessKeyId();
        String action = "account:GetAlternateContact";
        String memberArn = "arn:aws:account::" + MANAGEMENT + ":account/" + organization + "/" + member;
        String body = "{\"AccountId\":\"" + member + "\",\"AlternateContactType\":\"BILLING\"}";
        Consumer<String> policy = statements -> EnforcementFixtures.putUserPolicy(baseUri.getPort(),
                auth(MANAGEMENT, "iam"), userName, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");

        // The member account's own ARN, its organization and management account the ones the caller's organization has.
        policy.accept(allow(action, memberArn));
        given().header("Authorization", auth(accessKeyId, "account")).contentType("application/json").body(body)
                .post("/getAlternateContact").then().statusCode(404);
        policy.accept(allow(action, "arn:aws:account::" + MANAGEMENT + ":account/o-other00000/" + member));
        refused(given().header("Authorization", auth(accessKeyId, "account")).contentType("application/json")
                .body(body).post("/getAlternateContact").then(), action);
        policy.accept(allow(action, "*") + "," + deny(action, memberArn));
        refused(given().header("Authorization", auth(accessKeyId, "account")).contentType("application/json")
                .body(body).post("/getAlternateContact").then(), action);
    }

    @Test
    void controlCatalogIsCheckedOnTheControlItNames() {
        String user = user("route-control");
        String control = "arn:aws:controlcatalog:::control/" + "a".repeat(34);
        String other = "arn:aws:controlcatalog:::control/" + "b".repeat(34);

        policy(user, allow("controlcatalog:GetControl", control));
        request(user, "controlcatalog", "POST", "/get-control", "{\"ControlArn\":\"" + control + "\"}").statusCode(404);
        refused(request(user, "controlcatalog", "POST", "/get-control", "{\"ControlArn\":\"" + other + "\"}"),
                "controlcatalog:GetControl");
    }

    @Test
    void fisIsCheckedOnTheTemplateItsPathNames() {
        String template = "{\"clientToken\":\"" + suffix() + "\",\"description\":\"d\",\"roleArn\":\"arn:aws:iam::"
                + ACCOUNT + ":role/fis\",\"stopConditions\":[{\"source\":\"none\"}],\"targets\":{},\"actions\":"
                + "{\"wait\":{\"actionId\":\"aws:fis:wait\",\"parameters\":{\"duration\":\"PT1M\"}}}}";
        String id = adminOf("fis").body(template).post("/experimentTemplates")
                .then().statusCode(200).extract().path("experimentTemplate.id");
        cleanup.register(() -> adminOf("fis").delete("/experimentTemplates/" + id));
        String arn = "arn:aws:fis:us-east-1:" + ACCOUNT + ":experiment-template/" + id;
        String user = user("route-fis");

        policy(user, allow("fis:GetExperimentTemplate", arn));
        request(user, "fis", "GET", "/experimentTemplates/" + id, null)
                .statusCode(200).body("experimentTemplate.arn", equalTo(arn));
        refused(request(user, "fis", "GET", "/experimentTemplates/EXTother", null), "fis:GetExperimentTemplate");
    }

    @Test
    void fisSafetyLeverUpdateIsCheckedOnTheLever() {
        String user = user("route-lever");
        String action = "fis:UpdateSafetyLeverState";
        String body = "{\"state\":{\"status\":\"disengaged\",\"reason\":\"route test\"}}";

        policy(user, allow(action, "arn:aws:fis:us-east-1:" + ACCOUNT + ":safety-lever/default"));
        request(user, "fis", "PATCH", "/safetyLevers/default/state", body).statusCode(200);
        policy(user, allow(action, "arn:aws:fis:us-east-1:" + ACCOUNT + ":safety-lever/other"));
        refused(request(user, "fis", "PATCH", "/safetyLevers/default/state", body), action);
    }

    @Test
    void oamIsCheckedOnTheArnItsIdentifiersHold() {
        String sink = adminOf("oam").body("{\"Name\":\"route-sink-" + suffix() + "\"}").post("/CreateSink")
                .then().statusCode(200).extract().path("Arn");
        cleanup.register(() -> adminOf("oam").body("{\"Identifier\":\"" + sink + "\"}").post("/DeleteSink"));
        String link = "arn:aws:oam:us-east-1:" + ACCOUNT + ":link/absent";
        String other = "arn:aws:oam:us-east-1:" + ACCOUNT + ":sink/other";
        String user = user("route-oam");

        policy(user, allow("oam:GetSink", sink));
        request(user, "oam", "POST", "/GetSink", "{\"Identifier\":\"" + sink + "\"}")
                .statusCode(200).body("Arn", equalTo(sink));
        refused(request(user, "oam", "POST", "/GetSink", "{\"Identifier\":\"" + other + "\"}"), "oam:GetSink");

        policy(user, allow("oam:GetSinkPolicy", sink));
        request(user, "oam", "POST", "/GetSinkPolicy", "{\"SinkIdentifier\":\"" + sink + "\"}").statusCode(not(403));
        refused(request(user, "oam", "POST", "/GetSinkPolicy", "{\"SinkIdentifier\":\"" + other + "\"}"),
                "oam:GetSinkPolicy");

        policy(user, allow("oam:GetLink", link));
        request(user, "oam", "POST", "/GetLink", "{\"Identifier\":\"" + link + "\"}").statusCode(404);
        refused(request(user, "oam", "POST", "/GetLink", "{\"Identifier\":\"" + link + "x\"}"), "oam:GetLink");
    }

    @Test
    void oamTagRoutesAreCheckedOnTheArnWhetherItsSlashIsEncodedOrNot() {
        String sink = "arn:aws:oam:us-east-1:" + ACCOUNT + ":sink/allowed";
        String other = "arn:aws:oam:us-east-1:" + ACCOUNT + ":sink/other";
        String user = user("route-oam-tags");
        String tags = "{\"Tags\":{\"team\":\"red\"}}";

        // The tag routes take the ARN as the rest of the path, so a sink's slash may come unencoded too.
        policy(user, allow("oam:TagResource", sink) + "," + allow("oam:ListTagsForResource", sink) + ","
                + allow("oam:UntagResource", sink));
        for (String arn : List.of(sink, encoded(sink))) {
            request(user, "oam", "PUT", "/tags/" + arn, tags).statusCode(not(403));
            request(user, "oam", "GET", "/tags/" + arn, null).statusCode(not(403));
            request(user, "oam", "DELETE", "/tags/" + arn + "?tagKeys=team", null).statusCode(not(403));
        }
        for (String arn : List.of(other, encoded(other))) {
            refused(request(user, "oam", "PUT", "/tags/" + arn, tags), "oam:TagResource");
            refused(request(user, "oam", "GET", "/tags/" + arn, null), "oam:ListTagsForResource");
            refused(request(user, "oam", "DELETE", "/tags/" + arn + "?tagKeys=team", null), "oam:UntagResource");
        }
    }

    @Test
    void ramIsCheckedOnTheResourceShareItNamesInItsBodyAndQuery() {
        String share = adminOf("ram").body("{\"name\":\"route-share-" + suffix() + "\"}").post("/createresourceshare")
                .then().statusCode(200).extract().path("resourceShare.resourceShareArn");
        String other = "arn:aws:ram:us-east-1:" + ACCOUNT + ":resource-share/other";
        String user = user("route-ram");

        policy(user, allow("ram:UpdateResourceShare", share));
        request(user, "ram", "POST", "/updateresourceshare",
                "{\"resourceShareArn\":\"" + share + "\",\"name\":\"route-renamed-" + suffix() + "\"}").statusCode(200);
        refused(request(user, "ram", "POST", "/updateresourceshare", "{\"resourceShareArn\":\"" + other + "\"}"),
                "ram:UpdateResourceShare");

        policy(user, allow("ram:DeleteResourceShare", share));
        refused(request(user, "ram", "DELETE", "/deleteresourceshare?resourceShareArn=" + encoded(other), null),
                "ram:DeleteResourceShare");
        request(user, "ram", "DELETE", "/deleteresourceshare?resourceShareArn=" + encoded(share), null).statusCode(200);
    }

    @Test
    void ramCreateIsCheckedOnTheShareItMakesAndThePermissionsItNames() {
        String user = user("route-ram-create");
        String action = "ram:CreateResourceShare";
        String shares = "arn:aws:ram:us-east-1:" + ACCOUNT + ":resource-share/*";
        String permission = "arn:aws:ram::aws:permission/AWSRAMDefaultPermissionSubnet";
        String named = "{\"permissionArns\":[\"" + permission + "\"]}";

        policy(user, allow(action, shares));
        madeShare(request(user, "ram", "POST", "/createresourceshare", "{}"));
        refused(request(user, "ram", "POST", "/createresourceshare", named), action);

        policy(user, allow(action, shares) + "," + allow(action, permission));
        madeShare(request(user, "ram", "POST", "/createresourceshare", named));

        policy(user, allow(action, permission));
        refused(request(user, "ram", "POST", "/createresourceshare", named), action);

        // Its tags go on the share it makes.
        policy(user, allow(action, shares) + "," + allow("ram:TagResource", shares));
        madeShare(request(user, "ram", "POST", "/createresourceshare",
                "{\"tags\":[{\"key\":\"team\",\"value\":\"red\"}]}"));
    }

    @Test
    void resourceExplorerIsCheckedOnTheViewItsMembersName() {
        String service = "resource-explorer-2";
        String view = adminOf(service).body("{\"ViewName\":\"route-view-" + suffix() + "\"}").post("/CreateView")
                .then().statusCode(200).extract().path("View.ViewArn");
        cleanup.register(() -> adminOf(service).body("{\"ViewArn\":\"" + view + "\"}").post("/DeleteView"));
        String other = "arn:aws:resource-explorer-2:us-east-1:" + ACCOUNT + ":view/other/1";
        String user = user("route-re2-view");

        policy(user, allow("resource-explorer-2:GetView", view));
        request(user, service, "POST", "/GetView", "{\"ViewArn\":\"" + view + "\"}")
                .statusCode(200).body("View.ViewArn", equalTo(view));
        refused(request(user, service, "POST", "/GetView", "{\"ViewArn\":\"" + other + "\"}"),
                "resource-explorer-2:GetView");
    }

    @Test
    void createsActingOnNoResourceTypeAreCheckedForTheirTagsOnWhatTheyMake() {
        String service = "resource-explorer-2";
        String user = user("route-made-tags");
        String views = "arn:aws:resource-explorer-2:us-east-1:" + ACCOUNT + ":view/*";
        String indexes = "arn:aws:resource-explorer-2:eu-west-2:" + ACCOUNT + ":index/*";
        String sinks = "arn:aws:oam:us-east-1:" + ACCOUNT + ":sink/*";
        String tags = "\"Tags\":{\"team\":\"red\"}";
        String creates = allow("resource-explorer-2:CreateView", "*") + "," + allow("resource-explorer-2:CreateIndex", "*")
                + "," + allow("oam:CreateSink", "*");

        // A tagging grant scoped to the type a create makes lets its tags through.
        policy(user, creates + "," + allow("resource-explorer-2:TagResource", views) + ","
                + allow("resource-explorer-2:TagResource", indexes) + "," + allow("oam:TagResource", sinks));
        String view = request(user, service, "POST", "/CreateView",
                "{\"ViewName\":\"route-made-" + suffix() + "\"," + tags + "}").statusCode(200).extract().path("View.ViewArn");
        cleanup.register(() -> adminOf(service).body("{\"ViewArn\":\"" + view + "\"}").post("/DeleteView"));
        String sink = request(user, "oam", "POST", "/CreateSink", "{\"Name\":\"route-made-" + suffix() + "\"," + tags + "}")
                .statusCode(200).extract().path("Arn");
        cleanup.register(() -> adminOf("oam").body("{\"Identifier\":\"" + sink + "\"}").post("/DeleteSink"));
        Response index = as(user, "eu-west-2", service).body("{" + tags + "}").post("/CreateIndex");
        index.then().statusCode(not(403));
        if (index.statusCode() == 200) {
            String arn = index.path("Arn");
            cleanup.register(() -> given().header("Authorization", auth(ADMIN, "eu-west-2", service))
                    .contentType("application/json").body("{\"Arn\":\"" + arn + "\"}").post("/DeleteIndex"));
        }

        // A grant on another type, or a deny on the type made, refuses them.
        policy(user, creates + "," + allow("resource-explorer-2:TagResource", indexes.replace("eu-west-2", "us-east-1"))
                + "," + allow("oam:TagResource", "*") + "," + deny("oam:TagResource", sinks));
        refused(request(user, service, "POST", "/CreateView", "{\"ViewName\":\"route-made-" + suffix() + "\"," + tags + "}"),
                "resource-explorer-2:TagResource");
        refused(as(user, "eu-west-2", service).body("{" + tags + "}").post("/CreateIndex").then(),
                "resource-explorer-2:TagResource");
        refused(request(user, "oam", "POST", "/CreateSink", "{\"Name\":\"route-made-" + suffix() + "\"," + tags + "}"),
                "oam:TagResource");
    }

    @Test
    void oamCreateLinkIsCheckedOnTheSinkOfTheAccountItsArnNamesAlone() {
        String sink = given().header("Authorization", auth(PEER, "oam")).contentType("application/json")
                .body("{\"Name\":\"route-peer-" + suffix() + "\"}").post("/CreateSink")
                .then().statusCode(200).extract().path("Arn");
        cleanup.register(() -> given().header("Authorization", auth(PEER, "oam")).contentType("application/json")
                .body("{\"Identifier\":\"" + sink + "\"}").post("/DeleteSink"));
        String sinkPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\""
                + ACCOUNT + "\"]},\"Action\":[\"oam:CreateLink\"],\"Resource\":\"*\"}]}";
        given().header("Authorization", auth(PEER, "oam")).contentType("application/json")
                .body("{\"SinkIdentifier\":\"" + sink + "\",\"Policy\":\"" + sinkPolicy.replace("\"", "\\\"") + "\"}")
                .post("/PutSinkPolicy").then().statusCode(200);
        String user = user("route-oam-link");
        String action = "oam:CreateLink";
        String body = "{\"SinkIdentifier\":\"" + sink + "\",\"LabelTemplate\":\"$AccountName\","
                + "\"ResourceTypes\":[\"AWS::CloudWatch::Metric\"]}";

        // The sink is the other account's, which the link is made to: a grant on a sink of that name of the caller's
        // own is no grant on it.
        policy(user, allow(action, sink.replace(PEER, ACCOUNT)));
        refused(request(user, "oam", "POST", "/CreateLink", body), action);
        policy(user, allow(action, "*") + "," + deny(action, sink));
        refused(request(user, "oam", "POST", "/CreateLink", body), action);
        policy(user, allow(action, sink));
        String link = request(user, "oam", "POST", "/CreateLink", body).statusCode(200).extract().path("Arn");
        cleanup.register(() -> adminOf("oam").body("{\"Identifier\":\"" + link + "\"}").post("/DeleteLink"));
    }

    @Test
    void ramInvitationIsCheckedOnTheInvitationOfTheAccountThatSentIt() {
        String share = given().header("Authorization", auth(PEER, "ram")).contentType("application/json")
                .body("{\"name\":\"route-invite-" + suffix() + "\",\"principals\":[\"" + ACCOUNT + "\"]}")
                .post("/createresourceshare").then().statusCode(200).extract().path("resourceShare.resourceShareArn");
        cleanup.register(() -> given().header("Authorization", auth(PEER, "ram")).queryParam("resourceShareArn", share)
                .delete("/deleteresourceshare"));
        String invitation = adminOf("ram").body("{\"resourceShareArns\":[\"" + share + "\"]}")
                .post("/getresourceshareinvitations").then().statusCode(200)
                .extract().path("resourceShareInvitations[0].resourceShareInvitationArn");
        String user = user("route-ram-invite");
        String action = "ram:AcceptResourceShareInvitation";
        String body = "{\"resourceShareInvitationArn\":\"" + invitation + "\"}";

        policy(user, allow(action, invitation.replace(PEER, ACCOUNT)));
        refused(request(user, "ram", "POST", "/acceptresourceshareinvitation", body), action);
        policy(user, allow(action, invitation));
        request(user, "ram", "POST", "/acceptresourceshareinvitation", body).statusCode(200)
                .body("resourceShareInvitation.status", equalTo("ACCEPTED"));
        // The share it accepted is the other account's, whose permissions it reads there.
        policy(user, allow("ram:ListResourceSharePermissions", share));
        request(user, "ram", "POST", "/listresourcesharepermissions", "{\"resourceShareArn\":\"" + share + "\"}")
                .statusCode(200);
    }

    @Test
    void fisTemplatesAreCheckedOnTheActionsTheyName() {
        String user = user("route-fis-actions");
        String create = "fis:CreateExperimentTemplate";
        String update = "fis:UpdateExperimentTemplate";
        String wait = "arn:aws:fis:us-east-1:" + ACCOUNT + ":action/aws:fis:wait";
        String templates = "arn:aws:fis:us-east-1:" + ACCOUNT + ":experiment-template/*";
        String template = "{\"clientToken\":\"" + suffix() + "\",\"description\":\"d\",\"roleArn\":\"arn:aws:iam::"
                + ACCOUNT + ":role/fis\",\"stopConditions\":[{\"source\":\"none\"}],\"targets\":{},\"actions\":"
                + "{\"wait\":{\"actionId\":\"aws:fis:wait\",\"parameters\":{\"duration\":\"PT1M\"}}}}";
        String actions = "{\"actions\":{\"wait\":{\"actionId\":\"aws:fis:wait\",\"parameters\":{\"duration\":\"PT2M\"}}}}";
        String passRole = allow("iam:PassRole", "*");

        policy(user, allow(create, "*") + "," + deny(create, wait) + "," + passRole);
        refused(request(user, "fis", "POST", "/experimentTemplates", template), create);
        policy(user, allow(create, templates) + "," + passRole);
        refused(request(user, "fis", "POST", "/experimentTemplates", template), create);
        policy(user, allow(create, templates) + "," + allow(create, wait) + "," + passRole);
        String id = request(user, "fis", "POST", "/experimentTemplates", template)
                .statusCode(200).extract().path("experimentTemplate.id");
        cleanup.register(() -> adminOf("fis").delete("/experimentTemplates/" + id));

        policy(user, allow(update, "*") + "," + deny(update, wait));
        refused(request(user, "fis", "PATCH", "/experimentTemplates/" + id, actions), update);
        policy(user, allow(update, templates) + "," + allow(update, wait));
        request(user, "fis", "PATCH", "/experimentTemplates/" + id, actions).statusCode(200);
    }

    @Test
    void resourceExplorerSearchesNamingNoViewAreCheckedOnTheDefaultView() {
        String service = "resource-explorer-2";
        String view = adminOf(service).body("{}").post("/GetDefaultView").then().statusCode(200).extract().path("ViewArn");
        String other = "arn:aws:resource-explorer-2:us-east-1:" + ACCOUNT + ":view/other/1";
        String user = user("route-re2-default");
        String action = "resource-explorer-2:Search";
        String search = "{\"QueryString\":\"\"}";

        policy(user, allow(action, view));
        request(user, service, "POST", "/Search", search).statusCode(200);
        request(user, service, "POST", "/ListResources", "{}").statusCode(200);
        policy(user, allow(action, other));
        refused(request(user, service, "POST", "/Search", search), action);
        refused(request(user, service, "POST", "/ListResources", "{}"), action);
        policy(user, allow(action, "*") + "," + deny(action, view));
        refused(request(user, service, "POST", "/Search", search), action);
        refused(request(user, service, "POST", "/ListResources", "{}"), action);
    }

    @Test
    void createsAreCheckedWithTheTagsTheyCarry() {
        // These three name too little to be created, so past the check the service refuses them; RAM makes the share.
        Consumer<ValidatableResponse> refusedByTheService = response -> response.statusCode(400);
        createIsCheckedWithItsTags("fis", "/experimentTemplates", "fis:CreateExperimentTemplate", "fis:TagResource",
                team -> "{\"tags\":{\"team\":\"" + team + "\"}}", refusedByTheService);
        createIsCheckedWithItsTags("oam", "/CreateSink", "oam:CreateSink", "oam:TagResource",
                team -> "{\"Tags\":{\"team\":\"" + team + "\"}}", refusedByTheService);
        createIsCheckedWithItsTags("resource-explorer-2", "/CreateView", "resource-explorer-2:CreateView",
                "resource-explorer-2:TagResource", team -> "{\"Tags\":{\"team\":\"" + team + "\"}}",
                refusedByTheService);
        createIsCheckedWithItsTags("ram", "/createresourceshare", "ram:CreateResourceShare", "ram:TagResource",
                team -> "{\"tags\":[{\"key\":\"team\",\"value\":\"" + team + "\"}]}", this::madeShare);
    }

    /**
     * An untagged request needs only the create, one with tags the tagging action too, and a deny on a tag refuses
     * that tag and no other. {@code passed} takes a response that got past the check.
     */
    private void createIsCheckedWithItsTags(String service, String path, String action, String tagging,
                                            UnaryOperator<String> body, Consumer<ValidatableResponse> passed) {
        String user = user("route-tags-" + service);

        policy(user, allow(action, "*"));
        passed.accept(request(user, service, "POST", path, "{}"));
        refused(request(user, service, "POST", path, body.apply("red")), tagging);

        policy(user, allow(action, "*") + "," + allow(tagging, "*") + ",{\"Effect\":\"Deny\",\"Action\":\"" + action
                + "\",\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"red\"}}}");
        passed.accept(request(user, service, "POST", path, body.apply("blue")));
        refused(request(user, service, "POST", path, body.apply("red")), action);
    }

    /** A create that got past the check and made a resource share, removed afterwards. */
    private void madeShare(ValidatableResponse response) {
        String share = response.statusCode(200).extract().path("resourceShare.resourceShareArn");
        cleanup.register(() -> adminOf("ram").queryParam("resourceShareArn", share).delete("/deleteresourceshare"));
    }

    /** Refuses a request until the caller holds the action, then lets it past the check. */
    private void checkedAs(String user, String service, String method, String path, String body, String action) {
        refused(request(user, service, method, path, body), action);
        policy(user, allow(action, "*"));
        request(user, service, method, path, body).statusCode(not(403));
    }

    private ValidatableResponse request(String user, String service, String method, String path, String body) {
        RequestSpecification request = as(user, service).urlEncodingEnabled(false);
        if (body != null) {
            request.body(body);
        }
        return request.request(method, path).then();
    }

    private static RequestSpecification adminOf(String service) {
        return given().header("Authorization", auth(ADMIN, service)).contentType("application/json");
    }

    /** Creates a MicroVM image as the account's admin, removed afterwards, and returns its ARN. */
    private String microvmImage(String name) {
        admin().body("""
                {"name":"%s","baseImageArn":"arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                 "buildRoleArn":"arn:aws:iam::%s:role/route-build","codeArtifact":{"uri":"s3://bucket/code.zip"}}"""
                .formatted(name, ACCOUNT)).post("/2025-09-09/microvm-images").then().statusCode(201);
        cleanup.register(() -> admin().delete("/2025-09-09/microvm-images/" + name));
        return "arn:aws:lambda:us-east-1:" + ACCOUNT + ":microvm-image:" + name;
    }

    /** A {@code CreateAnalyzer} body of a type Access Analyzer refuses, so a request let through creates nothing. */
    private static String createAnalyzer(String analyzer) {
        return "{\"analyzerName\":\"" + analyzer + "\",\"type\":\"NONE\"}";
    }

    /** An Access Analyzer request as the account's admin. */
    private static RequestSpecification accessAnalyzer() {
        return given().header("Authorization", auth(ADMIN, "access-analyzer")).contentType("application/json");
    }

    private static RequestSpecification admin() {
        return adminOf("lambda");
    }

    /** Macie's {@code CreateMember} for {@code accountId}, with a {@code team} tag unless {@code team} is null. */
    private ValidatableResponse createMember(String user, String accountId, String team) {
        String tags = team == null ? "" : ",\"tags\":{\"team\":\"" + team + "\"}";
        return as(user, "macie2")
                .body("{\"account\":{\"accountId\":\"" + accountId + "\",\"email\":\"m@example.com\"}" + tags + "}")
                .post("/members").then();
    }

    private static String deny(String action, String resource) {
        return "{\"Effect\":\"Deny\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static void refused(ValidatableResponse response, String action) {
        response.statusCode(403).body(containsString(action)).body(not(containsString("iam:PassRole")));
    }

    private static String allow(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ADMIN, "iam"), user, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    /** Creates a user, removed afterwards, and returns its access key ID under the user's name. */
    private String user(String name) {
        String userName = name + "-" + suffix();
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"), userName)
                .accessKeyId();
        keys.put(userName, accessKeyId);
        return userName;
    }

    private RequestSpecification as(String user, String service) {
        return as(user, "us-east-1", service);
    }

    private RequestSpecification as(String user, String region, String service) {
        return given().header("Authorization", auth(keys.get(user), region, service)).contentType("application/json");
    }

    /** A Lambda request as {@code user}, with an empty JSON body on a write. */
    private ValidatableResponse send(String user, String method, String path) {
        RequestSpecification request = as(user, "lambda");
        if ("POST".equals(method) || "PUT".equals(method)) {
            request.body("{}");
        }
        return request.request(method, path).then();
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
