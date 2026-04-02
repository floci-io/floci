package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.s3.model.FilterRule;
import io.github.hectorvent.floci.services.s3.model.QueueNotification;
import io.github.hectorvent.floci.services.s3.model.TopicNotification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class S3NotificationModelTest {

    // --- matchesKey ---

    @Test
    void matchesKeyWithNullFilterRulesFromJacksonDeserialization() {
        var qn = new QueueNotification("id", "arn:aws:sqs:us-east-1:000000000000:q",
                List.of("s3:ObjectCreated:*"), null);
        assertTrue(qn.matchesKey("anything"));

        var tn = new TopicNotification("id", "arn:aws:sns:us-east-1:000000000000:t",
                List.of("s3:ObjectCreated:*"), null);
        assertTrue(tn.matchesKey("anything"));
    }

    @Test
    void matchesKeyWithEmptyFilterRulesMatchesAll() {
        var qn = new QueueNotification("id", "arn", List.of("s3:ObjectCreated:*"));
        assertTrue(qn.matchesKey("anything"));
    }

    @Test
    void matchesKeyEnforcesAllRules() {
        var qn = new QueueNotification("id", "arn", List.of("s3:ObjectCreated:*"),
                List.of(new FilterRule("prefix", "images/"), new FilterRule("suffix", ".jpg")));
        assertTrue(qn.matchesKey("images/photo.jpg"));
        assertFalse(qn.matchesKey("images/photo.png"));
        assertFalse(qn.matchesKey("docs/photo.jpg"));
    }

    // --- FilterRule.matches ---

    @Test
    void filterRulePrefixMatch() {
        FilterRule rule = new FilterRule("prefix", "images/");
        assertTrue(rule.matches("images/photo.jpg"));
        assertFalse(rule.matches("docs/file.txt"));
        assertFalse(rule.matches(null));
    }

    @Test
    void filterRuleSuffixMatch() {
        FilterRule rule = new FilterRule("suffix", ".jpg");
        assertTrue(rule.matches("images/photo.jpg"));
        assertFalse(rule.matches("images/photo.png"));
    }

    @Test
    void filterRuleUnknownNameDoesNotMatch() {
        FilterRule rule = new FilterRule("extension", ".jpg");
        assertFalse(rule.matches("photo.jpg"));
    }

    @Test
    void filterRuleNameIsCaseInsensitive() {
        assertTrue(new FilterRule("Prefix", "images/").matches("images/photo.jpg"));
        assertTrue(new FilterRule("SUFFIX", ".jpg").matches("photo.jpg"));
    }
}
