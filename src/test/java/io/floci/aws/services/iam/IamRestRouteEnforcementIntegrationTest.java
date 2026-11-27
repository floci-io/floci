package io.floci.aws.services.iam;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.AwsTestClients;
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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.EksException;
import software.amazon.awssdk.services.eks.model.ResourceNotFoundException;

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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    /** The XML namespace of CloudFront's 2020-05-31 control plane requests. */
    private static final String CLOUDFRONT_XMLNS = "http://cloudfront.amazonaws.com/doc/2020-05-31/";

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
        KeyValueStoreFixture created = keyValueStore();
        String name = created.name();
        String arn = created.arn();
        String etag = created.etag();

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

    /**
     * An {@code aws:ResourceTag} condition reads the tags CloudFront holds on the key value store a request names, so
     * an allow on one team's tag lets the caller describe that team's store and not another's.
     */
    @Test
    void aCloudFrontResourceTagConditionReadsTheTagsOfTheKeyValueStore() {
        String user = user("route-kvs-tags");
        KeyValueStoreFixture tagged = keyValueStore();
        KeyValueStoreFixture other = keyValueStore();
        tagCloudFront(tagged.arn(), "data");
        tagCloudFront(other.arn(), "web");

        policy(user, "{\"Effect\":\"Allow\",\"Action\":\"cloudfront:DescribeKeyValueStore\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        as(user, "cloudfront").get("/2020-05-31/key-value-store/" + tagged.name()).then().statusCode(200);
        refused(as(user, "cloudfront").get("/2020-05-31/key-value-store/" + other.name()).then(),
                "cloudfront:DescribeKeyValueStore");
    }

    /** A CloudFront key value store an administrator created, by its name, ARN and control plane ETag. */
    private record KeyValueStoreFixture(String name, String arn, String etag) {}

    /** A key value store an administrator creates, deleted after the test. */
    private KeyValueStoreFixture keyValueStore() {
        String name = "kvs-" + suffix();
        Response created = given().header("Authorization", auth(ADMIN, "cloudfront"))
                .contentType("application/xml")
                .body("<CreateKeyValueStoreRequest xmlns=\"" + CLOUDFRONT_XMLNS + "\"><Name>" + name
                        + "</Name></CreateKeyValueStoreRequest>")
                .post("/2020-05-31/key-value-store");
        created.then().statusCode(201);
        String etag = created.header("ETag");
        cleanup.register(() -> given().header("Authorization", auth(ADMIN, "cloudfront")).header("If-Match", etag)
                .delete("/2020-05-31/key-value-store/" + name).then().statusCode(204));
        return new KeyValueStoreFixture(name, XmlParser.extractFirst(created.asString(), "ARN", null), etag);
    }

    /** Tags the CloudFront resource {@code arn} with the team, as an administrator. */
    private static void tagCloudFront(String arn, String team) {
        given().header("Authorization", auth(ADMIN, "cloudfront")).contentType("application/xml")
                .queryParam("Operation", "Tag").queryParam("Resource", arn)
                .body("<Tags xmlns=\"" + CLOUDFRONT_XMLNS + "\"><Items><Tag><Key>team</Key><Value>" + team
                        + "</Value></Tag></Items></Tags>")
                .post("/2020-05-31/tagging").then().statusCode(204);
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
    void schedulerTaggingIsCheckedOnTheGroupItsModeledRouteNamesAndServedOnNoOtherRoute() {
        // Scheduler tags the group whose ARN its modeled /tags/{ResourceArn} carries, which the generated request
        // resources read, so a Deny on that group holds against an Allow on every resource. The shared controller's
        // bare POST /tags is no route of Scheduler's model, as AWS gives it none: let through, it is refused as an
        // ARN that path does not serve, and tags nothing.
        String user = user("route-scheduler-tags");
        String group = scheduleGroup("route-group-" + suffix());
        String tagsPath = "/tags/" + encoded(group);
        String tags = "{\"Tags\":[{\"Key\":\"team\",\"Value\":\"a\"}]}";

        policy(user, allow("scheduler:ListTagsForResource", "*"));
        refused(as(user, "scheduler").urlEncodingEnabled(false).body(tags).post(tagsPath).then(),
                "scheduler:TagResource");
        policy(user, allow("scheduler:TagResource", group));
        // Allowed on that group, the request reaches Scheduler, which has no such group.
        as(user, "scheduler").urlEncodingEnabled(false).body(tags).post(tagsPath).then().statusCode(not(403));
        policy(user, allow("scheduler:TagResource", "*") + "," + deny("scheduler:TagResource", group));
        refused(as(user, "scheduler").urlEncodingEnabled(false).body(tags).post(tagsPath).then(),
                "scheduler:TagResource");
        as(user, "scheduler").body(tagBody(group)).post("/tags").then().statusCode(400)
                .body(containsString("Invalid resource ARN"));
    }

    /** A resource-tag condition reads the tags Scheduler holds on the schedule group a request names. */
    @Test
    void aResourceTagConditionReadsTheTagsOfTheScheduleGroup() {
        String tagged = "route-tagged-" + suffix();
        String untagged = "route-untagged-" + suffix();
        for (String[] group : new String[][] {{tagged, "{\"Tags\":[{\"Key\":\"team\",\"Value\":\"data\"}]}"},
                {untagged, "{}"}}) {
            given().header("Authorization", auth(ADMIN, "scheduler")).contentType("application/json").body(group[1])
                    .post("/schedule-groups/" + group[0]).then().statusCode(200);
            cleanup.register(() -> given().header("Authorization", auth(ADMIN, "scheduler"))
                    .delete("/schedule-groups/" + group[0]).then().statusCode(200));
        }
        String user = user("route-scheduler-tag-condition");

        policy(user, "{\"Effect\":\"Allow\",\"Action\":\"scheduler:GetScheduleGroup\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        as(user, "scheduler").get("/schedule-groups/" + tagged).then().statusCode(200);
        refused(as(user, "scheduler").get("/schedule-groups/" + untagged).then(), "scheduler:GetScheduleGroup");
    }

    @Test
    void postTagsIsCheckedAgainstTheArnItsBodyNames() {
        // POST /tags tags the ARN its body names: one named in the query does not move the check. FIS's generated
        // routes give the bare path the shared tags controller also serves it on.
        String user = user("route-tags-body");
        String allowed = "arn:aws:fis:us-east-1:" + ACCOUNT + ":experiment-template/EXTallowed" + suffix();
        String other = "arn:aws:fis:us-east-1:" + ACCOUNT + ":experiment-template/EXTother" + suffix();

        policy(user, allow("fis:TagResource", allowed));
        refused(as(user, "fis").queryParam("resourceArn", allowed).body(tagBody(other)).post("/tags").then(),
                "fis:TagResource");
    }

    /**
     * A Pipes route is checked on the pipe its path names, from the generated table, and an {@code aws:ResourceTag}
     * condition reads the tags Pipes holds on it, so an allow on one team's tag lets the caller describe that team's
     * pipe and not another's.
     */
    @Test
    void aPipesResourceTagConditionReadsTheTagsOfThePipe() {
        String tagged = pipe("route-pipe-" + suffix(), "data");
        String other = pipe("route-pipe-" + suffix(), "web");
        String user = user("route-pipe-tags");

        policy(user, onTeam("Allow", "pipes:DescribePipe", "data"));
        request(user, "pipes", "GET", "/v1/pipes/" + tagged, null).statusCode(200);
        refused(request(user, "pipes", "GET", "/v1/pipes/" + other, null), "pipes:DescribePipe");
    }

    /** A stopped pipe an administrator creates between two queue ARNs, tagged with {@code team}; its name. */
    private String pipe(String name, String team) {
        adminOf("pipes").body("{\"Source\":\"arn:aws:sqs:us-east-1:" + ACCOUNT + ":route-source\",\"Target\":"
                + "\"arn:aws:sqs:us-east-1:" + ACCOUNT + ":route-target\",\"RoleArn\":\"arn:aws:iam::" + ACCOUNT
                + ":role/route-pipe\",\"DesiredState\":\"STOPPED\",\"Tags\":{\"team\":\"" + team + "\"}}")
                .post("/v1/pipes/" + name).then().statusCode(200);
        cleanup.register(() -> adminOf("pipes").delete("/v1/pipes/" + name).then().statusCode(200));
        return name;
    }

    @Test
    void sharedTaggingIsCheckedAsTheServiceItsArnNames() {
        // The shared tagging routes hand the request to the service its ARN names, whatever scope it is
        // signed with: a Pipes grant does not let the caller tag a schedule group, and a scope with no
        // tagging rule does not let the request through unchecked.
        String user = user("route-tags-scope");
        String group = scheduleGroup("route-group-" + suffix());
        String queue = "arn:aws:batch:us-east-1:" + ACCOUNT + ":job-queue/route-queue-" + suffix();
        String tags = "{\"Tags\":[{\"Key\":\"team\",\"Value\":\"a\"}]}";

        // Nor is an ARN of a service with no tagging rule checked as the signing scope's action.
        as(user, "pipes").body(tagBody(queue)).post("/tags").then().body(not(containsString("pipes:TagResource")));
        policy(user, allow("pipes:TagResource", "*"));
        refused(as(user, "pipes").urlEncodingEnabled(false).body(tags).post("/tags/" + encoded(group)).then(),
                "scheduler:TagResource");
        refused(as(user, "sqs").urlEncodingEnabled(false).body(tags).post("/tags/" + encoded(group)).then(),
                "scheduler:TagResource");
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

    /** A resource-tag condition reads the tags EKS holds on the cluster a request names. */
    @Test
    void anEksResourceTagConditionReadsTheTagsOfTheCluster() {
        String tagged = "route-eks-data-" + suffix();
        String other = "route-eks-web-" + suffix();
        eksCluster(tagged, "{\"team\":\"data\"}");
        eksCluster(other, "{\"team\":\"web\"}");
        String user = user("route-eks-tags");

        policy(user, onTeam("Allow", "eks:DescribeCluster", "data"));
        request(user, "eks", "GET", "/clusters/" + tagged, null).statusCode(200);
        refused(request(user, "eks", "GET", "/clusters/" + other, null), "eks:DescribeCluster");
    }

    @Test
    void deletedEksAddonRemainsAuthorizedForItsScopedDescribeWaiter() {
        String name = "addon-waiter-" + suffix();
        String cluster = eksCluster(name);
        String base = "/clusters/" + name + "/addons";
        String arn = eks().body("{\"addonName\":\"vpc-cni\"}").post(base).then().statusCode(200)
                .extract().path("addon.addonArn");
        String user = "addon-waiter-user-" + suffix();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, baseUri.getPort(), auth(ADMIN, "iam"), user);
        // The Service Reference lists the cluster's Pod Identity associations for DeleteAddon, beside the add-on.
        policy(user, allow("eks:DescribeAddon", arn) + "," + allow("eks:DeleteAddon", arn) + ","
                + allow("eks:DeleteAddon", cluster.replace(":cluster/", ":podidentityassociation/") + "/*"));
        try (EksClient client = AwsTestClients.create(EksClient.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.US_EAST_1, baseUri)) {
            assertEquals(arn, client.describeAddon(r -> r.clusterName(name).addonName("vpc-cni")).addon().addonArn());
            client.deleteAddon(r -> r.clusterName(name).addonName("vpc-cni"));
            assertThrows(ResourceNotFoundException.class,
                    () -> client.describeAddon(r -> r.clusterName(name).addonName("vpc-cni")));
            policy(user, allow("eks:DescribeAddon", arn + "-foreign"));
            assertEquals(403, assertThrows(EksException.class,
                    () -> client.describeAddon(r -> r.clusterName(name).addonName("vpc-cni"))).statusCode());
            policy(user, allow("eks:DescribeAddon", "*") + "," + deny("eks:DescribeAddon", arn));
            assertEquals(403, assertThrows(EksException.class,
                    () -> client.describeAddon(r -> r.clusterName(name).addonName("vpc-cni"))).statusCode());
        }
    }

    @Test
    void deletedEksFargateProfileRemainsAuthorizedForItsScopedDescribeWaiter() {
        String name = "fargate-waiter-" + suffix();
        eksCluster(name);
        String path = "/clusters/" + name + "/fargate-profiles";
        String arn = eks().body("{\"fargateProfileName\":\"fp\",\"podExecutionRoleArn\":\"arn:aws:iam::"
                + ACCOUNT + ":role/route-fargate\"}").post(path).then().statusCode(200)
                .extract().path("fargateProfile.fargateProfileArn");
        String user = "fargate-waiter-user-" + suffix();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, baseUri.getPort(), auth(ADMIN, "iam"), user);
        policy(user, allow("eks:DescribeFargateProfile", arn) + "," + allow("eks:DeleteFargateProfile", arn));
        try (EksClient client = AwsTestClients.create(EksClient.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.US_EAST_1, baseUri)) {
            assertEquals(arn, client.describeFargateProfile(r -> r.clusterName(name).fargateProfileName("fp"))
                    .fargateProfile().fargateProfileArn());
            client.deleteFargateProfile(r -> r.clusterName(name).fargateProfileName("fp"));
            assertThrows(ResourceNotFoundException.class,
                    () -> client.describeFargateProfile(r -> r.clusterName(name).fargateProfileName("fp")));
            policy(user, allow("eks:DescribeFargateProfile", arn + "-foreign"));
            assertEquals(403, assertThrows(EksException.class,
                    () -> client.describeFargateProfile(r -> r.clusterName(name).fargateProfileName("fp"))).statusCode());
            policy(user, allow("eks:DescribeFargateProfile", "*") + "," + deny("eks:DescribeFargateProfile", arn));
            assertEquals(403, assertThrows(EksException.class,
                    () -> client.describeFargateProfile(r -> r.clusterName(name).fargateProfileName("fp"))).statusCode());
        }
    }

    @Test
    void eachResourceAnEksClusterHoldsIsCheckedAsTheGeneratedTableReadsIt() {
        // A Fargate profile and an add-on, whose ARNs end in an id EKS minted, are found as EKS finds them, so a Deny
        // on their own ARN applies where the action is allowed on every resource, and a grant on that ARN alone
        // covers them. An update is checked on its cluster, and an access entry or a Pod Identity association with the
        // parts of its ARN the request does not give open, so a policy names those with a wildcard.
        String user = user("route-eks-held");
        String name = "route-held-" + suffix();
        String base = "/clusters/" + name;
        String cluster = eksCluster(name);
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
        eks().body("{\"principalArn\":\"" + role + "\"}").post(base + "/access-entries").then().statusCode(200);
        String association = eks().body("{\"namespace\":\"default\",\"serviceAccount\":\"app\",\"roleArn\":\"" + role
                + "\"}").post(base + "/pod-identity-associations").then().statusCode(200)
                .extract().path("association.associationArn");
        String[][] routes = {
            {"eks:DescribeFargateProfile", base + "/fargate-profiles/fp", profile},
            {"eks:DescribeAddon", base + "/addons/vpc-cni", addon},
            {"eks:DescribeUpdate", base + "/updates/" + update, cluster},
            {"eks:DescribeAccessEntry", base + "/access-entries/" + encoded(role),
                cluster.replace(":cluster/", ":access-entry/") + "/*"},
            {"eks:DescribePodIdentityAssociation",
                base + "/pod-identity-associations/" + association.substring(association.lastIndexOf('/') + 1),
                cluster.replace(":cluster/", ":podidentityassociation/") + "/*"},
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
        // Associating, listing and disassociating an entry's access policies act on the cluster's entries, the parts
        // of an entry's ARN the request does not give open, so a grant on another cluster's entries does not cover
        // them, and one on this cluster's does.
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
        eks().body("{\"principalArn\":\"" + otherRole + "\"}").post(base).then().statusCode(200);
        String entries = entry.substring(0, entry.indexOf(":access-entry/")) + ":access-entry/" + name + "/*";
        String other = entries.replace("/" + name + "/", "/" + name + "-other/");
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

        policy(user, "{\"Effect\":\"Allow\",\"Action\":" + actions + ",\"Resource\":\"" + entries + "\"}");
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
    void eksTaggingIsCheckedOnTheArnItCarriesAndTagsOnlyThatResource() {
        // A tagging request is checked on the ARN its modeled route carries, and on the caller's resource of that name
        // when the ARN names another account; EKS tags only the resource whose ARN is the whole ARN, so another
        // account's ARN tags nothing.
        String user = user("route-eks-tags");
        String cluster = eksCluster("route-tagged-" + suffix());
        String foreign = cluster.replace(ACCOUNT, "111122223333");
        String body = "{\"tags\":{\"team\":\"a\"}}";

        policy(user, allow("eks:TagResource", foreign));
        refused(as(user, "eks").urlEncodingEnabled(false).body(body).post("/tags/" + encoded(foreign)).then(),
                "eks:TagResource");
        policy(user, allow("eks:TagResource", foreign) + "," + allow("eks:TagResource", cluster));
        as(user, "eks").urlEncodingEnabled(false).body(body).post("/tags/" + encoded(foreign)).then().statusCode(404);
        policy(user, allow("eks:TagResource", cluster));
        as(user, "eks").urlEncodingEnabled(false).body(body).post("/tags/" + encoded(cluster)).then().statusCode(200);
        eks().urlEncodingEnabled(false).get("/tags/" + encoded(cluster)).then().statusCode(200)
                .body("tags.team", equalTo("a"));
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

    @Test
    void aListCallAwsChecksOnItsServicesResourcesIsAllowedByAPolicyScopedToThem() {
        // AWS checks these list calls on every resource of the type, not on *, as measured in #9039: a policy on
        // the type's wildcard allows the call, and one on a single resource of the type does not.
        String user = user("route-measured-list");
        String[][] calls = {
                {"es:ListDomainNames", "es", "/2021-01-01/domain", "arn:aws:es:us-east-1:" + ACCOUNT + ":domain/"},
                {"s3tables:ListTableBuckets", "s3tables", "/buckets",
                        "arn:aws:s3tables:us-east-1:" + ACCOUNT + ":bucket/"},
                {"eks:ListClusters", "eks", "/clusters", "arn:aws:eks:us-east-1:" + ACCOUNT + ":cluster/"}};
        for (String[] call : calls) {
            policy(user, allow(call[0], call[3] + "*"));
            as(user, call[1]).get(call[2]).then().statusCode(200);
            policy(user, allow(call[0], call[3] + "one"));
            refused(as(user, call[1]).get(call[2]).then(), call[0]);
        }
    }

    /** Creates an EKS cluster as the account's admin, removed afterwards, and returns its ARN. */
    private String eksCluster(String name) {
        return eksCluster(name, "{}");
    }

    /** A cluster an administrator creates with {@code tags}, a JSON map, removed afterwards; its ARN. */
    private String eksCluster(String name, String tags) {
        String arn = eks().body("{\"name\":\"" + name + "\",\"roleArn\":\"arn:aws:iam::" + ACCOUNT
                + ":role/route-cluster\",\"accessConfig\":{\"authenticationMode\":\"API\"},\"tags\":" + tags + "}")
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
    void lambdaTaggingByAnotherAccountsArnIsCheckedOnThatArnAndTheCallersOwnResourceOfItsName() {
        // The ARN names another account's function or MicroVM image, which is checked, and Lambda tags the caller's
        // own of that name, which is checked too, so a grant on either alone does not cover it; one on the caller's
        // own covers a request naming its own ARN.
        String user = user("route-lambda-tags-account");
        String name = "route-tagged-" + suffix();
        String body = "{\"Tags\":{\"team\":\"a\"}}";

        for (String type : new String[] {"function:", "microvm-image:"}) {
            String own = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":" + type + name;
            String foreign = "arn:aws:lambda:us-east-1:111122223333:" + type + name;
            policy(user, allow("lambda:TagResource", foreign));
            refused(as(user, "lambda").body(body).post("/2017-03-31/tags/" + foreign).then(), "lambda:TagResource");
            policy(user, allow("lambda:TagResource", own));
            refused(as(user, "lambda").body(body).post("/2017-03-31/tags/" + foreign).then(), "lambda:TagResource");
            as(user, "lambda").body(body).post("/2017-03-31/tags/" + own).then().statusCode(not(403));
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
     * EFS is checked by its vendored routes on the file system a request names: by its id, and through a resource id,
     * which EFS answers with the file system of that id.
     */
    @Test
    void efsIsCheckedOnTheFileSystemItsRequestNames() {
        String fileSystem = createFileSystem();
        String other = createFileSystem();
        String user = user("route-efs");
        String arn = "arn:aws:elasticfilesystem:us-east-1:000000000000:file-system/";
        policy(user, allow("elasticfilesystem:DescribeFileSystems", arn + fileSystem) + ","
                + allow("elasticfilesystem:ListTagsForResource", arn + fileSystem));

        as(user, "elasticfilesystem").queryParam("FileSystemId", fileSystem).get("/2015-02-01/file-systems")
                .then().statusCode(200);
        refused(as(user, "elasticfilesystem").queryParam("FileSystemId", other).get("/2015-02-01/file-systems").then(),
                "elasticfilesystem:DescribeFileSystems");
        as(user, "elasticfilesystem").get("/2015-02-01/resource-tags/" + fileSystem).then().statusCode(200);
        refused(as(user, "elasticfilesystem").get("/2015-02-01/resource-tags/" + other).then(),
                "elasticfilesystem:ListTagsForResource");
    }

    /**
     * A tagged {@code CreateAccessPoint} is checked as its action on the file system its request names, and its tags,
     * as {@code elasticfilesystem:TagResource}, on the access point it makes, whose id is EFS's to mint:
     * {@code access-point/*}. A grant on the file system alone does not tag it.
     */
    @Test
    void efsChecksATaggedAccessPointsTagsOnTheAccessPointItMakes() {
        String fileSystem = createFileSystem();
        String arn = "arn:aws:elasticfilesystem:us-east-1:" + ACCOUNT + ":";
        String body = "{\"ClientToken\":\"route-ap-" + suffix() + "\",\"FileSystemId\":\"" + fileSystem
                + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"data\"}]}";
        String user = user("route-efs-ap");
        policy(user, allow("elasticfilesystem:CreateAccessPoint", arn + "file-system/" + fileSystem) + ","
                + allow("elasticfilesystem:TagResource", arn + "file-system/" + fileSystem));

        refused(as(user, "elasticfilesystem").contentType("application/json").body(body)
                .post("/2015-02-01/access-points").then(), "elasticfilesystem:TagResource");

        policy(user, allow("elasticfilesystem:CreateAccessPoint", arn + "file-system/" + fileSystem) + ","
                + allow("elasticfilesystem:TagResource", arn + "access-point/*"));
        String accessPoint = as(user, "elasticfilesystem").contentType("application/json").body(body)
                .post("/2015-02-01/access-points").then().statusCode(200).extract().path("AccessPointId");
        cleanup.register(() -> adminOf("elasticfilesystem").delete("/2015-02-01/access-points/" + accessPoint));
    }

    /**
     * AppConfig Data's calls are checked by their modeled routes, signed for {@code appconfig} as its SDK signs them:
     * {@code GetLatestConfiguration}'s {@code GET /configuration}, which a route filter moves onto an internal path that
     * records its prefix, and {@code StartConfigurationSession}. A caller holding no grant is refused either, and so is
     * one sending the internal path itself, and one signing for {@code appconfigdata}, its model's own name, which
     * Floci serves too and is checked under the {@code appconfig} its model gives as its signing name.
     */
    @Test
    void appConfigDataCallsAreCheckedByTheirModeledRoutes() {
        String user = user("route-appconfigdata");
        refused(as(user, "appconfig").queryParam("configuration_token", "token").get("/configuration").then(),
                "appconfig:GetLatestConfiguration");
        refused(as(user, "appconfig").queryParam("configuration_token", "token").get("/_appconfigdata/configuration")
                .then(), "appconfig:GetLatestConfiguration");
        refused(as(user, "appconfig").body("{\"ApplicationIdentifier\":\"a\",\"EnvironmentIdentifier\":\"e\","
                + "\"ConfigurationProfileIdentifier\":\"p\"}").post("/configurationsessions").then(),
                "appconfig:StartConfigurationSession");
        refused(as(user, "appconfigdata").body("{\"ApplicationIdentifier\":\"a\",\"EnvironmentIdentifier\":\"e\","
                + "\"ConfigurationProfileIdentifier\":\"p\"}").post("/configurationsessions").then(),
                "appconfig:StartConfigurationSession");
        refused(as(user, "appconfigdata").queryParam("configuration_token", "token").get("/configuration").then(),
                "appconfig:GetLatestConfiguration");
    }

    /**
     * AppConfig Data's {@code GetLatestConfiguration} is checked on the configuration the session its token names is on,
     * which AppConfig Data finds by the token: a grant on another configuration is refused, and a grant on that one
     * lets the poll through.
     */
    @Test
    void getLatestConfigurationIsCheckedOnTheConfigurationItsSessionIsOn() {
        String application = adminOf("appconfig").body("{\"Name\":\"route-poll-" + suffix() + "\"}")
                .post("/applications").then().statusCode(201).extract().path("Id");
        cleanup.register(() -> adminOf("appconfig").delete("/applications/" + application));
        String environment = adminOf("appconfig").body("{\"Name\":\"route-poll-env\"}")
                .post("/applications/" + application + "/environments").then().statusCode(201).extract().path("Id");
        String profile = adminOf("appconfig")
                .body("{\"Name\":\"route-poll-profile\",\"LocationUri\":\"hosted\",\"Type\":\"AWS.Freeform\"}")
                .post("/applications/" + application + "/configurationprofiles").then().statusCode(201)
                .extract().path("Id");
        String token = adminOf("appconfig").body("{\"ApplicationIdentifier\":\"" + application
                        + "\",\"EnvironmentIdentifier\":\"" + environment + "\",\"ConfigurationProfileIdentifier\":\""
                        + profile + "\"}")
                .post("/configurationsessions").then().statusCode(201).extract().path("InitialConfigurationToken");
        String configuration = "arn:aws:appconfig:us-east-1:" + ACCOUNT + ":application/" + application
                + "/environment/" + environment + "/configuration/" + profile;
        String user = user("route-latest-config");

        policy(user, allow("appconfig:GetLatestConfiguration", configuration + "-other"));
        refused(as(user, "appconfig").queryParam("configuration_token", token).get("/configuration").then(),
                "appconfig:GetLatestConfiguration");
        policy(user, allow("appconfig:GetLatestConfiguration", configuration));
        as(user, "appconfig").queryParam("configuration_token", token).get("/configuration").then().statusCode(200);
    }

    /**
     * AppConfig Data's {@code StartConfigurationSession} is checked on the application its {@code ApplicationIdentifier}
     * names, by its id or its name, as AWS's refusal names it by its id: a grant on that application lets the request
     * through to AppConfig Data either way, and a grant on another application does not.
     */
    @Test
    void startConfigurationSessionIsCheckedOnTheApplicationItNames() {
        String name = "route-session-" + suffix();
        String application = adminOf("appconfig").body("{\"Name\":\"" + name + "\"}")
                .post("/applications").then().statusCode(201).extract().path("Id");
        String otherApplication = adminOf("appconfig").body("{\"Name\":\"route-session-" + suffix() + "\"}")
                .post("/applications").then().statusCode(201).extract().path("Id");
        cleanup.register(() -> adminOf("appconfig").delete("/applications/" + application));
        cleanup.register(() -> adminOf("appconfig").delete("/applications/" + otherApplication));
        String user = user("route-appconfig-session");
        policy(user, allow("appconfig:StartConfigurationSession",
                "arn:aws:appconfig:us-east-1:" + ACCOUNT + ":application/" + application));

        // Past the check, AppConfig Data answers that the application has no such environment.
        for (String identifier : new String[] {application, name}) {
            as(user, "appconfig").body("{\"ApplicationIdentifier\":\"" + identifier + "\",\"EnvironmentIdentifier\":\"e\","
                    + "\"ConfigurationProfileIdentifier\":\"p\"}").post("/configurationsessions").then().statusCode(404);
        }
        refused(as(user, "appconfig").body("{\"ApplicationIdentifier\":\"" + otherApplication
                + "\",\"EnvironmentIdentifier\":\"e\",\"ConfigurationProfileIdentifier\":\"p\"}")
                .post("/configurationsessions").then(), "appconfig:StartConfigurationSession");
    }

    private String createFileSystem() {
        String id = adminOf("elasticfilesystem").contentType("application/json")
                .body("{\"CreationToken\":\"route-efs-" + suffix() + "\"}")
                .post("/2015-02-01/file-systems").then().statusCode(201).extract().path("FileSystemId");
        cleanup.register(() -> adminOf("elasticfilesystem").delete("/2015-02-01/file-systems/" + id));
        return id;
    }

    /**
     * AppConfig, CodeArtifact and CodeGuru Reviewer are checked by their vendored routes on the resources the
     * generated table reads: an AppConfig application by its id and a CodeArtifact domain by its name, so a grant on
     * one does not reach another, and CodeGuru Reviewer's association by the ARN its request names.
     */
    @Test
    void appConfigCodeArtifactAndCodeGuruReviewerAreCheckedOnTheResourcesTheirRequestsName() {
        String applicationName = "route-app-" + suffix();
        String application = adminOf("appconfig").body("{\"Name\":\"" + applicationName + "\"}")
                .post("/applications").then().statusCode(201).extract().path("Id");
        String otherApplication = adminOf("appconfig").body("{\"Name\":\"route-app-" + suffix() + "\"}")
                .post("/applications").then().statusCode(201).extract().path("Id");
        cleanup.register(() -> adminOf("appconfig").delete("/applications/" + application));
        cleanup.register(() -> adminOf("appconfig").delete("/applications/" + otherApplication));
        String domain = "route-domain-" + suffix();
        String otherDomain = "route-domain-" + suffix();
        for (String name : new String[] {domain, otherDomain}) {
            adminOf("codeartifact").queryParam("domain", name).body("{}").post("/v1/domain").then().statusCode(200);
            cleanup.register(() -> adminOf("codeartifact").queryParam("domain", name).delete("/v1/domain"));
        }
        String user = user("route-app-artifact");
        policy(user, allow("appconfig:GetApplication",
                "arn:aws:appconfig:us-east-1:000000000000:application/" + application) + ","
                + allow("codeartifact:DescribeDomain", "arn:aws:codeartifact:us-east-1:000000000000:domain/" + domain));

        as(user, "appconfig").get("/applications/" + application).then().statusCode(200);
        // Floci's AppConfig also takes an application's name, which is checked on that application's own ARN.
        as(user, "appconfig").get("/applications/" + applicationName).then().statusCode(200);
        refused(as(user, "appconfig").get("/applications/" + otherApplication).then(), "appconfig:GetApplication");
        as(user, "codeartifact").queryParam("domain", domain).get("/v1/domain").then().statusCode(200);
        refused(as(user, "codeartifact").queryParam("domain", otherDomain).get("/v1/domain").then(),
                "codeartifact:DescribeDomain");

        String association = "arn:aws:codeguru-reviewer:us-east-1:000000000000:association:"
                + "11111111-2222-3333-4444-555555555555";
        refused(as(user, "codeguru-reviewer").get("/associations/" + association).then(),
                "codeguru-reviewer:DescribeRepositoryAssociation");
        policy(user, allow("codeguru-reviewer:DescribeRepositoryAssociation", association));
        // Past the check, CodeGuru Reviewer answers that it holds no such association.
        as(user, "codeguru-reviewer").get("/associations/" + association).then().statusCode(404);
    }

    /**
     * A CodeArtifact request naming its domain's owner by {@code domain-owner}, an account id, is checked on that
     * account's domain, where its handler looks the domain up, as AWS checks a cross-account request: a grant on the
     * caller's own domain of that name does not reach another account's, and a grant on the other account's needs
     * that account's domain policy to allow it too, after which CodeArtifact answers from that account.
     */
    @Test
    void aCodeArtifactDomainIsCheckedInTheAccountItsDomainOwnerNames() {
        String domain = "route-domain-" + suffix();
        adminOf("codeartifact").queryParam("domain", domain).body("{}").post("/v1/domain").then().statusCode(200);
        cleanup.register(() -> adminOf("codeartifact").queryParam("domain", domain).delete("/v1/domain"));
        String user = user("route-artifact-owner");
        policy(user, allow("codeartifact:DescribeDomain",
                "arn:aws:codeartifact:us-east-1:" + ACCOUNT + ":domain/" + domain));

        as(user, "codeartifact").queryParam("domain", domain).queryParam("domain-owner", ACCOUNT)
                .get("/v1/domain").then().statusCode(200);
        refused(as(user, "codeartifact").queryParam("domain", domain).queryParam("domain-owner", "111122223333")
                .get("/v1/domain").then(), "codeartifact:DescribeDomain");

        policy(user, allow("codeartifact:DescribeDomain", "arn:aws:codeartifact:us-east-1:111122223333:domain/" + domain));
        codeArtifactOf("111122223333").queryParam("domain", domain).body("{}").post("/v1/domain").then().statusCode(200);
        cleanup.register(() -> codeArtifactOf("111122223333").queryParam("domain", domain).delete("/v1/domain"));
        // The other account's domain policy grants this account nothing yet.
        refused(as(user, "codeartifact").queryParam("domain", domain).queryParam("domain-owner", "111122223333")
                .get("/v1/domain").then(), "codeartifact:DescribeDomain");

        String grant = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\""
                + ACCOUNT + "\"},\"Action\":\"codeartifact:DescribeDomain\",\"Resource\":\"*\"}]}";
        codeArtifactOf("111122223333").body("{\"domain\":\"" + domain + "\",\"policyDocument\":"
                + quoted(grant) + "}").put("/v1/domain/permissions/policy").then().statusCode(200);
        as(user, "codeartifact").queryParam("domain", domain).queryParam("domain-owner", "111122223333")
                .get("/v1/domain").then().statusCode(200).body("domain.owner", equalTo("111122223333"));
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags AppConfig, CodeArtifact and CodeGuru Reviewer hold on the
     * resource a request names: an allow on one team's tag lets the caller read that team's application, domain and
     * association, and not another team's.
     */
    @Test
    void aResourceTagConditionReadsTheTagsAppConfigCodeArtifactAndCodeGuruReviewerHold() {
        String[] applications = new String[2];
        String[] domains = new String[2];
        String[] associations = new String[2];
        String[] teams = {"data", "web"};
        for (int i = 0; i < 2; i++) {
            String application = adminOf("appconfig").body("{\"Name\":\"route-tags-" + suffix() + "\"}")
                    .post("/applications").then().statusCode(201).extract().path("Id");
            cleanup.register(() -> adminOf("appconfig").delete("/applications/" + application));
            adminOf("appconfig").urlEncodingEnabled(false).body("{\"Tags\":{\"team\":\"" + teams[i] + "\"}}")
                    .post("/tags/" + URLEncoder.encode("arn:aws:appconfig:us-east-1:" + ACCOUNT + ":application/"
                            + application, StandardCharsets.UTF_8)).then().statusCode(204);
            String domain = "route-tags-" + suffix();
            adminOf("codeartifact").queryParam("domain", domain)
                    .body("{\"tags\":[{\"key\":\"team\",\"value\":\"" + teams[i] + "\"}]}")
                    .post("/v1/domain").then().statusCode(200);
            cleanup.register(() -> adminOf("codeartifact").queryParam("domain", domain).delete("/v1/domain"));
            String association = adminOf("codeguru-reviewer")
                    .body("{\"Repository\":{\"CodeCommit\":{\"Name\":\"route-tags-" + suffix() + "\"}},"
                            + "\"Tags\":{\"team\":\"" + teams[i] + "\"}}")
                    .post("/associations").then().statusCode(200)
                    .extract().path("RepositoryAssociation.AssociationArn");
            cleanup.register(() -> adminOf("codeguru-reviewer").delete("/associations/" + association));
            applications[i] = application;
            domains[i] = domain;
            associations[i] = association;
        }
        String user = user("route-tags-three");
        policy(user, "{\"Effect\":\"Allow\",\"Action\":[\"appconfig:GetApplication\",\"codeartifact:DescribeDomain\","
                + "\"codeguru-reviewer:DescribeRepositoryAssociation\"],\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");

        as(user, "appconfig").get("/applications/" + applications[0]).then().statusCode(200);
        refused(as(user, "appconfig").get("/applications/" + applications[1]).then(), "appconfig:GetApplication");
        as(user, "codeartifact").queryParam("domain", domains[0]).get("/v1/domain").then().statusCode(200);
        refused(as(user, "codeartifact").queryParam("domain", domains[1]).get("/v1/domain").then(),
                "codeartifact:DescribeDomain");
        as(user, "codeguru-reviewer").get("/associations/" + associations[0]).then().statusCode(200);
        refused(as(user, "codeguru-reviewer").get("/associations/" + associations[1]).then(),
                "codeguru-reviewer:DescribeRepositoryAssociation");
    }

    /**
     * A CodeArtifact request naming a package group by its pattern is checked on that group's ARN, its pattern
     * percent-encoded as CodeArtifact writes it ({@code /npm/team/*} is {@code /npm/team/%2a}), the ARN CodeArtifact
     * answers for the group: a grant on it lets the caller describe the group, and a grant on another group does not.
     * A tag call naming the group by another spelling of its pattern ({@code %2A}), which CodeArtifact reads as the same
     * group, is checked on the group's own ARN, so a deny on that ARN holds.
     */
    @Test
    void aPackageGroupIsCheckedOnItsEncodedArn() {
        String domain = "route-groups-" + suffix();
        adminOf("codeartifact").queryParam("domain", domain).body("{}").post("/v1/domain").then().statusCode(200);
        cleanup.register(() -> adminOf("codeartifact").queryParam("domain", domain).delete("/v1/domain"));
        String arn = adminOf("codeartifact").queryParam("domain", domain).body("{\"packageGroup\":\"/npm/team/*\"}")
                .post("/v1/package-group").then().statusCode(200).extract().path("packageGroup.arn");
        adminOf("codeartifact").queryParam("domain", domain).body("{\"packageGroup\":\"/npm/other/*\"}")
                .post("/v1/package-group").then().statusCode(200);
        assertEquals("arn:aws:codeartifact:us-east-1:" + ACCOUNT + ":package-group/" + domain + "/npm/team/%2a", arn);
        String user = user("route-package-group");
        policy(user, allow("codeartifact:DescribePackageGroup", arn));

        as(user, "codeartifact").queryParam("domain", domain).queryParam("package-group", "/npm/team/*")
                .get("/v1/package-group").then().statusCode(200).body("packageGroup.arn", equalTo(arn));
        refused(as(user, "codeartifact").queryParam("domain", domain).queryParam("package-group", "/npm/other/*")
                .get("/v1/package-group").then(), "codeartifact:DescribePackageGroup");

        policy(user, allow("codeartifact:TagResource", "*") + "," + deny("codeartifact:TagResource", arn));
        refused(as(user, "codeartifact").queryParam("resourceArn", arn.replace("%2a", "%2A"))
                .body("{\"tags\":[{\"key\":\"team\",\"value\":\"red\"}]}").post("/v1/tag").then(),
                "codeartifact:TagResource");
    }

    /**
     * A {@code CopyPackageVersions} is checked as the CodeArtifact user guide says: as {@code ReadFromRepository} on
     * its source repository and as {@code CopyPackageVersions} on its destination repository. The two grants that way
     * round let the copy through to CodeArtifact, and the same two the other way round are refused.
     */
    @Test
    void aCopyIsCheckedAsReadOnItsSourceAndCopyOnItsDestination() {
        String domain = "route-copy-" + suffix();
        adminOf("codeartifact").queryParam("domain", domain).body("{}").post("/v1/domain").then().statusCode(200);
        cleanup.register(() -> adminOf("codeartifact").queryParam("domain", domain).delete("/v1/domain"));
        for (String repository : new String[] {"source", "destination"}) {
            adminOf("codeartifact").queryParam("domain", domain).queryParam("repository", repository).body("{}")
                    .post("/v1/repository").then().statusCode(200);
            cleanup.register(() -> adminOf("codeartifact").queryParam("domain", domain)
                    .queryParam("repository", repository).delete("/v1/repository"));
        }
        String repositories = "arn:aws:codeartifact:us-east-1:" + ACCOUNT + ":repository/" + domain + "/";
        String user = user("route-copy");

        policy(user, allow("codeartifact:ReadFromRepository", repositories + "source") + ","
                + allow("codeartifact:CopyPackageVersions", repositories + "destination"));
        // Past the check, CodeArtifact answers which of the versions it could not copy.
        copy(user, domain).statusCode(200);
        policy(user, allow("codeartifact:ReadFromRepository", repositories + "destination") + ","
                + allow("codeartifact:CopyPackageVersions", repositories + "source"));
        refused(copy(user, domain), "codeartifact:ReadFromRepository");
    }

    private ValidatableResponse copy(String user, String domain) {
        return as(user, "codeartifact").queryParam("domain", domain).queryParam("source-repository", "source")
                .queryParam("destination-repository", "destination").queryParam("format", "npm")
                .queryParam("package", "left-pad").body("{\"versions\":[\"1.0.0\"]}")
                .post("/v1/package/versions/copy").then();
    }

    /**
     * A CodeArtifact request naming a package with no {@code namespace}, as a PyPI or an unscoped npm package is, is
     * checked on the package with its namespace empty, as the CodeArtifact user guide writes its ARN
     * ({@code package/my_domain/my_repo/npm//react}): a grant on that ARN lets the request through, and one on the
     * package under a namespace does not.
     */
    @Test
    void aPackageWithNoNamespaceIsCheckedWithItsNamespaceEmpty() {
        String domain = "route-pypi-" + suffix();
        adminOf("codeartifact").queryParam("domain", domain).body("{}").post("/v1/domain").then().statusCode(200);
        cleanup.register(() -> adminOf("codeartifact").queryParam("domain", domain).delete("/v1/domain"));
        adminOf("codeartifact").queryParam("domain", domain).queryParam("repository", "python").body("{}")
                .post("/v1/repository").then().statusCode(200);
        cleanup.register(() -> adminOf("codeartifact").queryParam("domain", domain).queryParam("repository", "python")
                .delete("/v1/repository"));
        String packages = "arn:aws:codeartifact:us-east-1:" + ACCOUNT + ":package/" + domain + "/python/pypi/";
        String user = user("route-pypi");

        policy(user, allow("codeartifact:DescribePackage", packages + "scope/requests"));
        refused(describePackage(user, domain), "codeartifact:DescribePackage");
        policy(user, allow("codeartifact:DescribePackage", packages + "/requests"));
        // Past the check, CodeArtifact answers that the repository holds no such package.
        describePackage(user, domain).statusCode(404);
    }

    private ValidatableResponse describePackage(String user, String domain) {
        return as(user, "codeartifact").queryParam("domain", domain).queryParam("repository", "python")
                .queryParam("format", "pypi").queryParam("package", "requests").get("/v1/package").then();
    }

    /**
     * A request naming an AppConfig environment by its name, which Floci's AppConfig resolves to the environment, is
     * checked on that environment by its id, so a deny on the environment's ARN holds whichever the request names it by.
     */
    @Test
    void anAppConfigEnvironmentNamedByItsNameIsCheckedByItsId() {
        String application = adminOf("appconfig").body("{\"Name\":\"route-names-" + suffix() + "\"}")
                .post("/applications").then().statusCode(201).extract().path("Id");
        cleanup.register(() -> adminOf("appconfig").delete("/applications/" + application));
        String environment = adminOf("appconfig").body("{\"Name\":\"route-env\"}")
                .post("/applications/" + application + "/environments").then().statusCode(201).extract().path("Id");
        String user = user("route-env-names");
        policy(user, allow("appconfig:GetEnvironment", "*") + "," + deny("appconfig:GetEnvironment",
                "arn:aws:appconfig:us-east-1:" + ACCOUNT + ":application/" + application + "/environment/" + environment));

        refused(as(user, "appconfig").get("/applications/" + application + "/environments/" + environment).then(),
                "appconfig:GetEnvironment");
        refused(as(user, "appconfig").get("/applications/" + application + "/environments/route-env").then(),
                "appconfig:GetEnvironment");
    }

    /**
     * A PyPI package a request names by a name CodeArtifact normalizes ({@code Foo_Bar} is {@code foo-bar}) is checked
     * on the package by its normalized name, as AWS writes it in the ARN, so a deny on that ARN holds whichever spelling
     * the request gives.
     */
    @Test
    void aPackageNamedByAnUnnormalizedNameIsCheckedByItsNormalizedName() {
        String domain = "route-norm-" + suffix();
        adminOf("codeartifact").queryParam("domain", domain).body("{}").post("/v1/domain").then().statusCode(200);
        cleanup.register(() -> adminOf("codeartifact").queryParam("domain", domain).delete("/v1/domain"));
        adminOf("codeartifact").queryParam("domain", domain).queryParam("repository", "python").body("{}")
                .post("/v1/repository").then().statusCode(200);
        cleanup.register(() -> adminOf("codeartifact").queryParam("domain", domain).queryParam("repository", "python")
                .delete("/v1/repository"));
        String user = user("route-norm");
        policy(user, allow("codeartifact:DescribePackage", "*") + "," + deny("codeartifact:DescribePackage",
                "arn:aws:codeartifact:us-east-1:" + ACCOUNT + ":package/" + domain + "/python/pypi//foo-bar"));

        refused(as(user, "codeartifact").queryParam("domain", domain).queryParam("repository", "python")
                .queryParam("format", "pypi").queryParam("package", "Foo_Bar").get("/v1/package").then(),
                "codeartifact:DescribePackage");
    }

    /** A JSON string holding the text, as a request body carries a policy document. */
    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** A CodeArtifact request of {@code account}'s root, whose access key is that account's id. */
    private static RequestSpecification codeArtifactOf(String account) {
        return given().header("Authorization", auth(account, "codeartifact")).contentType("application/json");
    }

    /**
     * Macie's {@code CreateMember} is checked on the account's members, {@code member/*}: nothing botocore or the
     * Service Reference says names the member it makes by its {@code account.accountId}, so a grant scoped to one
     * member does not reach it, a grant on the members lets the request reach Macie, which answers that this account
     * has no session, and a deny on them holds under an allow on everything.
     */
    @Test
    void macieCreateMemberIsCheckedOnTheAccountsMembers() {
        String user = user("route-macie-member");
        String members = "arn:aws:macie2:us-east-1:" + ACCOUNT + ":member/*";

        policy(user, allow("macie2:CreateMember", "arn:aws:macie2:us-east-1:" + ACCOUNT + ":member/111122223333"));
        refused(createMember(user, "111122223333", null), "macie2:CreateMember");

        policy(user, allow("macie2:CreateMember", members));
        createMember(user, "111122223333", null).statusCode(404);
        createMember(user, "444455556666", null).statusCode(404);

        policy(user, allow("macie2:CreateMember", "*") + "," + deny("macie2:CreateMember", members));
        refused(createMember(user, "111122223333", null), "macie2:CreateMember");
    }

    /**
     * A Macie {@code CreateMember} that carries tags also needs {@code macie2:TagResource} on the member it makes, and
     * a condition on {@code aws:RequestTag/<key>} sees those tags. One that carries none needs only its own action.
     */
    @Test
    void macieCreateMemberIsCheckedWithTheTagsItCarries() {
        String user = user("route-macie-tags");
        String member = "arn:aws:macie2:us-east-1:" + ACCOUNT + ":member/*";

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
     * An {@code aws:ResourceTag} condition reads the tags OpenSearch holds on the domain a request names in its path,
     * as a tag call leaves them, so an allow on one team's tag lets the caller describe that team's domain and not
     * another's.
     */
    @Test
    void anOpenSearchResourceTagConditionReadsTheTagsOfTheDomain() {
        String tagged = openSearchDomain("web");
        String other = openSearchDomain("web");
        adminOf("es").body("{\"ARN\":\"arn:aws:es:us-east-1:" + ACCOUNT + ":domain/" + tagged
                + "\",\"TagList\":[{\"Key\":\"team\",\"Value\":\"data\"}]}").post("/2021-01-01/tags").then()
                .statusCode(200);
        String user = user("route-domain-tags");

        policy(user, onTeam("Allow", "es:DescribeDomain", "data"));
        request(user, "es", "GET", "/2021-01-01/opensearch/domain/" + tagged, null).statusCode(200);
        refused(request(user, "es", "GET", "/2021-01-01/opensearch/domain/" + other, null), "es:DescribeDomain");
    }

    /** The name of an OpenSearch domain an administrator creates tagged with the team, deleted after the test. */
    private String openSearchDomain(String team) {
        String name = "route-tags-" + suffix();
        adminOf("es").body("{\"DomainName\":\"" + name + "\",\"EngineVersion\":\"OpenSearch_2.11\",\"TagList\":"
                + "[{\"Key\":\"team\",\"Value\":\"" + team + "\"}]}").post("/2021-01-01/opensearch/domain").then()
                .statusCode(200);
        cleanup.register(() -> adminOf("es").delete("/2021-01-01/opensearch/domain/" + name));
        return name;
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
     * An {@code aws:ResourceTag} condition reads the tags Access Analyzer holds on the analyzer a request names in its
     * path, the ones it was created with, so an allow on one team's tag lets the caller read that team's analyzer and
     * not another's.
     */
    @Test
    void anAccessAnalyzerResourceTagConditionReadsTheTagsOfTheAnalyzer() {
        String tagged = analyzer("data");
        String other = analyzer("web");
        String user = user("route-analyzer-tags");

        policy(user, onTeam("Allow", "access-analyzer:GetAnalyzer", "data"));
        as(user, "access-analyzer").get("/analyzer/" + tagged).then().statusCode(200);
        refused(as(user, "access-analyzer").get("/analyzer/" + other).then(), "access-analyzer:GetAnalyzer");
    }

    /** The name of an organization analyzer an administrator creates tagged with the team, deleted after the test. */
    private String analyzer(String team) {
        String name = "route-tags-" + suffix();
        accessAnalyzer().body("{\"analyzerName\":\"" + name + "\",\"type\":\"ORGANIZATION\",\"tags\":{\"team\":\""
                + team + "\"}}").put("/analyzer").then().statusCode(200);
        cleanup.register(() -> accessAnalyzer().delete("/analyzer/" + name));
        return name;
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
     * An {@code aws:ResourceTag} condition reads the tags Security Hub holds on the hub a request names, as TagResource
     * leaves them, so an allow on one team's tag lets the caller describe that team's hub and not another's. An
     * account has one hub a Region, so the two are in two Regions.
     */
    @Test
    void aSecurityHubResourceTagConditionReadsTheTagsOfTheHub() {
        String taggedRegion = "ca-central-1";
        String otherRegion = "sa-east-1";
        securityHub(taggedRegion, "web");
        securityHub(otherRegion, "web");
        String taggedHub = "arn:aws:securityhub:" + taggedRegion + ":" + ACCOUNT + ":hub/default";
        given().header("Authorization", auth(ADMIN, taggedRegion, "securityhub")).contentType("application/json")
                .body("{\"Tags\":{\"team\":\"data\"}}")
                .post("/tags/{arn}", taggedHub).then().statusCode(200);
        String user = user("route-hub-tags");

        policy(user, onTeam("Allow", "securityhub:DescribeHub", "data"));
        as(user, taggedRegion, "securityhub").get("/accounts").then().statusCode(200);
        refused(as(user, otherRegion, "securityhub").get("/accounts").then(), "securityhub:DescribeHub");
    }

    /**
     * Security Hub enabled for the administrator in {@code region}, its hub tagged with the team. Floci serves no
     * DisableSecurityHub, so each Region a test enables it in is one no other test uses.
     */
    private void securityHub(String region, String team) {
        given().header("Authorization", auth(ADMIN, region, "securityhub")).contentType("application/json")
                .body("{\"EnableDefaultStandards\":false,\"Tags\":{\"team\":\"" + team + "\"}}").post("/accounts")
                .then().statusCode(200);
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
        String detector = guardDutyDetector(region, null);
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
     * An {@code aws:ResourceTag} condition reads the tags GuardDuty holds on the detector a request names, as
     * TagResource leaves them, so an allow on one team's tag lets the caller read that team's detector and not
     * another's. An account has one detector a Region, so the two are in two Regions.
     */
    @Test
    void aGuardDutyResourceTagConditionReadsTheTagsOfTheDetector() {
        String taggedRegion = "eu-north-1";
        String otherRegion = "eu-south-2";
        String tagged = guardDutyDetector(taggedRegion, "web");
        String other = guardDutyDetector(otherRegion, "web");
        String taggedArn = "arn:aws:guardduty:" + taggedRegion + ":" + ACCOUNT + ":detector/" + tagged;
        given().header("Authorization", auth(ADMIN, taggedRegion, "guardduty")).contentType("application/json")
                .body("{\"tags\":{\"team\":\"data\"}}")
                .post("/tags/{arn}", taggedArn).then().statusCode(204);
        String user = user("route-detector-tags");

        policy(user, onTeam("Allow", "guardduty:GetDetector", "data"));
        as(user, taggedRegion, "guardduty").get("/detector/" + tagged).then().statusCode(200);
        refused(as(user, otherRegion, "guardduty").get("/detector/" + other).then(), "guardduty:GetDetector");
    }

    /**
     * The id of a detector an administrator creates in {@code region}, tagged with the team if one is given, deleted
     * after the test.
     */
    private String guardDutyDetector(String region, String team) {
        String detector = given().header("Authorization", auth(ADMIN, region, "guardduty"))
                .contentType("application/json")
                .body("{\"enable\":true" + (team == null ? "" : ",\"tags\":{\"team\":\"" + team + "\"}") + "}")
                .post("/detector").then().statusCode(200).extract().path("detectorId");
        cleanup.register(() -> given().header("Authorization", auth(ADMIN, region, "guardduty"))
                .delete("/detector/" + detector));
        return detector;
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
        checkedAs(user, "account", "POST", "/getContactInformation", "{}", "account:GetContactInformation");
        checkedAs(user, "account", "POST", "/putContactInformation", "{}", "account:PutContactInformation");
        checkedAs(user, "account", "POST", "/getAccountInformation", "{}", "account:GetAccountInformation");
        checkedAs(user, "account", "POST", "/putAccountName", "{}", "account:PutAccountName");
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
        cleanup.register(() -> OrganizationsRequests.request(MANAGEMENT, "DeleteOrganization", "{}")
                .when().post("/").then().statusCode(200));
        String organization = OrganizationsRequests.request(MANAGEMENT, "DescribeOrganization", "{}")
                .when().post("/").then().statusCode(200).extract().path("Organization.Id");
        String member = OrganizationsRequests.request(MANAGEMENT, "CreateAccount",
                        "{\"Email\":\"route-contact@example.com\",\"AccountName\":\"RouteContact\"}")
                .when().post("/").then().statusCode(200).extract().path("CreateAccountStatus.AccountId");
        cleanup.register(() -> OrganizationsRequests.request(MANAGEMENT, "RemoveAccountFromOrganization",
                "{\"AccountId\":\"" + member + "\"}").when().post("/").then().statusCode(200));
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
        String id = fisTemplate(null);
        String arn = fisTemplateArn(id);
        String user = user("route-fis");

        policy(user, allow("fis:GetExperimentTemplate", arn));
        request(user, "fis", "GET", "/experimentTemplates/" + id, null)
                .statusCode(200).body("experimentTemplate.arn", equalTo(arn));
        refused(request(user, "fis", "GET", "/experimentTemplates/EXTother", null), "fis:GetExperimentTemplate");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags AWS FIS holds on the experiment template a request names,
     * as TagResource leaves them, so an allow on one team's tag lets the caller read that team's template and not
     * another's.
     */
    @Test
    void aFisResourceTagConditionReadsTheTagsOfTheTemplate() {
        String tagged = fisTemplate("{\"team\":\"web\"}");
        String other = fisTemplate("{\"team\":\"web\"}");
        adminOf("fis").body("{\"tags\":{\"team\":\"data\"}}").post("/tags/" + fisTemplateArn(tagged))
                .then().statusCode(200);
        String user = user("route-fis-tags");

        policy(user, onTeam("Allow", "fis:GetExperimentTemplate", "data"));
        request(user, "fis", "GET", "/experimentTemplates/" + tagged, null).statusCode(200);
        refused(request(user, "fis", "GET", "/experimentTemplates/" + other, null), "fis:GetExperimentTemplate");
    }

    /** The id of an experiment template an administrator creates, with the {@code tags} JSON object if any. */
    private String fisTemplate(String tags) {
        String template = "{\"clientToken\":\"" + suffix() + "\",\"description\":\"d\",\"roleArn\":\"arn:aws:iam::"
                + ACCOUNT + ":role/fis\",\"stopConditions\":[{\"source\":\"none\"}],\"targets\":{},\"actions\":"
                + "{\"wait\":{\"actionId\":\"aws:fis:wait\",\"parameters\":{\"duration\":\"PT1M\"}}}"
                + (tags == null ? "" : ",\"tags\":" + tags) + "}";
        String id = adminOf("fis").body(template).post("/experimentTemplates")
                .then().statusCode(200).extract().path("experimentTemplate.id");
        cleanup.register(() -> adminOf("fis").delete("/experimentTemplates/" + id));
        return id;
    }

    private static String fisTemplateArn(String id) {
        return "arn:aws:fis:us-east-1:" + ACCOUNT + ":experiment-template/" + id;
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

    /**
     * An {@code aws:ResourceTag} condition reads the tags OAM holds on the sink a request names: an allow on the team
     * the sink is tagged with lets the caller read it, one on another team does not, and a deny on its team holds
     * against an allow on every resource. A Region holds one sink, so the one sink answers each policy.
     */
    @Test
    void anOamResourceTagConditionReadsTheTagsOfTheSink() {
        String sink = oamSink("{\"team\":\"data\"}");
        String user = user("route-oam-tags");
        String getSink = "{\"Identifier\":\"" + sink + "\"}";

        policy(user, onTeam("Allow", "oam:GetSink", "data"));
        request(user, "oam", "POST", "/GetSink", getSink).statusCode(200);
        policy(user, onTeam("Allow", "oam:GetSink", "web"));
        refused(request(user, "oam", "POST", "/GetSink", getSink), "oam:GetSink");
        policy(user, allow("oam:GetSink", "*") + "," + onTeam("Deny", "oam:GetSink", "data"));
        refused(request(user, "oam", "POST", "/GetSink", getSink), "oam:GetSink");
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags EMR on EKS holds on the job template a request names, those
     * it was created with (TagResource takes only virtual clusters, job runs and managed endpoints), so an allow on one
     * team's tag lets the caller read that team's template and not another's.
     */
    @Test
    void anEmrContainersResourceTagConditionReadsTheTagsOfTheJobTemplate() {
        String tagged = emrContainersJobTemplate("data");
        String other = emrContainersJobTemplate("web");
        String user = user("route-emrc-tags");

        policy(user, onTeam("Allow", "emr-containers:DescribeJobTemplate", "data"));
        request(user, "emr-containers", "GET", "/jobtemplates/" + tagged, null).statusCode(200);
        refused(request(user, "emr-containers", "GET", "/jobtemplates/" + other, null),
                "emr-containers:DescribeJobTemplate");
    }

    /** The id of an EMR on EKS job template an administrator creates, tagged with {@code team}, deleted after. */
    private String emrContainersJobTemplate(String team) {
        String body = "{\"name\":\"route-template-" + suffix() + "\",\"clientToken\":\"" + suffix()
                + "\",\"tags\":{\"team\":\"" + team + "\"},\"jobTemplateData\":{\"executionRoleArn\":\"arn:aws:iam::"
                + ACCOUNT + ":role/emr-job\",\"releaseLabel\":\"emr-7.5.0-latest\",\"jobDriver\":"
                + "{\"sparkSubmitJobDriver\":{\"entryPoint\":\"s3://bucket/pi.py\"}}}}";
        String id = adminOf("emr-containers").body(body).post("/jobtemplates").then().statusCode(200).extract()
                .path("id");
        cleanup.register(() -> adminOf("emr-containers").delete("/jobtemplates/" + id).then().statusCode(200));
        return id;
    }

    /** A statement with {@code effect} on {@code action} for every resource whose {@code team} tag is the one given. */
    private static String onTeam(String effect, String action, String team) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"" + team + "\"}}}";
    }

    /** The ARN of a sink an administrator creates with the {@code tags} JSON object, deleted after the test. */
    private String oamSink(String tags) {
        String sink = adminOf("oam").body("{\"Name\":\"route-sink-" + suffix() + "\",\"Tags\":" + tags + "}")
                .post("/CreateSink").then().statusCode(200).extract().path("Arn");
        cleanup.register(() -> adminOf("oam").body("{\"Identifier\":\"" + sink + "\"}").post("/DeleteSink"));
        return sink;
    }

    /**
     * An {@code aws:ResourceTag} condition reads the tags Resource Explorer holds on the view a request names, so an
     * allow on one team's tag lets the caller read that team's view and not another's.
     */
    @Test
    void aResourceExplorerResourceTagConditionReadsTheTagsOfTheView() {
        String tagged = taggedView("data");
        String other = taggedView("web");
        String user = user("route-view-tags");

        policy(user, onTeam("Allow", "resource-explorer-2:GetView", "data"));
        request(user, "resource-explorer-2", "POST", "/GetView", "{\"ViewArn\":\"" + tagged + "\"}").statusCode(200);
        refused(request(user, "resource-explorer-2", "POST", "/GetView", "{\"ViewArn\":\"" + other + "\"}"),
                "resource-explorer-2:GetView");
    }

    /** The ARN of a Resource Explorer view an administrator creates tagged with the team, deleted after the test. */
    private String taggedView(String team) {
        String view = adminOf("resource-explorer-2").body("{\"ViewName\":\"route-view-" + suffix()
                        + "\",\"Tags\":{\"team\":\"" + team + "\"}}").post("/CreateView")
                .then().statusCode(200).extract().path("View.ViewArn");
        cleanup.register(() -> adminOf("resource-explorer-2").body("{\"ViewArn\":\"" + view + "\"}")
                .post("/DeleteView"));
        return view;
    }

    @Test
    void oamIsCheckedOnTheArnItsIdentifiersHold() {
        String sink = oamSink("{}");
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
        String share = madeShare(adminOf("ram").body("{\"name\":\"route-share-" + suffix() + "\"}")
                .post("/createresourceshare").then());
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

    /**
     * An {@code aws:ResourceTag} condition reads the tags RAM holds on the resource share a request names, so an allow
     * on one team's tag lets the caller update that team's share and not another's.
     */
    @Test
    void aRamResourceTagConditionReadsTheTagsOfTheResourceShare() {
        String tagged = taggedShare("data");
        String other = taggedShare("web");
        String user = user("route-ram-tags");

        policy(user, "{\"Effect\":\"Allow\",\"Action\":\"ram:UpdateResourceShare\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"data\"}}}");
        request(user, "ram", "POST", "/updateresourceshare", "{\"resourceShareArn\":\"" + tagged + "\"}")
                .statusCode(200);
        refused(request(user, "ram", "POST", "/updateresourceshare", "{\"resourceShareArn\":\"" + other + "\"}"),
                "ram:UpdateResourceShare");
    }

    /** A resource share an administrator makes and tags with the team, deleted after the test; its ARN. */
    private String taggedShare(String team) {
        String share = madeShare(adminOf("ram").body("{\"name\":\"route-share-" + suffix() + "\"}")
                .post("/createresourceshare").then());
        adminOf("ram").body("{\"resourceShareArn\":\"" + share + "\",\"tags\":[{\"key\":\"team\",\"value\":\""
                + team + "\"}]}").post("/tagresource").then().statusCode(200);
        return share;
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

        String links = "arn:aws:oam:us-east-1:" + ACCOUNT + ":link/*";

        // The sink is the other account's, which the link is made to: a grant on a sink of that name of the caller's
        // own is no grant on it. The link it makes is the caller's, and is checked too.
        policy(user, allow(action, sink.replace(PEER, ACCOUNT)) + "," + allow(action, links));
        refused(request(user, "oam", "POST", "/CreateLink", body), action);
        policy(user, allow(action, "*") + "," + deny(action, sink));
        refused(request(user, "oam", "POST", "/CreateLink", body), action);
        policy(user, allow(action, sink));
        refused(request(user, "oam", "POST", "/CreateLink", body), action);
        policy(user, allow(action, sink) + "," + allow(action, links));
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

    /** A create that got past the check and made a resource share, removed afterwards; returns the share's ARN. */
    private String madeShare(ValidatableResponse response) {
        String share = response.statusCode(200).extract().path("resourceShare.resourceShareArn");
        cleanup.register(() -> adminOf("ram").queryParam("resourceShareArn", share).delete("/deleteresourceshare"));
        return share;
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
