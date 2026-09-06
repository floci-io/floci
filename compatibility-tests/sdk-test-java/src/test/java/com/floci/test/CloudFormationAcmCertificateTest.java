package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.acm.model.CertificateDetail;
import software.amazon.awssdk.services.acm.model.CertificateExport;
import software.amazon.awssdk.services.acm.model.CertificateStatus;
import software.amazon.awssdk.services.acm.model.CertificateTransparencyLoggingPreference;
import software.amazon.awssdk.services.acm.model.ResourceNotFoundException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@code CertificateExport} and {@code CertificateTransparencyLoggingPreference} on an
 * {@code AWS::CertificateManager::Certificate} through the CloudFormation SDK and reads the result
 * back through the ACM SDK: the logging preference changes in place, the export setting replaces
 * the certificate, and the stack delete removes it.
 */
@DisplayName("CloudFormation AWS::CertificateManager::Certificate")
class CloudFormationAcmCertificateTest {

    private static CloudFormationClient cloudFormation;
    private static AcmClient acm;
    private static String stackName;
    private static String domainName;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        acm = TestFixtures.acmClient();
        stackName = TestFixtures.uniqueName("compat-cfn-acm");
        domainName = TestFixtures.uniqueName("cert") + ".example.com";
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            try {
                cloudFormation.deleteStack(r -> r.stackName(stackName));
            } catch (Exception e) {
                System.err.println("CloudFormation ACM cleanup skipped: " + e.getMessage());
            }
            cloudFormation.close();
        }
        if (acm != null) {
            acm.close();
        }
    }

    @Test
    void certificateOptionsFollowTheTemplateThroughCreateAndUpdate() throws InterruptedException {
        cloudFormation.createStack(r -> r
                .stackName(stackName)
                .templateBody(template())
                .parameters(options("ENABLED", "DISABLED")));
        assertThat(waitForTerminal(stackName, 60)).isEqualTo("CREATE_COMPLETE");

        String arn = output("CertArn");
        assertThat(arn).startsWith("arn:aws:acm:us-east-1:");
        CertificateDetail created = describe(arn);
        assertThat(created.domainName()).isEqualTo(domainName);
        assertThat(created.status()).isEqualTo(CertificateStatus.ISSUED);
        assertThat(created.options().export()).isEqualTo(CertificateExport.ENABLED);
        assertThat(created.options().certificateTransparencyLoggingPreference())
                .isEqualTo(CertificateTransparencyLoggingPreference.DISABLED);

        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(template())
                .parameters(options("ENABLED", "ENABLED")));
        assertThat(waitForTerminal(stackName, 60)).isEqualTo("UPDATE_COMPLETE");
        assertThat(output("CertArn"))
                .as("a transparency logging change updates the certificate in place")
                .isEqualTo(arn);
        assertThat(describe(arn).options().certificateTransparencyLoggingPreference())
                .isEqualTo(CertificateTransparencyLoggingPreference.ENABLED);

        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(template())
                .parameters(options("DISABLED", "ENABLED")));
        assertThat(waitForTerminal(stackName, 60)).isEqualTo("UPDATE_COMPLETE");
        String replacement = output("CertArn");
        assertThat(replacement).as("an export change replaces the certificate").isNotEqualTo(arn);
        assertThatThrownBy(() -> describe(arn)).isInstanceOf(ResourceNotFoundException.class);
        CertificateDetail replaced = describe(replacement);
        assertThat(replaced.domainName()).isEqualTo(domainName);
        assertThat(replaced.options().export()).isEqualTo(CertificateExport.DISABLED);
        assertThat(replaced.options().certificateTransparencyLoggingPreference())
                .isEqualTo(CertificateTransparencyLoggingPreference.ENABLED);

        cloudFormation.deleteStack(r -> r.stackName(stackName));
        waitForDeleted(stackName, 60);
        assertThatThrownBy(() -> describe(replacement)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static String template() {
        return """
                {
                  "Parameters": {
                    "Export": {"Type": "String"},
                    "TransparencyLogging": {"Type": "String"}
                  },
                  "Resources": {
                    "Cert": {
                      "Type": "AWS::CertificateManager::Certificate",
                      "Properties": {
                        "DomainName": "%s",
                        "ValidationMethod": "DNS",
                        "CertificateExport": {"Ref": "Export"},
                        "CertificateTransparencyLoggingPreference": {"Ref": "TransparencyLogging"}
                      }
                    }
                  },
                  "Outputs": {
                    "CertArn": {"Value": {"Fn::GetAtt": ["Cert", "CertificateArn"]}}
                  }
                }
                """.formatted(domainName);
    }

    private static List<Parameter> options(String export, String transparencyLogging) {
        return List.of(
                Parameter.builder().parameterKey("Export").parameterValue(export).build(),
                Parameter.builder().parameterKey("TransparencyLogging").parameterValue(transparencyLogging).build());
    }

    private static CertificateDetail describe(String arn) {
        return acm.describeCertificate(r -> r.certificateArn(arn)).certificate();
    }

    private static String output(String key) {
        Stack stack = cloudFormation.describeStacks(
                DescribeStacksRequest.builder().stackName(stackName).build()).stacks().get(0);
        return stack.outputs().stream()
                .filter(o -> key.equals(o.outputKey()))
                .map(Output::outputValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("stack " + stackName + " has no output " + key));
    }

    private static String waitForTerminal(String name, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<Stack> stacks = cloudFormation.describeStacks(
                    DescribeStacksRequest.builder().stackName(name).build()).stacks();
            if (!stacks.isEmpty()) {
                String status = stacks.get(0).stackStatusAsString();
                if (!status.endsWith("_IN_PROGRESS")) {
                    return status;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError(
                "Stack " + name + " did not reach a terminal state within " + maxSeconds + "s");
    }

    private static void waitForDeleted(String name, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<Stack> stacks = cloudFormation.describeStacks(
                        DescribeStacksRequest.builder().stackName(name).build()).stacks();
                if (stacks.isEmpty()
                        || "DELETE_COMPLETE".equals(stacks.get(0).stackStatusAsString())) {
                    return;
                }
            } catch (CloudFormationException e) {
                if (e.getMessage() != null && e.getMessage().contains("does not exist")) {
                    return;
                }
                throw e;
            }
            Thread.sleep(500);
        }
        throw new AssertionError(
                "Stack " + name + " was not deleted within " + maxSeconds + "s");
    }
}
