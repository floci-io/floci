package io.floci.aws.services.redshift;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.RedshiftException;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Compatibility of generated Identifier inference with signed permission and missing-resource errors. */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class RedshiftIdentifierArnPolicyIntegrationTest {

    private static final String RAW = "arn:aws:redshift:::cluster:inference-missing";
    private static final String LOCAL = "arn:aws:redshift:us-east-1:000000000000:cluster:inference-missing";

    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"describe, raw, 404", "delete, raw, 404",
            "describe, local, 403", "delete, local, 403",
            "describe, wildcard, 404", "delete, wildcard, 404",
            "describe, denyRaw, 403", "delete, denyRaw, 403",
            "describe, denyLocal, 404", "delete, denyLocal, 404",
            "describe, full, 404", "delete, full, 404",
            "describe, name, 404", "delete, name, 404"})
    void inferredIdentifierPreservesPolicyResourceAndHandlerPrecedence(String operation, String policyKind,
                                                                      int status) throws Exception {
        String resource = switch (policyKind) {
            case "raw" -> RAW;
            case "local", "full", "name" -> LOCAL;
            default -> "*";
        };
        String statements = statement("Allow", resource);
        if (policyKind.equals("denyRaw") || policyKind.equals("denyLocal")) {
            statements += "," + statement("Deny", policyKind.equals("denyRaw") ? RAW : LOCAL);
        }
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}";
        String identifier = switch (policyKind) {
            case "full" -> LOCAL;
            case "name" -> "inference-missing";
            default -> RAW;
        };
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "identifier-", "policy", document);
             RedshiftClient client = AwsTestClients.create(RedshiftClient.builder(), user.accessKeyId(),
                     user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            RedshiftException error = assertThrows(RedshiftException.class, () -> {
                if (operation.equals("describe")) {
                    client.describeClusters(request -> request.clusterIdentifier(identifier));
                } else {
                    client.deleteCluster(request -> request.clusterIdentifier(identifier).skipFinalClusterSnapshot(true));
                }
            });
            assertEquals(status, error.statusCode(), operation + " / " + policyKind);
            assertEquals(status == 403 ? "AccessDenied" : "ClusterNotFound", error.awsErrorDetails().errorCode());
        }
    }

    private static String statement(String effect, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"redshift:*\",\"Resource\":\"" + resource + "\"}";
    }
}
