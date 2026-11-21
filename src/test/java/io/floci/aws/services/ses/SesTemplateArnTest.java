package io.floci.aws.services.ses;

import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The template ARN parser and the name/ARN alignment check that the v1 and v2 sends share.
 */
class SesTemplateArnTest {

    private static final String REGION = "us-west-2";

    @Test
    void templateNameFromArn_returnsTheRelativeIdAfterTemplate() {
        assertEquals("welcome",
                SesTemplateService.templateNameFromArn("arn:aws:ses:us-west-2:000000000000:template/welcome", REGION));
    }

    @Test
    void templateNameFromArn_ignoresTheAccount() {
        assertEquals("welcome",
                SesTemplateService.templateNameFromArn("arn:aws:ses:us-west-2:999999999999:template/welcome", REGION));
    }

    @Test
    void templateNameFromArn_expectsThePartitionOfTheRegion() {
        assertEquals("welcome", SesTemplateService.templateNameFromArn(
                "arn:aws-cn:ses:cn-north-1:000000000000:template/welcome", "cn-north-1"));
        assertInvalid("arn:aws:ses:cn-north-1:000000000000:template/welcome", "cn-north-1",
                "Invalid template ARN <arn:aws:ses:cn-north-1:000000000000:template/welcome>. "
                        + "Partition should be <aws-cn>");
    }

    static Stream<Arguments> invalidArns() {
        return Stream.of(
                Arguments.of("arn:aws-cn:ses:us-west-2:000000000000:template/welcome",
                        "Invalid template ARN <arn:aws-cn:ses:us-west-2:000000000000:template/welcome>. "
                                + "Partition should be <aws>"),
                Arguments.of("arn:aws:s3:us-west-2:000000000000:template/welcome",
                        "Invalid template ARN <arn:aws:s3:us-west-2:000000000000:template/welcome>. "
                                + "Vendor should be <ses>"),
                Arguments.of("arn:aws:ses:us-east-1:000000000000:template/welcome",
                        "Invalid template ARN <arn:aws:ses:us-east-1:000000000000:template/welcome>. "
                                + "Template must be in region <us-west-2>"),
                Arguments.of("arn:aws:ses:us-west-2:000000000000:configuration-set/welcome",
                        "Invalid template ARN <arn:aws:ses:us-west-2:000000000000:configuration-set/welcome>. "
                                + "Relative ID should start with <template/>"),
                Arguments.of("arn:aws:ses:us-west-2:000000000000:template/", "The template name must be specified."),
                Arguments.of("not-an-arn", "ARNs must start with 'arn:': not-an-arn"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidArns")
    void templateNameFromArn_rejectsWithTheProbedMessage(String arn, String message) {
        assertInvalid(arn, REGION, message);
    }

    @Test
    void requireNameAlignsWithArn_acceptsTheNameTheArnNames() {
        assertDoesNotThrow(() -> SesTemplateService.requireNameAlignsWithArn("welcome",
                "arn:aws:ses:us-west-2:000000000000:template/welcome", REGION));
    }

    @Test
    void requireNameAlignsWithArn_rejectsAnotherName() {
        String arn = "arn:aws:ses:us-west-2:000000000000:template/other";
        AwsException e = assertThrows(AwsException.class,
                () -> SesTemplateService.requireNameAlignsWithArn("welcome", arn, REGION));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals("The SES resource 'welcome' does not align with the given ARN '" + arn + "'.", e.getMessage());
    }

    @Test
    void requireNameAlignsWithArn_reportsAMalformedArnFirst() {
        AwsException e = assertThrows(AwsException.class,
                () -> SesTemplateService.requireNameAlignsWithArn("welcome", "not-an-arn", REGION));
        assertEquals("ARNs must start with 'arn:': not-an-arn", e.getMessage());
    }

    private static void assertInvalid(String arn, String region, String message) {
        AwsException e = assertThrows(AwsException.class, () -> SesTemplateService.templateNameFromArn(arn, region));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals(message, e.getMessage());
    }
}
