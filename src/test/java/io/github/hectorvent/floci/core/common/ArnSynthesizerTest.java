package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.ec2.Ec2ArnSynthesizer;
import io.github.hectorvent.floci.services.s3.S3ArnSynthesizer;
import io.github.hectorvent.floci.services.sqs.SqsArnSynthesizer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three services whose models carry an id or a name where the tagging scan needs an ARN.
 * Each synthesizer must produce the ARN AWS produces, and must decline anything it does not own —
 * a synthesizer that answers for a store it does not understand puts a wrong ARN in front of
 * every caller that joins on it.
 */
class ArnSynthesizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ─── EC2 ───────────────────────────────────────────────────────────────────

    @Test
    void ec2BuildsTheArnFromTheStorageKey() {
        Optional<String> arn = new Ec2ArnSynthesizer().synthesize(
                "ec2", "ec2-volumes.json", "us-east-1::vol-0abc", ACCOUNT, json("{}"));
        assertEquals(Optional.of("arn:aws:ec2:us-east-1:000000000000:volume/vol-0abc"), arn);
    }

    /** The key holds the id even when the model has several; a subnet must not become its VPC. */
    @Test
    void ec2IgnoresOtherIdsOnTheModel() {
        Optional<String> arn = new Ec2ArnSynthesizer().synthesize(
                "ec2", "ec2-subnets.json", "us-east-1::subnet-0abc", ACCOUNT,
                json("{\"subnetId\": \"subnet-0abc\", \"vpcId\": \"vpc-0def\"}"));
        assertEquals(Optional.of("arn:aws:ec2:us-east-1:000000000000:subnet/subnet-0abc"), arn);
    }

    @Test
    void ec2DeclinesOtherServicesAndUnkeyedEntries() {
        Ec2ArnSynthesizer synthesizer = new Ec2ArnSynthesizer();
        assertTrue(synthesizer.synthesize("iam", "iam-roles.json", "us-east-1::vol-0abc", ACCOUNT, json("{}")).isEmpty());
        assertTrue(synthesizer.synthesize("ec2", "ec2-volumes.json", "vol-0abc", ACCOUNT, json("{}")).isEmpty());
        assertTrue(synthesizer.synthesize("ec2", "ec2-volumes.json", "us-east-1::", ACCOUNT, json("{}")).isEmpty());
    }

    // ─── S3 ────────────────────────────────────────────────────────────────────

    @Test
    void s3BucketArnsCarryNeitherRegionNorAccount() {
        Optional<String> arn = new S3ArnSynthesizer().synthesize(
                "s3", "s3-buckets.json", "my-bucket", ACCOUNT, json("{\"name\": \"my-bucket\"}"));
        assertEquals(Optional.of("arn:aws:s3:::my-bucket"), arn);
    }

    /** The object store is the same service with a different ARN shape, so it must be declined. */
    @Test
    void s3DeclinesTheObjectStore() {
        assertTrue(new S3ArnSynthesizer()
                .synthesize("s3", "s3-objects.json", "my-bucket/key.txt", ACCOUNT, json("{}"))
                .isEmpty());
    }

    // ─── SQS ───────────────────────────────────────────────────────────────────

    @Test
    void sqsBuildsTheArnFromRegionAccountAndName() {
        Optional<String> arn = new SqsArnSynthesizer().synthesize(
                "sqs", "sqs-queues.json", "us-east-1::000000000000/my-queue", ACCOUNT,
                json("{\"queueName\": \"my-queue\", \"accountId\": \"000000000000\"}"));
        assertEquals(Optional.of("arn:aws:sqs:us-east-1:000000000000:my-queue"), arn);
    }

    /** A model missing its own fields still has the key, which carries both segments. */
    @Test
    void sqsFallsBackToTheStorageKey() {
        Optional<String> arn = new SqsArnSynthesizer().synthesize(
                "sqs", "sqs-queues.json", "eu-west-1::111122223333/other-queue", ACCOUNT, json("{}"));
        assertEquals(Optional.of("arn:aws:sqs:eu-west-1:111122223333:other-queue"), arn);
    }

    @Test
    void sqsDeclinesOtherStores() {
        assertTrue(new SqsArnSynthesizer()
                .synthesize("sqs", "sqs-messages.json", "us-east-1::000000000000/q", ACCOUNT, json("{}"))
                .isEmpty());
    }
}
