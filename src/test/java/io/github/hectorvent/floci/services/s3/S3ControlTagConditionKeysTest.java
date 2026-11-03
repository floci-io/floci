package io.github.hectorvent.floci.services.s3;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3ControlTagConditionKeysTest {

    private static final String TAGS = "<TagResourceRequest><Tags>"
            + "<Tag><Key>Owner</Key><Value>team</Value></Tag>"
            + "<Tag><Key>Env</Key><Value>dev</Value></Tag>"
            + "</Tags></TagResourceRequest>";

    @Test
    void resourceAccountCoverageComesFromTheActionTable() {
        assertTrue(S3ControlTagConditionKeys.takes("s3:GetObject", "s3:ResourceAccount"));
        assertTrue(S3ControlTagConditionKeys.takes("s3:PutObject", "s3:ResourceAccount"));
        assertTrue(S3ControlTagConditionKeys.takes("s3:ListBucket", "s3:ResourceAccount"));
        assertFalse(S3ControlTagConditionKeys.takes("s3:PutAccessPointPublicAccessBlock", "s3:ResourceAccount"));
        assertFalse(S3ControlTagConditionKeys.takes("s3:AllowVendedLogDeliveryForResource", "s3:ResourceAccount"));
        assertFalse(S3ControlTagConditionKeys.takes(null, "s3:ResourceAccount"));
    }

    @Test
    void tagResourceTakesEachTagOfItsBodyAndTheirKeys() {
        assertEquals(Map.of("aws:RequestTag/Owner", List.of("team"), "aws:RequestTag/Env", List.of("dev"),
                        "aws:TagKeys", List.of("Owner", "Env")),
                S3ControlTagConditionKeys.conditions("s3:TagResource", () -> TAGS, new MultivaluedHashMap<>()));
    }

    @Test
    void untagResourceTakesTheKeysItsQueryNamesAndNoRequestTag() {
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.addAll("tagKeys", "Owner", "Env");
        assertEquals(Map.of("aws:TagKeys", List.of("Owner", "Env")),
                S3ControlTagConditionKeys.conditions("s3:UntagResource", () -> TAGS, query));
    }

    @Test
    void aRequestCarryingNoTagsAndAnActionTakingNoKeyGetNothing() {
        assertNull(S3ControlTagConditionKeys.conditions("s3:TagResource", () -> "", new MultivaluedHashMap<>()));
        assertNull(S3ControlTagConditionKeys.conditions("s3:UntagResource", () -> "", new MultivaluedHashMap<>()));
        assertNull(S3ControlTagConditionKeys.conditions("s3:ListAccessPoints", () -> TAGS,
                new MultivaluedHashMap<>()));
    }
}
