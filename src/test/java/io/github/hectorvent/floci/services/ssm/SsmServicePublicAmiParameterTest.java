package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.Ec2ImageCatalog;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS seeds AMI id lookup parameters under {@code /aws/service/} in every account with no
 * setup, and Terraform modules read them unconditionally, so a read of one of the documented
 * names must resolve here too.
 */
class SsmServicePublicAmiParameterTest {

    private static final String REGION = "eu-west-1";
    private static final String AL2023_DEFAULT =
            "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64";
    private static final String AL2023_ARM64_DEFAULT =
            "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64";
    private static final String AL2023_ARM64_MINIMAL =
            "/aws/service/ami-amazon-linux-latest/al2023-ami-minimal-kernel-default-arm64";
    private static final String AL2023_ARM64_KERNEL_6_1 =
            "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-arm64";
    private static final String AMZN2_DEFAULT =
            "/aws/service/ami-amazon-linux-latest/amzn2-ami-hvm-x86_64-gp2";

    private SsmService ssmService;

    @BeforeEach
    void setUp() {
        ssmService = new SsmService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                5, new RegionResolver(REGION, "000000000000"), new Ec2ImageCatalog());
    }

    @Test
    void resolvesTheDocumentedAl2023DefaultFromTheImageCatalog() {
        Parameter param = ssmService.getParameter(AL2023_DEFAULT, REGION);

        assertEquals("ami-0abcdef1234567891", param.getValue());
        assertEquals("String", param.getType());
        assertEquals(1, param.getVersion());
        assertEquals("arn:aws:ssm:eu-west-1::parameter" + AL2023_DEFAULT, param.getArn());
        assertEquals(Instant.parse("2023-03-15T00:00:00.000Z"), param.getLastModifiedDate());
    }

    @Test
    void resolvesTheDocumentedAl2023Arm64DefaultFromTheImageCatalog() {
        Parameter param = ssmService.getParameter(AL2023_ARM64_DEFAULT, REGION);

        assertEquals("ami-amazonlinux2023-arm64", param.getValue());
        assertEquals("String", param.getType());
        assertEquals(1, param.getVersion());
        assertEquals("arn:aws:ssm:eu-west-1::parameter" + AL2023_ARM64_DEFAULT, param.getArn());
        assertEquals(Instant.parse("2023-03-15T00:00:00.000Z"), param.getLastModifiedDate());
    }

    @Test
    void resolvesAllDocumentedAl2023Arm64VariantsFromTheImageCatalog() {
        for (String paramName : List.of(AL2023_ARM64_DEFAULT, AL2023_ARM64_MINIMAL, AL2023_ARM64_KERNEL_6_1)) {
            Parameter param = ssmService.getParameter(paramName, REGION);
            assertEquals("ami-amazonlinux2023-arm64", param.getValue(), paramName);
        }
    }

    @Test
    void resolvesTheDocumentedAmzn2DefaultFromTheImageCatalog() {
        assertEquals("ami-0abcdef1234567890", ssmService.getParameter(AMZN2_DEFAULT, REGION).getValue());
    }

    @Test
    void getParametersAnswersPublicNamesAlongsideStoredOnes() {
        ssmService.putParameter("/app/ami", "ami-custom", "String", null, false, REGION);

        List<Parameter> params = ssmService.getParameters(
                List.of("/app/ami", AL2023_DEFAULT, "/app/missing"), REGION);

        assertEquals(List.of("/app/ami", AL2023_DEFAULT), params.stream().map(Parameter::getName).toList());
    }

    @Test
    void getParametersByPathListsThePublicFamily() {
        List<Parameter> params = ssmService.getParametersByPath(
                "/aws/service/ami-amazon-linux-latest", false, REGION);

        List<String> names = params.stream().map(Parameter::getName).toList();
        assertTrue(names.contains(AL2023_DEFAULT), names.toString());
        assertTrue(names.contains(AL2023_ARM64_DEFAULT), names.toString());
        assertTrue(names.contains(AMZN2_DEFAULT), names.toString());
        assertEquals(9, names.size());
        assertTrue(ssmService.getParametersByPath("/aws/service", false, REGION).isEmpty());
        assertEquals(32, ssmService.getParametersByPath("/aws/service", true, REGION).size());
    }

    @Test
    void resolvesEksOptimizedAmiParametersAcrossArchitecturesAndVersions() {
        String x86Param = "/aws/service/eks/optimized-ami/1.31/amazon-linux-2023/x86_64/standard/recommended/image_id";
        Parameter x86 = ssmService.getParameter(x86Param, REGION);
        assertEquals("ami-0abcdef1234567891", x86.getValue());
        assertEquals("String", x86.getType());
        assertEquals(1, x86.getVersion());
        assertEquals("arn:aws:ssm:eu-west-1::parameter" + x86Param, x86.getArn());

        String arm64Param = "/aws/service/eks/optimized-ami/1.31/amazon-linux-2023/arm64/standard/recommended/image_id";
        Parameter arm64 = ssmService.getParameter(arm64Param, REGION);
        assertEquals("ami-amazonlinux2023-arm64", arm64.getValue());
        assertEquals("String", arm64.getType());
        assertEquals(1, arm64.getVersion());
        assertEquals("arn:aws:ssm:eu-west-1::parameter" + arm64Param, arm64.getArn());

        String al2Param132 = "/aws/service/eks/optimized-ami/1.32/amazon-linux-2/recommended/image_id";
        Parameter al2 = ssmService.getParameter(al2Param132, REGION);
        assertEquals("ami-0abcdef1234567890", al2.getValue());
        assertEquals("String", al2.getType());
        assertEquals(1, al2.getVersion());
        assertEquals("arn:aws:ssm:eu-west-1::parameter" + al2Param132, al2.getArn());

        String al2023Param136 = "/aws/service/eks/optimized-ami/1.36/amazon-linux-2023/x86_64/standard/recommended/image_id";
        Parameter al2023 = ssmService.getParameter(al2023Param136, REGION);
        assertEquals("ami-0abcdef1234567891", al2023.getValue());
        assertEquals("String", al2023.getType());
        assertEquals(1, al2023.getVersion());
        assertEquals("arn:aws:ssm:eu-west-1::parameter" + al2023Param136, al2023.getArn());
    }

    @Test
    void unseededEksOptimizedAmiVersionThrowsParameterNotFound() {
        String unseededVersion = "/aws/service/eks/optimized-ami/1.27/amazon-linux-2023/x86_64/standard/recommended/image_id";
        AwsException ex = assertThrows(AwsException.class, () -> ssmService.getParameter(unseededVersion, REGION));
        assertEquals("ParameterNotFound", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());

        String al2Deprecated133 = "/aws/service/eks/optimized-ami/1.33/amazon-linux-2/recommended/image_id";
        AwsException exAl2_133 = assertThrows(AwsException.class, () -> ssmService.getParameter(al2Deprecated133, REGION));
        assertEquals("ParameterNotFound", exAl2_133.getErrorCode());
        assertEquals(400, exAl2_133.getHttpStatus());

        String al2Deprecated136 = "/aws/service/eks/optimized-ami/1.36/amazon-linux-2/recommended/image_id";
        AwsException exAl2_136 = assertThrows(AwsException.class, () -> ssmService.getParameter(al2Deprecated136, REGION));
        assertEquals("ParameterNotFound", exAl2_136.getErrorCode());
        assertEquals(400, exAl2_136.getHttpStatus());

        String unseededFutureVersion = "/aws/service/eks/optimized-ami/1.37/amazon-linux-2023/x86_64/standard/recommended/image_id";
        AwsException exFuture = assertThrows(AwsException.class, () -> ssmService.getParameter(unseededFutureVersion, REGION));
        assertEquals("ParameterNotFound", exFuture.getErrorCode());
        assertEquals(400, exFuture.getHttpStatus());

        String unseededArch = "/aws/service/eks/optimized-ami/1.31/amazon-linux-2023/s390x/standard/recommended/image_id";
        AwsException ex2 = assertThrows(AwsException.class, () -> ssmService.getParameter(unseededArch, REGION));
        assertEquals("ParameterNotFound", ex2.getErrorCode());
        assertEquals(400, ex2.getHttpStatus());
    }

    @Test
    void ancestorQueriesFollowTheSamePathRules() {
        List<String> recursive = ssmService.getParametersByPath("/aws", true, REGION)
                .stream().map(Parameter::getName).toList();

        assertTrue(recursive.contains(AL2023_DEFAULT), recursive.toString());
        assertTrue(ssmService.getParametersByPath("/aws", false, REGION).isEmpty());
        assertTrue(ssmService.getParametersByPath("/aws/service", false, REGION).isEmpty());
        assertTrue(ssmService.getParametersByPath("/aws/service", true, REGION)
                .stream().map(Parameter::getName).toList().contains(AMZN2_DEFAULT));
    }

    @Test
    void putParameterRejectsTheReservedPrefixesSoPublicNamesCannotBeShadowed() {
        for (String name : List.of(AL2023_DEFAULT, "/aws/custom", "aws/custom", "/AWS/custom",
                "/ssm/custom", "SSM/x", "/aws", "ssm")) {
            AwsException ex = assertThrows(AwsException.class, () ->
                    ssmService.putParameter(name, "ami-mine", "String", null, true, REGION), name);
            assertEquals("ValidationException", ex.getErrorCode(), name);
            assertEquals(400, ex.getHttpStatus(), name);
        }
        assertEquals("ami-0abcdef1234567891", ssmService.getParameter(AL2023_DEFAULT, REGION).getValue());
        // Only the aws and ssm path segments are reserved. CloudFormation names an
        // AWS::SSM::Parameter after its stack, so a stack called ssm-auto-stack must still work.
        for (String name : List.of("/awesome/param", "awsfoo", "ssm-thing", "ssm-auto-stack-Param-ABC")) {
            assertEquals(1, ssmService.putParameter(name, "ok", "String", null, false, REGION), name);
        }
    }

    @Test
    void publicParametersAreNotTheAccountsOwn() {
        ssmService.getParameter(AL2023_DEFAULT, REGION);

        assertTrue(ssmService.describeParameters(REGION).isEmpty());
        assertThrows(AwsException.class, () -> ssmService.getParameterHistory(AL2023_DEFAULT, REGION));
    }

    @Test
    void anUnknownNameUnderTheSamePrefixStillFails() {
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

    @Test
    void withoutACatalogNothingIsSeeded() {
        SsmService bare = new SsmService(new InMemoryStorage<>(), new InMemoryStorage<>(), 5);

        assertThrows(AwsException.class, () -> bare.getParameter(AL2023_DEFAULT, REGION));
    }
}
