package io.github.hectorvent.floci.services.opensearch;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * The domain settings {@code terraform-aws-modules/opensearch} sets beyond the cluster basics
 * survive CreateDomain and UpdateDomainConfig: the zone awareness count, the dedicated master
 * type and count, EBS IOPS and throughput, advanced options, the address type, the off-peak
 * window and automatic software updates. A domain created without them reports AWS's defaults.
 */
@QuarkusTest
class OpenSearchDomainConfigRoundTripIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20261103/us-east-1/es/aws4_request";
    private static final String DOMAINS = "/2021-01-01/opensearch/domain";

    @AfterEach
    void cleanup() {
        for (String name : new String[]{"cfg-full", "cfg-bare"}) {
            given().header("Authorization", AUTH).when().delete(DOMAINS + "/" + name);
        }
    }

    @Test
    void theSettingsGivenOnCreateAreReported() {
        given().contentType("application/json").header("Authorization", AUTH).body("""
                {"DomainName": "cfg-full", "EngineVersion": "OpenSearch_2.19",
                 "ClusterConfig": {"InstanceType": "r7g.medium.search", "InstanceCount": 2,
                   "DedicatedMasterEnabled": false, "DedicatedMasterType": "c7g.large.search",
                   "DedicatedMasterCount": 3, "ZoneAwarenessEnabled": true,
                   "ZoneAwarenessConfig": {"AvailabilityZoneCount": 2}},
                 "EBSOptions": {"EBSEnabled": true, "VolumeType": "gp3", "VolumeSize": 20,
                   "Iops": 3000, "Throughput": 250},
                 "AdvancedOptions": {"rest.action.multi.allow_explicit_index": "true"},
                 "IPAddressType": "ipv4",
                 "OffPeakWindowOptions": {"Enabled": true},
                 "SoftwareUpdateOptions": {"AutoSoftwareUpdateEnabled": true}}
                """).when().post(DOMAINS).then().statusCode(200);

        String status = "DomainStatus.";
        given().header("Authorization", AUTH).when().get(DOMAINS + "/cfg-full").then().statusCode(200)
                .body(status + "ClusterConfig.ZoneAwarenessConfig.AvailabilityZoneCount", equalTo(2))
                .body(status + "ClusterConfig.DedicatedMasterType", equalTo("c7g.large.search"))
                .body(status + "ClusterConfig.DedicatedMasterCount", equalTo(3))
                .body(status + "EBSOptions.Iops", equalTo(3000))
                .body(status + "EBSOptions.Throughput", equalTo(250))
                .body(status + "AdvancedOptions.'rest.action.multi.allow_explicit_index'", equalTo("true"))
                .body(status + "IPAddressType", equalTo("ipv4"))
                .body(status + "OffPeakWindowOptions.Enabled", equalTo(true))
                // A window the request leaves unset starts at 10:00 P.M.
                .body(status + "OffPeakWindowOptions.OffPeakWindow.WindowStartTime.Hours", equalTo(22))
                .body(status + "OffPeakWindowOptions.OffPeakWindow.WindowStartTime.Minutes", equalTo(0))
                .body(status + "SoftwareUpdateOptions.AutoSoftwareUpdateEnabled", equalTo(true));

        String config = "DomainConfig.";
        given().header("Authorization", AUTH).when().get(DOMAINS + "/cfg-full/config").then().statusCode(200)
                .body(config + "ClusterConfig.Options.ZoneAwarenessConfig.AvailabilityZoneCount", equalTo(2))
                .body(config + "EBSOptions.Options.Throughput", equalTo(250))
                .body(config + "AdvancedOptions.Options.'rest.action.multi.allow_explicit_index'", equalTo("true"))
                .body(config + "IPAddressType.Options", equalTo("ipv4"))
                .body(config + "OffPeakWindowOptions.Options.Enabled", equalTo(true))
                .body(config + "SoftwareUpdateOptions.Options.AutoSoftwareUpdateEnabled", equalTo(true))
                .body(config + "SoftwareUpdateOptions.Status.State", equalTo("Active"));
    }

    @Test
    void aDomainCreatedWithoutThemReportsTheDefaultsAndUpdatesChangeThem() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"DomainName\": \"cfg-bare\", \"EngineVersion\": \"OpenSearch_2.19\"}")
                .when().post(DOMAINS).then().statusCode(200)
                .body("DomainStatus.IPAddressType", equalTo("ipv4"))
                .body("DomainStatus.AdvancedOptions", anEmptyMap())
                .body("DomainStatus.OffPeakWindowOptions.Enabled", equalTo(true))
                .body("DomainStatus.OffPeakWindowOptions.OffPeakWindow.WindowStartTime.Hours", equalTo(22))
                .body("DomainStatus.SoftwareUpdateOptions.AutoSoftwareUpdateEnabled", equalTo(false))
                .body("DomainStatus.ClusterConfig.ZoneAwarenessConfig", nullValue())
                .body("DomainStatus.ClusterConfig.DedicatedMasterType", nullValue())
                .body("DomainStatus.EBSOptions.Iops", nullValue());

        given().contentType("application/json").header("Authorization", AUTH).body("""
                {"ClusterConfig": {"ZoneAwarenessEnabled": true, "ZoneAwarenessConfig": {"AvailabilityZoneCount": 3}},
                 "EBSOptions": {"VolumeType": "gp3", "Iops": 4000},
                 "AdvancedOptions": {"indices.query.bool.max_clause_count": "2048"},
                 "OffPeakWindowOptions": {"OffPeakWindow": {"WindowStartTime": {"Hours": 3, "Minutes": 30}}},
                 "SoftwareUpdateOptions": {"AutoSoftwareUpdateEnabled": true}}
                """).when().post(DOMAINS + "/cfg-bare/config").then().statusCode(200);

        given().header("Authorization", AUTH).when().get(DOMAINS + "/cfg-bare").then().statusCode(200)
                .body("DomainStatus.ClusterConfig.ZoneAwarenessConfig.AvailabilityZoneCount", equalTo(3))
                .body("DomainStatus.EBSOptions.Iops", equalTo(4000))
                .body("DomainStatus.AdvancedOptions.'indices.query.bool.max_clause_count'", equalTo("2048"))
                .body("DomainStatus.OffPeakWindowOptions.Enabled", equalTo(true))
                .body("DomainStatus.OffPeakWindowOptions.OffPeakWindow.WindowStartTime.Hours", equalTo(3))
                .body("DomainStatus.OffPeakWindowOptions.OffPeakWindow.WindowStartTime.Minutes", equalTo(30))
                .body("DomainStatus.SoftwareUpdateOptions.AutoSoftwareUpdateEnabled", equalTo(true));
    }
}
