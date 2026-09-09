package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3AnalyticsConfigurationTest {

    private static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";

    private static final String EXPORT = "<StorageClassAnalysis><DataExport>"
            + "<OutputSchemaVersion>V_1</OutputSchemaVersion>"
            + "<Destination><S3BucketDestination>"
            + "<Format>CSV</Format>"
            + "<BucketAccountId>123456789012</BucketAccountId>"
            + "<Bucket>arn:aws:s3:::destination-bucket</Bucket>"
            + "<Prefix>reports/</Prefix>"
            + "</S3BucketDestination></Destination>"
            + "</DataExport></StorageClassAnalysis>";

    private static String body(String inner) {
        return "<AnalyticsConfiguration xmlns=\"" + NS + "\">" + inner + "</AnalyticsConfiguration>";
    }

    @Test
    void parsesAnIdWithAnEmptyAnalysisAndNoFilter() {
        S3AnalyticsConfiguration parsed = S3AnalyticsConfiguration.parse(
                body("<Id>whole</Id><StorageClassAnalysis/>"));

        assertEquals("whole", parsed.id());
        assertEquals("<Id>whole</Id><StorageClassAnalysis></StorageClassAnalysis>", parsed.innerXml());
    }

    @Test
    void serializesTheExportInAwsOrderWhateverOrderItArrivedIn() {
        String shuffled = "<StorageClassAnalysis><DataExport>"
                + "<Destination><S3BucketDestination>"
                + "<Prefix>reports/</Prefix>"
                + "<Bucket>arn:aws:s3:::destination-bucket</Bucket>"
                + "<BucketAccountId>123456789012</BucketAccountId>"
                + "<Format>CSV</Format>"
                + "</S3BucketDestination></Destination>"
                + "<OutputSchemaVersion>V_1</OutputSchemaVersion>"
                + "</DataExport></StorageClassAnalysis>";

        assertEquals("<Id>a</Id>" + EXPORT,
                S3AnalyticsConfiguration.parse(body("<Id>a</Id>" + shuffled)).innerXml());
    }

    @Test
    void parsesEachSingleFilterPredicate() {
        assertEquals("<Id>a</Id><Filter><Prefix>logs/</Prefix></Filter><StorageClassAnalysis></StorageClassAnalysis>",
                S3AnalyticsConfiguration.parse(body(
                        "<Id>a</Id><Filter><Prefix>logs/</Prefix></Filter><StorageClassAnalysis/>")).innerXml());

        assertEquals("<Id>a</Id><Filter><Tag><Key>env</Key><Value>prod</Value></Tag></Filter>"
                        + "<StorageClassAnalysis></StorageClassAnalysis>",
                S3AnalyticsConfiguration.parse(body(
                        "<Id>a</Id><Filter><Tag><Key>env</Key><Value>prod</Value></Tag></Filter>"
                                + "<StorageClassAnalysis/>")).innerXml());
    }

    @Test
    void parsesAnAndConjunctionKeepingEveryTag() {
        String parsed = S3AnalyticsConfiguration.parse(body("""
                <Id>a</Id>
                <Filter>
                    <And>
                        <Prefix>logs/</Prefix>
                        <Tag><Key>env</Key><Value>prod</Value></Tag>
                        <Tag><Key>team</Key><Value>core</Value></Tag>
                    </And>
                </Filter>
                <StorageClassAnalysis/>
                """)).innerXml();

        assertEquals("<Id>a</Id><Filter><And><Prefix>logs/</Prefix>"
                + "<Tag><Key>env</Key><Value>prod</Value></Tag>"
                + "<Tag><Key>team</Key><Value>core</Value></Tag></And></Filter>"
                + "<StorageClassAnalysis></StorageClassAnalysis>", parsed);
    }

    @Test
    void escapesValuesOnTheWayBackOut() {
        String parsed = S3AnalyticsConfiguration.parse(
                body("<Id>a&amp;b</Id><Filter><Prefix>x&lt;y</Prefix></Filter><StorageClassAnalysis/>")).innerXml();

        assertEquals("<Id>a&amp;b</Id><Filter><Prefix>x&lt;y</Prefix></Filter>"
                + "<StorageClassAnalysis></StorageClassAnalysis>", parsed);
    }

    @Test
    void rejectsBodiesThatDoNotMatchTheSchema() {
        // Missing id, missing analysis, stray elements, wrong root element, empty and non-XML
        // bodies are all MalformedXML on AWS.
        for (String invalid : new String[]{
                body(""),
                body("<Id>   </Id><StorageClassAnalysis/>"),
                body("<StorageClassAnalysis/>"),
                body("<Id>a</Id>"),
                body("<Id>a</Id><Unknown/><StorageClassAnalysis/>"),
                body("<Id>a</Id><Filter></Filter><StorageClassAnalysis/>"),
                body("<Id>a</Id><Filter><And><Prefix>p</Prefix></And></Filter><StorageClassAnalysis/>"),
                body("<Id>a</Id><Filter><Tag><Key>k</Key></Tag></Filter><StorageClassAnalysis/>"),
                "<SomethingElse><Id>a</Id></SomethingElse>",
                "not xml at all",
                ""}) {
            AwsException e = assertThrows(AwsException.class, () -> S3AnalyticsConfiguration.parse(invalid),
                    () -> "expected rejection of: " + invalid);
            assertEquals("MalformedXML", e.getErrorCode());
            assertEquals(400, e.getHttpStatus());
        }
    }

    @Test
    void rejectsExportsOutsideTheSchema() {
        String unknownVersion = EXPORT.replace("V_1", "V_2");
        String unknownFormat = EXPORT.replace("CSV", "ORC");
        String missingBucket = EXPORT.replace("<Bucket>arn:aws:s3:::destination-bucket</Bucket>", "");
        String missingVersion = EXPORT.replace("<OutputSchemaVersion>V_1</OutputSchemaVersion>", "");
        String strayElement = EXPORT.replace("<Format>CSV</Format>", "<Format>CSV</Format><Region>eu</Region>");

        for (String invalid : new String[]{
                unknownVersion, unknownFormat, missingBucket, missingVersion, strayElement}) {
            AwsException e = assertThrows(AwsException.class,
                    () -> S3AnalyticsConfiguration.parse(body("<Id>a</Id>" + invalid)),
                    () -> "expected rejection of: " + invalid);
            assertEquals("MalformedXML", e.getErrorCode());
        }
    }
}
