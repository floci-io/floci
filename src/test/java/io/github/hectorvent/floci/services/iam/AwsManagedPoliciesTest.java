package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-partition managed policy catalogs are derived from the commercial data by exactly two
 * substitutions: {@code arn:aws:} takes the partition (what AWS's SAM translator does) and the
 * region-bearing {@code kms:ViaService} hosts take the partition's DNS suffix. Bare service
 * principals, service-linked role paths, the {@code ::aws:policy/} owner slot and the
 * pre-existing {@code .amazonaws.com.cn} entries must come through untouched.
 */
class AwsManagedPoliciesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BARE_PRINCIPAL =
            Pattern.compile("\"([a-z0-9-]+(?:\\.[a-z0-9-]+)?\\.amazonaws\\.com)\"");

    @Test
    void theCommercialCatalogIsTheBundledDataItself() {
        assertSame(AwsManagedPolicies.POLICIES, AwsManagedPolicies.forPartition("aws"));
        assertEquals("arn:aws:iam::aws:policy/AdministratorAccess", find("aws", "AdministratorAccess").arn());
    }

    @ParameterizedTest
    @MethodSource("io.github.hectorvent.floci.testing.PartitionMatrix#cases")
    void everyPartitionGetsTheFullCatalogUnderItsOwnArnPrefix(PartitionCase partition) {
        List<AwsManagedPolicies.ManagedPolicyDef> catalog = AwsManagedPolicies.forPartition(partition.partition());
        assertEquals(AwsManagedPolicies.POLICIES.size(), catalog.size());
        AwsManagedPolicies.ManagedPolicyDef admin = find(partition.partition(), "AdministratorAccess");
        assertEquals("arn:" + partition.partition() + ":iam::aws:policy/AdministratorAccess", admin.arn());
        assertTrue(AwsManagedPolicies.isManagedPolicyArn(admin.arn()));
        assertSame(catalog, AwsManagedPolicies.forPartition(partition.partition()), "built once per partition");
    }

    @Test
    void anUnpublishedPartitionIsRefusedNotInvented() {
        assertThrows(IllegalArgumentException.class, () -> AwsManagedPolicies.forPartition("aws-xyz"));
        assertFalse(AwsManagedPolicies.isManagedPolicyArn("arn:aws:iam::000000000000:policy/mine"));
        assertFalse(AwsManagedPolicies.isManagedPolicyArn(null));
    }

    @Test
    void documentArnsTakeThePartitionAndTheOwnerSlotStaysAws() throws Exception {
        String china = find("aws-cn", "AWSLambdaExecute").document();
        assertFalse(china.contains("arn:aws:"), "every commercial ARN in the document was rewritten");
        assertTrue(china.contains("arn:aws-cn:logs:"), china);
        // A document that references another managed policy keeps the owner slot literally aws.
        String batch = find("aws-cn", "AWSBatchServiceRole").document();
        assertFalse(batch.contains("arn:aws:"));
        MAPPER.readTree(china);
        MAPPER.readTree(batch);
    }

    @Test
    void bareServicePrincipalsAndPreExistingChinaPrincipalsAreLeftAlone() {
        String commercial = find("aws", "AWSBatchServiceRole").document();
        String china = find("aws-cn", "AWSBatchServiceRole").document();
        assertEquals(count(commercial, "ec2.amazonaws.com.cn"), count(china, "ec2.amazonaws.com.cn"),
                "the .cn principal AWS already spells out is neither doubled nor removed");
        Matcher bare = BARE_PRINCIPAL.matcher(commercial);
        int principals = 0;
        while (bare.find()) {
            principals++;
            assertTrue(china.contains("\"" + bare.group(1) + "\""), bare.group(1) + " must stay universal");
        }
        assertTrue(principals > 0, "the fixture has bare principals to check");
    }

    @Test
    void regionBearingViaServiceHostsTakeThePartitionSuffix() {
        String commercial = "{\"Condition\":{\"StringEquals\":{\"kms:ViaService\":[\"s3.*.amazonaws.com\","
                + "\"secretsmanager.us-east-1.amazonaws.com\",\"ec2.amazonaws.com\",\"ec2.amazonaws.com.cn\"]}},"
                + "\"Resource\":\"arn:aws:iam::aws:policy/X\","
                + "\"Principal\":\"arn:aws:iam::123456789012:role/aws-service-role/ecs.amazonaws.com/"
                + "AWSServiceRoleForECS\"}";
        String china = AwsManagedPolicies.rewriteDocument(commercial, "arn:aws-cn:", "amazonaws.com.cn");
        assertTrue(china.contains("\"s3.*.amazonaws.com.cn\""), china);
        assertTrue(china.contains("\"secretsmanager.us-east-1.amazonaws.com.cn\""), china);
        assertTrue(china.contains("\"ec2.amazonaws.com\""), "a bare principal is universal");
        assertTrue(china.contains("\"ec2.amazonaws.com.cn\"") && !china.contains(".cn.cn"), china);
        assertTrue(china.contains("arn:aws-cn:iam::aws:policy/X"), "owner slot stays aws");
        assertTrue(china.contains("role/aws-service-role/ecs.amazonaws.com/AWSServiceRoleForECS"),
                "SLR path untouched");

        String govcloud = AwsManagedPolicies.rewriteDocument(commercial, "arn:aws-us-gov:", "amazonaws.com");
        assertTrue(govcloud.contains("\"s3.*.amazonaws.com\"")
                && govcloud.contains("arn:aws-us-gov:iam::aws:policy/X"));
    }

    @Test
    void theWholeChinaCatalogStillParsesAndCarriesNoCommercialArn() throws Exception {
        int viaServiceHosts = 0;
        for (AwsManagedPolicies.ManagedPolicyDef def : AwsManagedPolicies.forPartition("aws-cn")) {
            JsonNode document = MAPPER.readTree(def.document());
            assertFalse(document.isNull(), def.name());
            assertFalse(def.document().contains("arn:aws:"), def.name());
            if (def.document().contains(".*.amazonaws.com.cn")) {
                viaServiceHosts++;
            }
        }
        assertTrue(viaServiceHosts > 100, "the kms:ViaService hosts were rewritten: " + viaServiceHosts);
    }

    private static AwsManagedPolicies.ManagedPolicyDef find(String partition, String name) {
        return AwsManagedPolicies.forPartition(partition).stream()
                .filter(def -> def.name().equals(name)).findFirst().orElseThrow();
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
