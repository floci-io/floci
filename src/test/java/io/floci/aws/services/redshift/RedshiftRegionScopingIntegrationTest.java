package io.floci.aws.services.redshift;

import io.floci.aws.testing.QueryRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Cluster parameter groups, subnet groups, snapshot schedules and snapshot copy grants are Regional on AWS: one
 * created in a Region of an account is listed, found by its name and tagged only in that Region, and the same name
 * can be used in another Region. Each case creates the resource in {@link #HOME}, checks it from there and from
 * {@link #OTHER_REGION}, creates the same name in {@link #OTHER_REGION} and deletes it there, leaving
 * {@link #HOME}'s.
 */
@QuarkusTest
class RedshiftRegionScopingIntegrationTest {

    private static final String ACCOUNT = "135792468024";
    private static final String HOME = "eu-west-1";
    private static final String OTHER_REGION = "eu-central-1";

    @Test
    void aClusterParameterGroupIsRegional() {
        assertRegional(new Kind("ClusterParameterGroup", "ParameterGroupName", "ParameterGroupName", "parametergroup",
                "ClusterParameterGroupNotFound", "ParameterGroupFamily", "redshift-1.0", "Description", "Region scoping"),
                "region-scoped-parameters");
    }

    @Test
    void aClusterSubnetGroupIsRegional() {
        assertRegional(new Kind("ClusterSubnetGroup", "ClusterSubnetGroupName", "ClusterSubnetGroupName", "subnetgroup",
                "ClusterSubnetGroupNotFoundFault", "Description", "Region scoping",
                "SubnetIds.SubnetIdentifier.1", "subnet-0a1b2c3d"), "region-scoped-subnets");
    }

    @Test
    void aSnapshotScheduleIsRegional() {
        // DescribeSnapshotSchedules lists no error: an unknown schedule is an empty list.
        assertRegional(new Kind("SnapshotSchedule", "ScheduleIdentifier", "ScheduleIdentifier", "snapshotschedule",
                null, "ScheduleDefinitions.ScheduleDefinition.1", "rate(12 hours)"), "region-scoped-schedule");
    }

    @Test
    void aSnapshotCopyGrantIsRegional() {
        assertRegional(new Kind("SnapshotCopyGrant", "SnapshotCopyGrantName", "SnapshotCopyGrantName", "snapshotcopygrant",
                "SnapshotCopyGrantNotFoundFault"), "region-scoped-grant");
    }

    /**
     * A {@code Create<name>}, {@code Describe<name>s}, {@code Delete<name>} family of calls naming the resource by
     * {@code nameParam}, listed as {@code nameElement} and tagged as {@code tagType}. {@code notFound} is the error a
     * describe by name answers for a missing one, or null when it answers an empty list.
     */
    private record Kind(String name, String nameParam, String nameElement, String tagType, String notFound,
                        String... createParams) {
    }

    private static void assertRegional(Kind kind, String name) {
        String element = "<" + kind.nameElement() + ">" + name + "</" + kind.nameElement() + ">";
        create(kind, HOME, name).then().statusCode(200);
        try {
            describedIn(HOME, kind, name, element);
            notDescribedIn(OTHER_REGION, kind, name, element);

            create(kind, OTHER_REGION, name).then().statusCode(200);
            describedIn(OTHER_REGION, kind, name, element);
            send(OTHER_REGION, "Delete" + kind.name(), kind.nameParam(), name).then().statusCode(200);
            notDescribedIn(OTHER_REGION, kind, name, element);
            describedIn(HOME, kind, name, element);
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

    private static void describedIn(String region, Kind kind, String name, String element) {
        send(region, "Describe" + kind.name() + "s").then().statusCode(200).body(containsString(element));
        send(region, "Describe" + kind.name() + "s", kind.nameParam(), name).then().statusCode(200)
                .body(containsString(element));
        send(region, "DescribeTags", "ResourceName", arn(region, kind, name)).then().statusCode(200)
                .body(containsString("<Value>" + region + "</Value>"));
    }

    private static void notDescribedIn(String region, Kind kind, String name, String element) {
        send(region, "Describe" + kind.name() + "s").then().statusCode(200).body(not(containsString(element)));
        Response byName = send(region, "Describe" + kind.name() + "s", kind.nameParam(), name);
        if (kind.notFound() == null) {
            byName.then().statusCode(200).body(not(containsString(element)));
        } else {
            byName.then().body(containsString("<Code>" + kind.notFound() + "</Code>"));
        }
        send(region, "DescribeTags", "ResourceType", kind.tagType()).then().statusCode(200)
                .body(not(containsString(arn(region, kind, name))));
    }

    private static String arn(String region, Kind kind, String name) {
        return "arn:aws:redshift:" + region + ":" + ACCOUNT + ":" + kind.tagType() + ":" + name;
    }

    /** A Redshift Query request from {@code region} of {@link #ACCOUNT}, with {@code params} as name/value pairs. */
    private static Response send(String region, String action, String... params) {
        return QueryRequests.request(ACCOUNT, region, "redshift", action, params).when().post("/");
    }
}
