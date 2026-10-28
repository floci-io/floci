package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The registry setting actions checked against the vendored ECR model constraints. */
class EcrRequestShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DESTINATION = "{\"region\":\"us-west-2\",\"registryId\":\"123456789012\"}";

    private static JsonNode json(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException(body, e);
        }
    }

    private static String refusal(String operation, String body) {
        AwsException e = assertThrows(AwsException.class, () -> EcrRequestShapes.check(operation, json(body)), body);
        assertEquals("InvalidParameterException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        return e.getMessage();
    }

    private static String replication(String rules) {
        return "{\"replicationConfiguration\":{\"rules\":[" + rules + "]}}";
    }

    private static String rule(String destinations, String filters) {
        return "{\"destinations\":[" + destinations + "]" + (filters == null ? "" : ",\"repositoryFilters\":[" + filters + "]")
                + "}";
    }

    @Test
    void registryPolicyLengthAndPresence() {
        assertEquals("Invalid parameter at 'policyText' failed to satisfy constraint: "
                        + "'Member must have length less than or equal to 10240'",
                refusal("PutRegistryPolicy", "{\"policyText\":\"" + "x".repeat(10241) + "\"}"));
        assertEquals("Invalid parameter at 'policyText' failed to satisfy constraint: 'Member must not be null'",
                refusal("PutRegistryPolicy", "{}"));
        assertDoesNotThrow(() -> EcrRequestShapes.check("PutRegistryPolicy",
                json("{\"policyText\":\"" + "x".repeat(10240) + "\"}")));
    }

    @Test
    void replicationRulesDestinationsAndFilters() {
        assertEquals("Invalid parameter at 'replicationConfiguration.rules.destinations.region' failed to satisfy "
                        + "constraint: 'must satisfy regular expression '[0-9a-z-]{2,25}''",
                refusal("PutReplicationConfiguration", replication(rule(
                        "{\"region\":\"US_WEST\",\"registryId\":\"123456789012\"}", null))));
        assertEquals("Invalid parameter at 'replicationConfiguration.rules.destinations.registryId' failed to satisfy "
                        + "constraint: 'must satisfy regular expression '[0-9]{12}''",
                refusal("PutReplicationConfiguration", replication(rule(
                        "{\"region\":\"us-west-2\",\"registryId\":\"12345\"}", null))));
        assertEquals("Invalid parameter at 'replicationConfiguration.rules.repositoryFilters.filterType' failed to "
                        + "satisfy constraint: 'Member must satisfy enum value set: [PREFIX_MATCH]'",
                refusal("PutReplicationConfiguration", replication(rule(DESTINATION,
                        "{\"filter\":\"prod\",\"filterType\":\"WILDCARD\"}"))));
        assertEquals("Invalid parameter at 'replicationConfiguration.rules.repositoryFilters' failed to satisfy "
                        + "constraint: 'Member must have length greater than or equal to 1'",
                refusal("PutReplicationConfiguration", replication(rule(DESTINATION, ""))));
        assertEquals("Invalid parameter at 'replicationConfiguration.rules' failed to satisfy constraint: "
                        + "'Member must have length less than or equal to 25'",
                refusal("PutReplicationConfiguration",
                        replication(String.join(",", Collections.nCopies(26, rule(DESTINATION, null))))));
        assertEquals("Invalid parameter at 'replicationConfiguration.rules.destinations' failed to satisfy "
                        + "constraint: 'Member must not be null'",
                refusal("PutReplicationConfiguration", replication("{}")));
        assertEquals("Invalid parameter at 'replicationConfiguration' failed to satisfy constraint: "
                        + "'Member must not be null'",
                refusal("PutReplicationConfiguration", "{}"));
        refusal("PutReplicationConfiguration", replication(rule(DESTINATION,
                "{\"filter\":\"Prod\",\"filterType\":\"PREFIX_MATCH\"}")));
        refusal("PutReplicationConfiguration", replication(rule(DESTINATION,
                "{\"filter\":\"p\",\"filterType\":\"PREFIX_MATCH\"}")));
        refusal("PutReplicationConfiguration", replication(rule(
                String.join(",", Collections.nCopies(101, DESTINATION)), null)));

        assertDoesNotThrow(() -> EcrRequestShapes.check("PutReplicationConfiguration",
                json(replication(String.join(",", Collections.nCopies(25, rule(DESTINATION,
                        "{\"filter\":\"prod/api\",\"filterType\":\"PREFIX_MATCH\"}")))))));
        // An empty list is how a configuration is cleared.
        assertDoesNotThrow(() -> EcrRequestShapes.check("PutReplicationConfiguration", json(replication(""))));
    }

    @Test
    void scanningTypeFrequencyAndFilters() {
        assertEquals("Invalid parameter at 'scanType' failed to satisfy constraint: "
                        + "'Member must satisfy enum value set: [BASIC, ENHANCED]'",
                refusal("PutRegistryScanningConfiguration", "{\"scanType\":\"FULL\"}"));
        assertEquals("Invalid parameter at 'rules.scanFrequency' failed to satisfy constraint: "
                        + "'Member must satisfy enum value set: [SCAN_ON_PUSH, CONTINUOUS_SCAN, MANUAL]'",
                refusal("PutRegistryScanningConfiguration", "{\"scanType\":\"ENHANCED\",\"rules\":[{\"scanFrequency\":"
                        + "\"HOURLY\",\"repositoryFilters\":[{\"filter\":\"*\",\"filterType\":\"WILDCARD\"}]}]}"));
        assertEquals("Invalid parameter at 'rules.repositoryFilters.filterType' failed to satisfy constraint: "
                        + "'Member must satisfy enum value set: [WILDCARD]'",
                refusal("PutRegistryScanningConfiguration", "{\"rules\":[{\"scanFrequency\":\"SCAN_ON_PUSH\","
                        + "\"repositoryFilters\":[{\"filter\":\"*\",\"filterType\":\"PREFIX_MATCH\"}]}]}"));
        refusal("PutRegistryScanningConfiguration", "{\"rules\":[{\"scanFrequency\":\"SCAN_ON_PUSH\","
                + "\"repositoryFilters\":[{\"filter\":\"Bad Filter\",\"filterType\":\"WILDCARD\"}]}]}");
        refusal("PutRegistryScanningConfiguration", "{\"rules\":[{\"scanFrequency\":\"SCAN_ON_PUSH\"}]}");
        String scanRule = "{\"scanFrequency\":\"SCAN_ON_PUSH\",\"repositoryFilters\":[{\"filter\":\"*\","
                + "\"filterType\":\"WILDCARD\"}]}";
        assertEquals("Invalid parameter at 'rules' failed to satisfy constraint: "
                        + "'Member must have length less than or equal to 2'",
                refusal("PutRegistryScanningConfiguration",
                        "{\"rules\":[" + String.join(",", Collections.nCopies(3, scanRule)) + "]}"));
        assertEquals("Invalid parameter at 'scanType' failed to satisfy constraint: 'Member must be a string'",
                refusal("PutRegistryScanningConfiguration", "{\"scanType\":1}"));

        assertDoesNotThrow(() -> EcrRequestShapes.check("PutRegistryScanningConfiguration",
                json("{\"scanType\":\"ENHANCED\",\"rules\":[" + scanRule + "," + scanRule + "]}")));
    }

    private static final String PREFIX_PATTERN =
            "^([a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*(\\/[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*)*\\/?|ROOT)$";

    private static String template(String fields) {
        return "{\"prefix\":\"prod\",\"appliedFor\":[\"REPLICATION\"]" + fields + "}";
    }

    private static String constraint(String member, String rule) {
        return "Invalid parameter at '" + member + "' failed to satisfy constraint: '" + rule + "'";
    }

    @Test
    void creationTemplateFields() {
        String create = "CreateRepositoryCreationTemplate";
        assertEquals(constraint("prefix", "must satisfy regular expression '" + PREFIX_PATTERN + "'"),
                refusal(create, "{\"prefix\":\"Prod\",\"appliedFor\":[\"REPLICATION\"]}"));
        assertEquals(constraint("prefix", "Member must have length less than or equal to 256"),
                refusal(create, "{\"prefix\":\"" + "a".repeat(257) + "\",\"appliedFor\":[\"REPLICATION\"]}"));
        assertEquals(constraint("appliedFor", "Member must not be null"), refusal(create, "{\"prefix\":\"prod\"}"));
        assertEquals(constraint("appliedFor", "Member must satisfy enum value set: "
                        + "[REPLICATION, PULL_THROUGH_CACHE, CREATE_ON_PUSH]"),
                refusal(create, "{\"prefix\":\"prod\",\"appliedFor\":[\"PULL\"]}"));
        assertEquals(constraint("description", "Member must have length less than or equal to 256"),
                refusal(create, template(",\"description\":\"" + "d".repeat(257) + "\"")));
        assertEquals(constraint("encryptionConfiguration.encryptionType",
                        "Member must satisfy enum value set: [AES256, KMS, KMS_DSSE]"),
                refusal(create, template(",\"encryptionConfiguration\":{\"encryptionType\":\"RSA\"}")));
        assertEquals(constraint("encryptionConfiguration.kmsKey", "must satisfy regular expression "
                        + "'^$|arn:aws[a-z0-9-]*:kms:[a-z0-9-]+:[0-9]{12}:key\\/[a-z0-9-]+'"),
                refusal(create, template(",\"encryptionConfiguration\":{\"encryptionType\":\"KMS\","
                        + "\"kmsKey\":\"alias/mine\"}")));
        assertEquals(constraint("imageTagMutability", "Member must satisfy enum value set: "
                        + "[MUTABLE, IMMUTABLE, IMMUTABLE_WITH_EXCLUSION, MUTABLE_WITH_EXCLUSION]"),
                refusal(create, template(",\"imageTagMutability\":\"FROZEN\"")));
        assertEquals(constraint("imageTagMutabilityExclusionFilters.filterType",
                        "Member must satisfy enum value set: [WILDCARD]"),
                refusal(create, template(",\"imageTagMutabilityExclusionFilters\":[{\"filterType\":\"PREFIX_MATCH\","
                        + "\"filter\":\"latest\"}]")));
        assertEquals(constraint("imageTagMutabilityExclusionFilters.filter",
                        "must satisfy regular expression '^[0-9a-zA-Z._*-]{1,128}$'"),
                refusal(create, template(",\"imageTagMutabilityExclusionFilters\":[{\"filterType\":\"WILDCARD\","
                        + "\"filter\":\"no spaces\"}]")));
        String filter = "{\"filterType\":\"WILDCARD\",\"filter\":\"v*\"}";
        assertEquals(constraint("imageTagMutabilityExclusionFilters", "Member must have length less than or equal to 5"),
                refusal(create, template(",\"imageTagMutabilityExclusionFilters\":["
                        + String.join(",", Collections.nCopies(6, filter)) + "]")));
        assertEquals(constraint("resourceTags.Value", "Member must not be null"),
                refusal(create, template(",\"resourceTags\":[{\"Key\":\"team\"}]")));
        assertEquals(constraint("repositoryPolicy", "Member must have length less than or equal to 10240"),
                refusal(create, template(",\"repositoryPolicy\":\"" + "p".repeat(10241) + "\"")));
        assertEquals(constraint("lifecyclePolicy", "Member must have length less than or equal to 30720"),
                refusal(create, template(",\"lifecyclePolicy\":\"" + "l".repeat(30721) + "\"")));
        assertEquals(constraint("customRoleArn", "Member must have length less than or equal to 2048"),
                refusal(create, template(",\"customRoleArn\":\"" + "r".repeat(2049) + "\"")));

        // The model gives the exclusion filters at least one entry, so an empty list does not clear them.
        assertEquals(constraint("imageTagMutabilityExclusionFilters",
                        "Member must have length greater than or equal to 1"),
                refusal("UpdateRepositoryCreationTemplate",
                        "{\"prefix\":\"prod\",\"imageTagMutabilityExclusionFilters\":[]}"));
        assertEquals(constraint("prefix", "Member must not be null"),
                refusal("UpdateRepositoryCreationTemplate", "{\"description\":\"x\"}"));
        assertEquals(constraint("prefixes", "must satisfy regular expression '" + PREFIX_PATTERN + "'"),
                refusal("DescribeRepositoryCreationTemplates", "{\"prefixes\":[\"Bad\"]}"));
        assertEquals(constraint("maxResults", "Member must have value less than or equal to 1000"),
                refusal("DescribeRepositoryCreationTemplates", "{\"maxResults\":1001}"));
        assertEquals(constraint("prefix", "Member must not be null"), refusal("DeleteRepositoryCreationTemplate", "{}"));

        // Emptying a string clears it, so the model allows it where an update can send one.
        assertDoesNotThrow(() -> EcrRequestShapes.check("UpdateRepositoryCreationTemplate", json(
                "{\"prefix\":\"ROOT\",\"description\":\"\",\"repositoryPolicy\":\"\",\"lifecyclePolicy\":\"\","
                        + "\"customRoleArn\":\"\",\"encryptionConfiguration\":{\"encryptionType\":\"KMS\","
                        + "\"kmsKey\":\"\"},\"resourceTags\":[],\"appliedFor\":[]}")));
        assertDoesNotThrow(() -> EcrRequestShapes.check(create, json(template(
                ",\"encryptionConfiguration\":{\"encryptionType\":\"KMS_DSSE\",\"kmsKey\":"
                        + "\"arn:aws-us-gov:kms:us-gov-west-1:000000000000:key/1234abcd-12ab\"},"
                        + "\"imageTagMutabilityExclusionFilters\":[" + String.join(",", Collections.nCopies(5, filter))
                        + "],\"resourceTags\":[{\"Key\":\"team\",\"Value\":\"platform\"}]"))));
    }

    @Test
    void readsTakeAnEmptyBody() {
        for (String operation : new String[] {"GetRegistryPolicy", "DeleteRegistryPolicy", "DescribeRegistry",
                "GetRegistryScanningConfiguration"}) {
            assertDoesNotThrow(() -> EcrRequestShapes.check(operation, json("{}")), operation);
        }
    }
}
