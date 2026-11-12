package io.floci.aws.services.s3;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S3ResourceArnsTest {

    @Test
    void aBucketAndAnObjectAreNamedWithNoRegionOrAccount() {
        assertEquals("arn:aws:s3:::public-bucket", S3ResourceArns.bucket("aws", "public-bucket"));
        assertEquals("arn:aws:s3:::public-bucket/folder/object.txt",
                S3ResourceArns.object("aws", "public-bucket", "folder/object.txt"));
    }

    /** The ARNs carry the partition given, or a China bucket policy never matches. */
    @Test
    void theArnsCarryTheGivenPartition() {
        assertEquals("arn:aws-cn:s3:::public-bucket", S3ResourceArns.bucket("aws-cn", "public-bucket"));
        assertEquals("arn:aws-us-gov:s3:::public-bucket/k", S3ResourceArns.object("aws-us-gov", "public-bucket", "k"));
    }

    /** A key is the stored key: a leading slash and a trailing one are key data, kept as they are. */
    @Test
    void aKeyIsKeptAsStored() {
        assertEquals("arn:aws:s3:::b//email.txt", S3ResourceArns.object("aws", "b", "/email.txt"));
        assertEquals("arn:aws:s3:::b/folder/", S3ResourceArns.object("aws", "b", "folder/"));
    }

    @Test
    void aListingOffersItsPrefixAndTheEmptyStringForNone() {
        assertEquals(Map.of("s3:prefix", List.of("logs/")), S3ConditionKeys.listing("logs/"));
        assertEquals(Map.of("s3:prefix", List.of("")), S3ConditionKeys.listing(null));
    }
}
