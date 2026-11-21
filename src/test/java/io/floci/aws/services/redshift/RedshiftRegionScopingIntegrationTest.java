package io.floci.aws.services.redshift;

import io.floci.aws.testing.QueryRequests;
import io.floci.aws.testing.TestAccounts;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cluster parameter groups, subnet groups, snapshot schedules and snapshot copy grants are Regional on AWS: one
 * created in a Region of an account is listed, found by its name and tagged only in that Region, and the same name
 * can be used in another Region. Each case creates the resource in {@link #HOME}, checks it from there and from
 * {@link #OTHER_REGION}, creates the same name in {@link #OTHER_REGION} and deletes it there, leaving
 * {@link #HOME}'s.
 */
@QuarkusTest
class RedshiftRegionScopingIntegrationTest {

    private static final String ACCOUNT = TestAccounts.randomId();
    private static final String HOME = "eu-west-1";
    private static final String OTHER_REGION = "eu-central-1";

    @Test
    void aClusterParameterGroupIsRegional() {
        assertRegional(new Kind("ClusterParameterGroup", "ParameterGroupName", "parametergroup",
                "ClusterParameterGroupNotFound", "ParameterGroupFamily", "redshift-1.0", "Description", "Region scoping"),
                "region-scoped-parameters");
    }

    @Test
    void aClusterSubnetGroupIsRegional() {
        assertRegional(new Kind("ClusterSubnetGroup", "ClusterSubnetGroupName", "subnetgroup",
                "ClusterSubnetGroupNotFoundFault", "Description", "Region scoping",
                "SubnetIds.SubnetIdentifier.1", "subnet-0a1b2c3d"), "region-scoped-subnets");
    }

    @Test
    void aSnapshotScheduleIsRegional() {
        // DescribeSnapshotSchedules lists no error: an unknown schedule is an empty list.
        assertRegional(new Kind("SnapshotSchedule", "ScheduleIdentifier", "snapshotschedule",
                null, "ScheduleDefinitions.ScheduleDefinition.1", "rate(12 hours)"), "region-scoped-schedule");
    }

    @Test
    void aSnapshotCopyGrantIsRegional() {
        assertRegional(new Kind("SnapshotCopyGrant", "SnapshotCopyGrantName", "snapshotcopygrant",
                "SnapshotCopyGrantNotFoundFault"), "region-scoped-grant");
    }

    /**
     * A {@code Create<name>}, {@code Describe<name>s}, {@code Delete<name>} family of calls naming the resource by
     * {@code nameParam}, which the describe answer lists it under too, and tagged as {@code tagType}.
     * {@code notFound} is the error a describe by name answers for a missing one, or null when it answers an empty
     * list.
     */
    private record Kind(String name, String nameParam, String tagType, String notFound, String... createParams) {
    }

    private static void assertRegional(Kind kind, String name) {
        create(kind, HOME, name).then().statusCode(200);
        try {
            describedIn(HOME, kind, name);
            notDescribedIn(OTHER_REGION, kind, name);

            create(kind, OTHER_REGION, name).then().statusCode(200);
            describedIn(OTHER_REGION, kind, name);
            send(OTHER_REGION, "Delete" + kind.name(), kind.nameParam(), name).then().statusCode(200);
            notDescribedIn(OTHER_REGION, kind, name);
            describedIn(HOME, kind, name);
        } finally {
            send(HOME, "Delete" + kind.name(), kind.nameParam(), name);
            send(OTHER_REGION, "Delete" + kind.name(), kind.nameParam(), name);
        }
    }

    /** Created with the tag {@code scope=<region>}, so each Region's DescribeTags can be told apart. */
    private static Response create(Kind kind, String region, String name) {
        List<String> params = new ArrayList<>(List.of(kind.nameParam(), name,
                "Tags.Tag.1.Key", "scope", "Tags.Tag.1.Value", region));
        params.addAll(List.of(kind.createParams()));
        return send(region, "Create" + kind.name(), params.toArray(String[]::new));
    }

    private static void describedIn(String region, Kind kind, String name) {
        assertTrue(names(send(region, "Describe" + kind.name() + "s"), kind).contains(name));
        assertEquals(List.of(name), names(send(region, "Describe" + kind.name() + "s", kind.nameParam(), name), kind));
        assertEquals(List.of(region), elements(send(region, "DescribeTags", "ResourceName", arn(region, kind, name)),
                "Value"));
    }

    private static void notDescribedIn(String region, Kind kind, String name) {
        assertFalse(names(send(region, "Describe" + kind.name() + "s"), kind).contains(name));
        Response byName = send(region, "Describe" + kind.name() + "s", kind.nameParam(), name);
        if (kind.notFound() == null) {
            assertEquals(List.of(), names(byName, kind));
        } else {
            assertEquals(kind.notFound(), XmlPath.from(byName.asString()).getString("ErrorResponse.Error.Code"));
        }
        // The account is this class's own, so no other resource of the type is tagged in the Region.
        assertEquals(List.of(), elements(send(region, "DescribeTags", "ResourceType", kind.tagType()), "ResourceName"));
    }

    /** The names a describe answer lists. */
    private static List<String> names(Response response, Kind kind) {
        return elements(response, kind.nameParam());
    }

    /** The text of every {@code element} in a successful answer. */
    private static List<String> elements(Response response, String element) {
        response.then().statusCode(200);
        return XmlPath.from(response.asString())
                .getList("**.findAll { it.name() == '" + element + "' }", String.class);
    }

    private static String arn(String region, Kind kind, String name) {
        return "arn:aws:redshift:" + region + ":" + ACCOUNT + ":" + kind.tagType() + ":" + name;
    }

    /** A Redshift Query request from {@code region} of {@link #ACCOUNT}, with {@code params} as name/value pairs. */
    private static Response send(String region, String action, String... params) {
        return QueryRequests.request(ACCOUNT, region, "redshift", action, params).when().post("/");
    }
}
