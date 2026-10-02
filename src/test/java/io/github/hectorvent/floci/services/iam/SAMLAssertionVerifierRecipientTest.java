package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.services.iam.model.SAMLProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bearer assertion names the console sign-on URL of the provider's partition. Where the CDK
 * publishes none (ISO-E, ISO-F, EUSC) the commercial URL is kept, as before the per-partition
 * table, instead of refusing every assertion.
 */
class SAMLAssertionVerifierRecipientTest {

    @ParameterizedTest
    @CsvSource({
            "aws,        https://signin.aws.amazon.com/saml",
            "aws-cn,     https://signin.amazonaws.cn/saml",
            "aws-iso,    https://signin.c2shome.ic.gov/saml",
            "aws-iso-e,  https://signin.aws.amazon.com/saml",
            "aws-iso-f,  https://signin.aws.amazon.com/saml",
            "aws-eusc,   https://signin.aws.amazon.com/saml"
    })
    void theRecipientIsThePartitionsSignOnUrlOrTheCommercialOne(String partition, String expected) {
        SAMLProvider provider = new SAMLProvider();
        provider.setArn("arn:" + partition + ":iam::000000000000:saml-provider/idp");

        assertEquals(expected, SAMLAssertionVerifier.expectedRecipient(provider));
    }
}
