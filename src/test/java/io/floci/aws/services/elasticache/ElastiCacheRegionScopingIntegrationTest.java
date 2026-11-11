package io.floci.aws.services.elasticache;

import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.QueryRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

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
    void builtInDefaultUserIsListedAndFoundInEveryRegionWithThatRegionsArn() {
        for (String region : new String[] {HOME, OTHER_REGION}) {
            String arn = "<ARN>arn:aws:elasticache:" + region + ":" + ACCOUNT + ":user:default</ARN>";
            send(ACCOUNT, region, "DescribeUsers").then().statusCode(200)
                    .body(containsString("<UserId>default</UserId>")).body(containsString(arn));
            send(ACCOUNT, region, "DescribeUsers", "UserId", "default").then().statusCode(200)
                    .body(containsString(arn));
        }
        // A user group holding it is one of its groups in the group's Region alone.
        String group = "region-scoped-default-group";
        send(ACCOUNT, HOME, "CreateUserGroup", "UserGroupId", group, "Engine", "redis",
                "UserIds.member.1", "default").then().statusCode(200);
        try {
            send(ACCOUNT, HOME, "DescribeUsers", "UserId", "default").then().statusCode(200)
                    .body(containsString("<member>" + group + "</member>"));
            send(ACCOUNT, OTHER_REGION, "DescribeUsers", "UserId", "default").then().statusCode(200)
                    .body(not(containsString(group)));
        } finally {
            send(ACCOUNT, HOME, "DeleteUserGroup", "UserGroupId", group).then().statusCode(200);
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
    void replicationGroupAndItsMembersAreOnlyVisibleInTheirRegionAndAccount() throws InterruptedException {
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
    void redisCacheClusterIsOnlyVisibleInItsRegionAndAccount() throws InterruptedException {
        requireDocker();
        assertCacheClusterIsRegional("region-scoped-redis", "redis");
    }

    @Test
    void memcachedCacheClusterIsOnlyVisibleInItsRegionAndAccount() throws InterruptedException {
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
        return QueryRequests.request(account, region, "elasticache", action, params).when().post("/");
    }

    private static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(),
                "Docker daemon must be available for ElastiCache container tests");
    }
}
