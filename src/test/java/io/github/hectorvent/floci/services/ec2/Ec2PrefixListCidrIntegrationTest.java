package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AddPrefixListEntry;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;

import java.net.URI;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2PrefixListCidrIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261025/us-east-1/cloudformation/aws4_request";

    @Test
    void sdkRejectsHostBitsAndPreservesCanonicalEntry() {
        try (Ec2Client client = client()) {
            String name = "sdk-host-bit-cidr";
            try {
                Ec2Exception error = assertThrows(Ec2Exception.class, () -> client.createManagedPrefixList(r -> r
                        .prefixListName(name).addressFamily("IPv4").maxEntries(1)
                        .entries(AddPrefixListEntry.builder().cidr("192.0.2.19/24").build())));
                assertEquals(400, error.statusCode());
                assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode());
                assertEquals("The specified CIDR (192.0.2.19/24) is not valid. "
                        + "For example, specify a CIDR in the following form: 10.0.0.0/16.",
                        error.awsErrorDetails().errorMessage());
                assertTrue(ids(client, name).isEmpty());
                String id = client.createManagedPrefixList(r -> r.prefixListName(name).addressFamily("IPv4")
                        .maxEntries(1).entries(AddPrefixListEntry.builder().cidr("192.0.2.0/24").build()))
                        .prefixList().prefixListId();
                assertEquals("192.0.2.0/24", client.getManagedPrefixListEntries(r -> r.prefixListId(id))
                        .entries().getFirst().cidr());
            } finally {
                for (String id : ids(client, name)) {
                    client.deleteManagedPrefixList(r -> r.prefixListId(id));
                }
            }
            assertTrue(ids(client, name).isEmpty());
        }
    }

    @Test
    void cloudFormationRejectsHostBitsAndPreservesCanonicalEntry() {
        try (Ec2Client client = client()) {
            for (String cidr : List.of("192.0.2.19/24", "192.0.2.0/24")) {
                boolean hostBits = cidr.equals("192.0.2.19/24");
                String name = hostBits ? "cfn-host-bit-cidr" : "cfn-canonical-cidr";
                try {
                    given().header("Authorization", CFN_AUTH)
                            .formParam("Action", "CreateStack").formParam("StackName", name)
                            .formParam("TemplateBody", """
                                    {"Resources":{"List":{"Type":"AWS::EC2::PrefixList","Properties":{
                                      "PrefixListName":"%s","AddressFamily":"IPv4","MaxEntries":1,
                                      "Entries":[{"Cidr":"%s"}]}}}}
                                    """.formatted(name, cidr))
                            .post("/").then().statusCode(200);
                    CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(name, CFN_AUTH);
                    assertEquals(hostBits ? "ROLLBACK_COMPLETE" : "CREATE_COMPLETE", state.status());
                    if (hostBits) {
                        assertTrue(CfnStackWaits.failedResourceReason(name, CFN_AUTH)
                                .contains("The specified CIDR (192.0.2.19/24) is not valid."));
                        assertTrue(ids(client, name).isEmpty());
                    } else {
                        List<String> ids = ids(client, name);
                        assertEquals(1, ids.size());
                        assertEquals(cidr, client.getManagedPrefixListEntries(r -> r.prefixListId(ids.getFirst()))
                                .entries().getFirst().cidr());
                    }
                } finally {
                    given().header("Authorization", CFN_AUTH).formParam("Action", "DeleteStack")
                            .formParam("StackName", name).post("/").then().statusCode(200);
                    CfnStackWaits.awaitStackDeleted(name);
                }
                assertTrue(ids(client, name).isEmpty());
            }
        }
    }

    private static List<String> ids(Ec2Client client, String name) {
        return client.describeManagedPrefixLists(r -> r.filters(Filter.builder()
                        .name("prefix-list-name").values(name).build())).prefixLists().stream()
                .map(list -> list.prefixListId()).toList();
    }

    private static Ec2Client client() {
        return Ec2Client.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
