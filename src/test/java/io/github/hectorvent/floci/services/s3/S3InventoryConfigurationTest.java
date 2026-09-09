package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3InventoryConfigurationTest {

    private static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";

    private static final String DESTINATION = "<Destination><S3BucketDestination>"
            + "<Format>CSV</Format>"
            + "<AccountId>123456789012</AccountId>"
            + "<Bucket>arn:aws:s3:::destination-bucket</Bucket>"
            + "<Prefix>inventory/</Prefix>"
            + "</S3BucketDestination></Destination>";

    private static final String MINIMAL_DESTINATION = "<Destination><S3BucketDestination>"
            + "<Format>CSV</Format>"
            + "<Bucket>arn:aws:s3:::destination-bucket</Bucket>"
            + "</S3BucketDestination></Destination>";

    private static String body(String inner) {
        return "<InventoryConfiguration xmlns=\"" + NS + "\">" + inner + "</InventoryConfiguration>";
    }

    private static String required(String destination) {
        return "<Id>report1</Id><IsEnabled>true</IsEnabled>" + destination
                + "<Schedule><Frequency>Daily</Frequency></Schedule>"
                + "<IncludedObjectVersions>All</IncludedObjectVersions>";
    }

    @Test
    void parsesTheRequiredElementsInAwsOrderWhateverOrderTheyArrivedIn() {
        String shuffled = "<IncludedObjectVersions>All</IncludedObjectVersions>"
                + "<Schedule><Frequency>Daily</Frequency></Schedule>"
                + "<IsEnabled>true</IsEnabled>"
                + MINIMAL_DESTINATION
                + "<Id>report1</Id>";

        S3InventoryConfiguration parsed = S3InventoryConfiguration.parse(body(shuffled));

        assertEquals("report1", parsed.id());
        assertEquals(required(MINIMAL_DESTINATION), parsed.innerXml());
    }

    @Test
    void keepsTheFilterOptionalFieldsAndEncryption() {
        String encrypted = DESTINATION.replace("</S3BucketDestination>",
                "<Encryption><SSE-KMS><KeyId>arn:aws:kms:us-east-1:123456789012:key/k</KeyId></SSE-KMS></Encryption>"
                        + "</S3BucketDestination>");
        String parsed = S3InventoryConfiguration.parse(body(
                "<Id>report1</Id><IsEnabled>false</IsEnabled>"
                        + "<Filter><Prefix>docs/</Prefix></Filter>"
                        + encrypted
                        + "<IncludedObjectVersions>Current</IncludedObjectVersions>"
                        + "<Schedule><Frequency>Weekly</Frequency></Schedule>"
                        + "<OptionalFields><Field>Size</Field><Field>ETag</Field></OptionalFields>")).innerXml();

        assertEquals("<Id>report1</Id><IsEnabled>false</IsEnabled>" + encrypted
                + "<Schedule><Frequency>Weekly</Frequency></Schedule>"
                + "<Filter><Prefix>docs/</Prefix></Filter>"
                + "<IncludedObjectVersions>Current</IncludedObjectVersions>"
                + "<OptionalFields><Field>Size</Field><Field>ETag</Field></OptionalFields>", parsed);
    }

    @Test
    void serializesSseS3AsAnEmptyElement() {
        String parsed = S3InventoryConfiguration.parse(body(required(
                MINIMAL_DESTINATION.replace("</S3BucketDestination>",
                        "<Encryption><SSE-S3/></Encryption></S3BucketDestination>")))).innerXml();

        assertEquals(required(MINIMAL_DESTINATION.replace("</S3BucketDestination>",
                "<Encryption><SSE-S3/></Encryption></S3BucketDestination>")), parsed);
    }

    @Test
    void escapesValuesOnTheWayBackOut() {
        String parsed = S3InventoryConfiguration.parse(body(required(MINIMAL_DESTINATION)
                .replace("<Id>report1</Id>", "<Id>a&amp;b</Id>")
                + "<Filter><Prefix>x&lt;y</Prefix></Filter>")).innerXml();

        assertEquals(required(MINIMAL_DESTINATION).replace("<Id>report1</Id>", "<Id>a&amp;b</Id>")
                .replace("<IncludedObjectVersions>", "<Filter><Prefix>x&lt;y</Prefix></Filter><IncludedObjectVersions>"),
                parsed);
    }

    @Test
    void rejectsBodiesThatDoNotMatchTheSchema() {
        // A missing required element, a stray element, wrong root, empty and non-XML bodies are
        // all MalformedXML on AWS.
        String all = required(MINIMAL_DESTINATION);
        for (String invalid : new String[]{
                body(""),
                body(all.replace("<Id>report1</Id>", "<Id> </Id>")),
                body(all.replace("<Id>report1</Id>", "")),
                body(all.replace("<IsEnabled>true</IsEnabled>", "")),
                body(all.replace("<IsEnabled>true</IsEnabled>", "<IsEnabled>yes</IsEnabled>")),
                body(all.replace("<IncludedObjectVersions>All</IncludedObjectVersions>", "")),
                body(all.replace("<IncludedObjectVersions>All</IncludedObjectVersions>",
                        "<IncludedObjectVersions>Latest</IncludedObjectVersions>")),
                body(all.replace("<Schedule><Frequency>Daily</Frequency></Schedule>", "")),
                body(all.replace("Daily", "Hourly")),
                body(all.replace(MINIMAL_DESTINATION, "")),
                body(all + "<Unknown/>"),
                body(all + "<Filter><Tag><Key>k</Key><Value>v</Value></Tag></Filter>"),
                body(all + "<OptionalFields><Field>Colour</Field></OptionalFields>"),
                "<SomethingElse><Id>a</Id></SomethingElse>",
                "not xml at all",
                ""}) {
            AwsException e = assertThrows(AwsException.class, () -> S3InventoryConfiguration.parse(invalid),
                    () -> "expected rejection of: " + invalid);
            assertEquals("MalformedXML", e.getErrorCode());
            assertEquals(400, e.getHttpStatus());
        }
    }

    @Test
    void rejectsDestinationsOutsideTheSchema() {
        String unknownFormat = DESTINATION.replace("CSV", "JSON");
        String missingFormat = DESTINATION.replace("<Format>CSV</Format>", "");
        String missingBucket = DESTINATION.replace("<Bucket>arn:aws:s3:::destination-bucket</Bucket>", "");
        String strayElement = DESTINATION.replace("<Prefix>inventory/</Prefix>",
                "<Prefix>inventory/</Prefix><Region>eu</Region>");
        String kmsWithoutKey = DESTINATION.replace("</S3BucketDestination>",
                "<Encryption><SSE-KMS/></Encryption></S3BucketDestination>");
        String twoEncryptions = DESTINATION.replace("</S3BucketDestination>",
                "<Encryption><SSE-S3/><SSE-KMS><KeyId>k</KeyId></SSE-KMS></Encryption></S3BucketDestination>");

        for (String invalid : new String[]{
                unknownFormat, missingFormat, missingBucket, strayElement, kmsWithoutKey, twoEncryptions}) {
            AwsException e = assertThrows(AwsException.class,
                    () -> S3InventoryConfiguration.parse(body(required(invalid))),
                    () -> "expected rejection of: " + invalid);
            assertEquals("MalformedXML", e.getErrorCode());
        }
    }
}
