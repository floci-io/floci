package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3MetricsConfigurationTest {

    private static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";

    private static String body(String inner) {
        return "<MetricsConfiguration xmlns=\"" + NS + "\">" + inner + "</MetricsConfiguration>";
    }

    @Test
    void parsesAnIdWithoutAFilter() {
        S3MetricsConfiguration parsed = S3MetricsConfiguration.parse(body("<Id>EntireBucket</Id>"));

        assertEquals("EntireBucket", parsed.id());
        assertEquals("<Id>EntireBucket</Id>", parsed.innerXml());
    }

    @Test
    void parsesEachSingleFilterPredicate() {
        assertEquals("<Id>a</Id><Filter><Prefix>logs/</Prefix></Filter>",
                S3MetricsConfiguration.parse(body("<Id>a</Id><Filter><Prefix>logs/</Prefix></Filter>")).innerXml());

        assertEquals("<Id>a</Id><Filter><Tag><Key>env</Key><Value>prod</Value></Tag></Filter>",
                S3MetricsConfiguration.parse(body(
                        "<Id>a</Id><Filter><Tag><Key>env</Key><Value>prod</Value></Tag></Filter>")).innerXml());

        String arn = "arn:aws:s3:eu-central-1:123456789012:accesspoint/ap";
        assertEquals("<Id>a</Id><Filter><AccessPointArn>" + arn + "</AccessPointArn></Filter>",
                S3MetricsConfiguration.parse(body(
                        "<Id>a</Id><Filter><AccessPointArn>" + arn + "</AccessPointArn></Filter>")).innerXml());
    }

    @Test
    void parsesAnAndConjunctionKeepingEveryTag() {
        // MetricsAndOperator.Tags is a flattened list named Tag, so the tags repeat with no
        // wrapping element and all of them have to survive the round trip.
        String parsed = S3MetricsConfiguration.parse(body("""
                <Id>a</Id>
                <Filter>
                    <And>
                        <Prefix>logs/</Prefix>
                        <Tag><Key>env</Key><Value>prod</Value></Tag>
                        <Tag><Key>team</Key><Value>core</Value></Tag>
                    </And>
                </Filter>
                """)).innerXml();

        assertEquals("<Id>a</Id><Filter><And><Prefix>logs/</Prefix>"
                + "<Tag><Key>env</Key><Value>prod</Value></Tag>"
                + "<Tag><Key>team</Key><Value>core</Value></Tag></And></Filter>", parsed);
    }

    @Test
    void escapesValuesOnTheWayBackOut() {
        String parsed = S3MetricsConfiguration.parse(
                body("<Id>a&amp;b</Id><Filter><Prefix>x&lt;y</Prefix></Filter>")).innerXml();

        assertEquals("<Id>a&amp;b</Id><Filter><Prefix>x&lt;y</Prefix></Filter>", parsed);
    }

    @Test
    void rejectsBodiesThatDoNotMatchTheSchema() {
        // Missing id, wrong root element, empty and non-XML bodies are all MalformedXML on AWS.
        for (String invalid : new String[]{
                body(""),
                body("<Id>   </Id>"),
                "<SomethingElse><Id>a</Id></SomethingElse>",
                "not xml at all",
                ""}) {
            AwsException e = assertThrows(AwsException.class, () -> S3MetricsConfiguration.parse(invalid),
                    () -> "expected rejection of: " + invalid);
            assertEquals("MalformedXML", e.getErrorCode());
            assertEquals(400, e.getHttpStatus());
        }
    }

    @Test
    void rejectsFiltersThatAreNotExactlyOnePredicate() {
        // Verified against AWS: each of these is MalformedXML rather than something to normalize.
        String bothPrefixAndTag = "<Id>a</Id><Filter><Prefix>logs/</Prefix>"
                + "<Tag><Key>env</Key><Value>prod</Value></Tag></Filter>";
        String emptyFilter = "<Id>a</Id><Filter></Filter>";
        String andWithOnePredicate = "<Id>a</Id><Filter><And><Prefix>logs/</Prefix></And></Filter>";
        String andWithOneTag = "<Id>a</Id><Filter><And>"
                + "<Tag><Key>env</Key><Value>prod</Value></Tag></And></Filter>";
        String tagWithoutAKey = "<Id>a</Id><Filter><Tag><Value>prod</Value></Tag></Filter>";
        // botocore marks both Key and Value required on a Tag.
        String tagWithoutAValue = "<Id>a</Id><Filter><Tag><Key>env</Key></Tag></Filter>";
        String unknownPredicate = "<Id>a</Id><Filter><Something>x</Something></Filter>";
        String andWithAnUnknownConjunct = "<Id>a</Id><Filter><And><Prefix>logs/</Prefix>"
                + "<Something>x</Something></And></Filter>";

        for (String invalid : new String[]{
                bothPrefixAndTag, emptyFilter, andWithOnePredicate, andWithOneTag, tagWithoutAKey,
                tagWithoutAValue, unknownPredicate, andWithAnUnknownConjunct}) {
            AwsException e = assertThrows(AwsException.class, () -> S3MetricsConfiguration.parse(body(invalid)),
                    () -> "expected rejection of: " + invalid);
            assertEquals("MalformedXML", e.getErrorCode());
        }
    }

    @Test
    void rejectsDuplicateOrMisplacedElements() {
        // Normalizing these away would store a configuration that is not the one that was sent.
        String twoIds = "<Id>a</Id><Id>b</Id>";
        String twoFilters = "<Id>a</Id><Filter><Prefix>x</Prefix></Filter>"
                + "<Filter><Prefix>y</Prefix></Filter>";
        String strayElement = "<Id>a</Id><Prefix>logs/</Prefix>";
        String noId = "<Filter><Prefix>logs/</Prefix></Filter>";
        String idOnlyInsideFilter = "<Filter><Id>a</Id></Filter>";

        for (String invalid : new String[]{twoIds, twoFilters, strayElement, noId, idOnlyInsideFilter}) {
            AwsException e = assertThrows(AwsException.class, () -> S3MetricsConfiguration.parse(body(invalid)),
                    () -> "expected rejection of: " + invalid);
            assertEquals("MalformedXML", e.getErrorCode());
        }
    }

    @Test
    void acceptsAFilterBeforeItsId() {
        // Element order is not something to be strict about; the set of elements is.
        assertEquals("<Id>a</Id><Filter><Prefix>logs/</Prefix></Filter>",
                S3MetricsConfiguration.parse(
                        body("<Filter><Prefix>logs/</Prefix></Filter><Id>a</Id>")).innerXml());
    }

    @Test
    void acceptsAnAndOfExactlyTwoPredicates() {
        // The boundary the rejection is drawn at: two conjuncts are legal, one is not.
        assertEquals("<Id>a</Id><Filter><And><Prefix>logs/</Prefix>"
                        + "<Tag><Key>env</Key><Value>prod</Value></Tag></And></Filter>",
                S3MetricsConfiguration.parse(body("<Id>a</Id><Filter><And><Prefix>logs/</Prefix>"
                        + "<Tag><Key>env</Key><Value>prod</Value></Tag></And></Filter>")).innerXml());
    }

    @Test
    void refusesToResolveExternalEntities() {
        // The parser must not read local files on behalf of a request body.
        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<MetricsConfiguration><Id>&xxe;</Id></MetricsConfiguration>";

        AwsException e = assertThrows(AwsException.class, () -> S3MetricsConfiguration.parse(xxe));
        assertEquals("MalformedXML", e.getErrorCode());
    }
}
