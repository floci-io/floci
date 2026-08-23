package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.Ec2ImageCatalog;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AWS itself seeds AMI-id lookup parameters under "/aws/service/" in every
 * real account with no setup - see
 * https://docs.aws.amazon.com/systems-manager/latest/userguide/parameter-store-public-parameters-ami.html
 * floci did not, so terraform-aws-modules/terraform-aws-ec2-instance's
 * "complete" example (its unconditional
 * data "aws_ssm_parameter" "this" { name = var.ami_ssm_parameter }, read even
 * when the caller passes an explicit "ami") failed cold deploy with
 * ParameterNotFound on a real floci run - not an upstream module bug, the
 * module reads a parameter AWS itself guarantees is there.
 */
class SsmServicePublicAmiParameterTest {

    private SsmService ssmService;
    private static final String REGION = "eu-west-1";

    @BeforeEach
    void setUp() {
        ssmService = new SsmService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                5,
                new RegionResolver("eu-west-1", "000000000000"),
                new Ec2ImageCatalog());
    }

    @Test
    void resolvesTheDocumentedAl2023DefaultFromTheImageCatalog() {
        Parameter param = ssmService.getParameter(
                "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64", REGION);
        assertEquals("ami-0abcdef1234567891", param.getValue());
        assertEquals("String", param.getType());
    }

    @Test
    void resolvesTheDocumentedAmzn2DefaultFromTheImageCatalog() {
        Parameter param = ssmService.getParameter(
                "/aws/service/ami-amazon-linux-latest/amzn2-ami-hvm-x86_64-gp2", REGION);
        assertEquals("ami-0abcdef1234567890", param.getValue());
    }

    @Test
    void isPersistedAfterTheFirstLookupLikeARealPutParameter() {
        ssmService.getParameter("/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64", REGION);
        Parameter again = ssmService.getParameter(
                "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64", REGION);
        assertEquals(1, again.getVersion());
        assertEquals("ami-0abcdef1234567891", again.getValue());
    }

    @Test
    void anUnknownNameUnderTheSamePrefixStillFails() {
        // A targeted fallback for the documented public-parameter family the
        // catalog knows, not a blanket catch-all for every "/aws/service/" name.
        AwsException ex = assertThrows(AwsException.class, () -> ssmService.getParameter(
                "/aws/service/ami-amazon-linux-latest/no-such-variant-x86_64", REGION));
        assertEquals("ParameterNotFound", ex.getErrorCode());
    }

    @Test
    void anUnrelatedUnknownNameStillFails() {
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.getParameter("/app/does-not-exist", REGION));
        assertEquals("ParameterNotFound", ex.getErrorCode());
    }
}
