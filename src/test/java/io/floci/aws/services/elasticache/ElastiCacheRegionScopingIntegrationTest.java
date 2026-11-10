package io.floci.aws.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Replication groups, cache clusters, users and user groups are Regional on AWS: one created in a Region of an
 * account is listed, and found by its id, only from that Region of that account. Each case creates the resource
 * in {@link #HOME} of {@link #ACCOUNT} and checks the unfiltered listing and the id filter from there, from
 * {@link #OTHER_REGION} of the same account, and from {@link #HOME} of {@link #OTHER_ACCOUNT}.
 */
@QuarkusTest
class ElastiCacheRegionScopingIntegrationTest {

    private static final String ACCOUNT = "975318642097";
    private static final String OTHER_ACCOUNT = "864207531986";
    private static final String HOME = "eu-west-1";
    private static final String OTHER_REGION = "eu-central-1";

    @Test
    void userIsOnlyVisibleInItsRegionAndAccount() {
        String user = "region-scoped-user";
        createUser(user, user);
        try {
            // Each Region also holds the built-in "default" user, so look for this user's element.
            assertRegional("DescribeUsers", "UserId", user, "<UserId>" + user + "</UserId>");
        } finally {
            send(ACCOUNT, HOME, "DeleteUser", "UserId", user).then().statusCode(200);
        }
    }

    @Test
    void userGroupIsOnlyVisibleInItsRegionAndAccount() {
        String member = "region-scoped-default";
        String group = "region-scoped-group";
        createUser(member, "default");
        try {
            send(ACCOUNT, HOME, "CreateUserGroup", "UserGroupId", group, "Engine", "redis",
                    "UserIds.member.1", member).then().statusCode(200);
            try {
                assertRegional("DescribeUserGroups", "UserGroupId", group, "<UserGroupId>" + group + "</UserGroupId>");
            } finally {
                send(ACCOUNT, HOME, "DeleteUserGroup", "UserGroupId", group).then().statusCode(200);
            }
        } finally {
            send(ACCOUNT, HOME, "DeleteUser", "UserId", member).then().statusCode(200);
        }
    }

    @Test
    void replicationGroupAndItsMembersAreOnlyVisibleInTheirRegionAndAccount() {
        requireDocker();
        String group = "region-scoped-rg";
        send(ACCOUNT, HOME, "CreateReplicationGroup", "ReplicationGroupId", group,
                "ReplicationGroupDescription", "Region scoping").then().statusCode(200);
        try {
            assertRegional("DescribeReplicationGroups", "ReplicationGroupId", group,
                    "<ReplicationGroupId>" + group + "</ReplicationGroupId>");
            String member = group + "-001";
            assertRegional("DescribeCacheClusters", "CacheClusterId", member,
                    "<CacheClusterId>" + member + "</CacheClusterId>");
        } finally {
            send(ACCOUNT, HOME, "DeleteReplicationGroup", "ReplicationGroupId", group).then().statusCode(200);
        }
    }

    @Test
    void redisCacheClusterIsOnlyVisibleInItsRegionAndAccount() {
        requireDocker();
        assertCacheClusterIsRegional("region-scoped-redis", "redis");
    }

    @Test
    void memcachedCacheClusterIsOnlyVisibleInItsRegionAndAccount() {
        requireDocker();
        assertCacheClusterIsRegional("region-scoped-memcached", "memcached");
    }

    private static void assertCacheClusterIsRegional(String cluster, String engine) {
        send(ACCOUNT, HOME, "CreateCacheCluster", "CacheClusterId", cluster, "Engine", engine,
                "NumCacheNodes", "1", "CacheNodeType", "cache.t3.micro").then().statusCode(200);
        try {
            assertRegional("DescribeCacheClusters", "CacheClusterId", cluster,
                    "<CacheClusterId>" + cluster + "</CacheClusterId>");
        } finally {
            send(ACCOUNT, HOME, "DeleteCacheCluster", "CacheClusterId", cluster).then().statusCode(200);
        }
    }

    /**
     * {@code action} lists {@code id} and finds it by {@code idParam} in its own Region and account, and neither
     * lists it nor finds it (404) from another Region of the account or from the same Region of another account.
     */
    private static void assertRegional(String action, String idParam, String id, String element) {
        send(ACCOUNT, HOME, action).then().statusCode(200).body(containsString(element));
        send(ACCOUNT, HOME, action, idParam, id).then().statusCode(200).body(containsString(element));

        for (String[] elsewhere : new String[][] {{ACCOUNT, OTHER_REGION}, {OTHER_ACCOUNT, HOME}}) {
            send(elsewhere[0], elsewhere[1], action).then().statusCode(200).body(not(containsString(element)));
            send(elsewhere[0], elsewhere[1], action, idParam, id).then().statusCode(404);
        }
    }

    private static void createUser(String userId, String userName) {
        send(ACCOUNT, HOME, "CreateUser", "UserId", userId, "UserName", userName, "Engine", "redis",
                "AccessString", "on ~* +@all", "AuthenticationMode.Type", "no-password-required")
                .then().statusCode(200);
    }

    /** An ElastiCache Query request from {@code region} of {@code account}, with {@code params} as name/value pairs. */
    private static Response send(String account, String region, String action, String... params) {
        RequestSpecification request = given().header("Authorization", auth(account, region))
                .formParam("Action", action);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/");
    }

    private static String auth(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261110/" + region
                + "/elasticache/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static void requireDocker() {
        boolean available;
        try {
            available = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker daemon must be available for ElastiCache container tests");
    }
}
