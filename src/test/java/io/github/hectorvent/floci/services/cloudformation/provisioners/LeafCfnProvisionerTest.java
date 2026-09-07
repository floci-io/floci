package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ecr.EcrService;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single-service CloudFormation provisioners extracted from
 * {@code CloudFormationResourceProvisioner} together, since each is a handful of lines with one
 * service call.
 *
 * <p>Every case asserts the exact physical id and the exact {@code Fn::GetAtt} attribute keys.
 * Asserting status alone would prove nothing: an unmapped type still reports CREATE_COMPLETE via
 * the dispatcher's stub arm, with a synthetic id and a fake {@code Arn}.
 */
class LeafCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";

    private CloudFormationTemplateEngine engine;
    private ProvisionContext ctx;

    @BeforeEach
    void setUp() {
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        ctx = new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static StackResource resource(String logicalId, String type) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        return r;
    }

    @Nested
    class Kms {

        private final KmsService kms = mock(KmsService.class);
        private final KmsCfnProvisioner provisioner = new KmsCfnProvisioner(kms);

        @Test
        void keyRefIsTheKeyIdAndGetAttExposesArnAndKeyId() {
            KmsKey key = new KmsKey();
            key.setKeyId("k-123");
            key.setArn("arn:aws:kms:us-east-1:000000000000:key/k-123");
            when(kms.createKey(eq("a key"), eq(null), any(), eq(REGION))).thenReturn(key);

            StackResource r = resource("Key", "AWS::KMS::Key");
            provisioner.provision(r, props("""
                    {"Description": "a key", "Tags": [{"Key": "env", "Value": "dev"}]}
                    """), ctx);

            assertEquals("k-123", r.getPhysicalId(), "Ref is the key id (schema primaryIdentifier)");
            // aws-kms-key.json readOnlyProperties
            assertEquals(Map.of("Arn", "arn:aws:kms:us-east-1:000000000000:key/k-123", "KeyId", "k-123"),
                    r.getAttributes());
        }

        @Test
        void keyTagsReachTheService() {
            KmsKey key = new KmsKey();
            key.setKeyId("k-1");
            ArgumentCaptor<Map<String, String>> tags = ArgumentCaptor.forClass(Map.class);
            when(kms.createKey(any(), any(), tags.capture(), anyString())).thenReturn(key);

            provisioner.provision(resource("Key", "AWS::KMS::Key"), props("""
                    {"Tags": [{"Key": "team", "Value": "core"}]}
                    """), ctx);

            assertEquals(Map.of("team", "core"), tags.getValue());
        }

        @Test
        void aliasRefIsTheAliasName() {
            StackResource r = resource("Alias", "AWS::KMS::Alias");
            provisioner.provision(r, props("""
                    {"AliasName": "alias/app", "TargetKeyId": "k-123"}
                    """), ctx);

            verify(kms).createAlias("alias/app", "k-123", REGION);
            assertEquals("alias/app", r.getPhysicalId());
        }

        @Test
        void aliasWithoutATargetIsNotCreatedButStillGetsAPhysicalId() {
            StackResource r = resource("Alias", "AWS::KMS::Alias");
            provisioner.provision(r, props("""
                    {"AliasName": "alias/app"}
                    """), ctx);

            verify(kms, never()).createAlias(anyString(), anyString(), anyString());
            assertEquals("alias/app", r.getPhysicalId());
        }

        @Test
        void deletingAKeyIsANoOpBecauseKmsOnlySchedulesDeletion() {
            provisioner.delete("AWS::KMS::Key", "k-123", REGION);
            verify(kms, never()).deleteAlias(anyString(), anyString());
        }

        @Test
        void deletingAnAliasReachesTheService() {
            provisioner.delete("AWS::KMS::Alias", "alias/app", REGION);
            verify(kms).deleteAlias("alias/app", REGION);
        }
    }

    @Nested
    class Ssm {

        private final SsmService ssm = mock(SsmService.class);
        private final SsmCfnProvisioner provisioner = new SsmCfnProvisioner(ssm);

        @Test
        void refIsTheParameterNameAndAttributesEchoTheStoredValue() {
            StackResource r = resource("Param", "AWS::SSM::Parameter");
            provisioner.provision(r, props("""
                    {"Name": "/app/db", "Value": "secret", "Type": "String"}
                    """), ctx);

            verify(ssm).putParameter("/app/db", "secret", "String", null, true, REGION);
            assertEquals("/app/db", r.getPhysicalId());
            assertEquals(Map.of("Name", "/app/db", "Type", "String", "Value", "secret",
                    "Arn", "arn:aws:ssm:us-east-1:000000000000:parameter/app/db"), r.getAttributes());
        }

        @Test
        void arnOfANameWithoutALeadingSlashStillHasTheParameterPrefix() {
            // AWS ARNs are always arn:...:parameter/<name>; a name without a leading slash gets the
            // slash inserted, a name with one is not doubled.
            StackResource r = resource("Param", "AWS::SSM::Parameter");
            provisioner.provision(r, props("""
                    {"Name": "db-host", "Value": "localhost"}
                    """), ctx);

            assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/db-host", r.getAttributes().get("Arn"));
        }

        @Test
        void anUnnamedParameterGetsAGeneratedStackScopedName() {
            StackResource r = resource("Param", "AWS::SSM::Parameter");
            provisioner.provision(r, props("{}"), ctx);

            assertTrue(r.getPhysicalId().startsWith("my-stack-Param-"),
                    "generated names stay stack-scoped: " + r.getPhysicalId());
            // Absent Value and Type fall back rather than reaching the service as null.
            verify(ssm).putParameter(anyString(), eq(""), eq("String"), eq(null), eq(true), eq(REGION));
        }

        @Test
        void deleteReachesTheService() {
            provisioner.delete("AWS::SSM::Parameter", "/app/db", REGION);
            verify(ssm).deleteParameter("/app/db", REGION);
        }
    }

    @Nested
    class Ecr {

        private final EcrService ecr = mock(EcrService.class);
        private final EcrCfnProvisioner provisioner = new EcrCfnProvisioner(ecr);

        private Repository repo(String arn, String uri) {
            Repository repository = new Repository();
            repository.setRepositoryArn(arn);
            repository.setRepositoryUri(uri);
            return repository;
        }

        @Test
        void refIsTheRepositoryNameAndGetAttExposesArnAndUri() {
            when(ecr.createRepository(eq("app"), any(), any(), any(), any(), any(), any(), eq(REGION)))
                    .thenReturn(repo("arn:aws:ecr:us-east-1:000000000000:repository/app", "localhost/app"));

            StackResource r = resource("Repo", "AWS::ECR::Repository");
            provisioner.provision(r, props("""
                    {"RepositoryName": "app"}
                    """), ctx);

            assertEquals("app", r.getPhysicalId());
            // aws-ecr-repository.json readOnlyProperties
            assertEquals(Map.of("Arn", "arn:aws:ecr:us-east-1:000000000000:repository/app",
                    "RepositoryUri", "localhost/app"), r.getAttributes());
        }

        @Test
        void repositoryNamesAreLowerCasedForCdkBootstrap() {
            when(ecr.createRepository(anyString(), any(), any(), any(), any(), any(), any(), anyString()))
                    .thenReturn(repo("arn", "uri"));

            StackResource r = resource("Repo", "AWS::ECR::Repository");
            provisioner.provision(r, props("""
                    {"RepositoryName": "MyApp"}
                    """), ctx);

            assertEquals("myapp", r.getPhysicalId());
        }

        @Test
        void aCollidingRepositoryNameFailsTheCreate() {
            when(ecr.createRepository(anyString(), any(), any(), any(), any(), any(), any(), anyString()))
                    .thenThrow(new AwsException("RepositoryAlreadyExistsException", "exists", 400));

            // A first create has no prior physical id, so the existing repository is not this
            // stack's: CloudFormation fails the create rather than adopting it.
            StackResource r = resource("Repo", "AWS::ECR::Repository");
            AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                    {"RepositoryName": "app"}
                    """), ctx));

            assertEquals("RepositoryAlreadyExistsException", failure.getErrorCode());
            verify(ecr, never()).describeRepositories(any(), any(), anyString());
        }

        @Test
        void theStacksOwnRepositoryIsReconciledOnUpdateRatherThanRecreated() {
            when(ecr.putImageTagMutability("app", null, "MUTABLE", REGION))
                    .thenReturn(repo("arn:existing", "uri:existing"));

            // The prior physical id is the same name, which is what a CDK bootstrap re-run of the
            // CDKToolkit stack looks like: reconcile in place, no create.
            StackResource r = resource("Repo", "AWS::ECR::Repository");
            provisioner.provision(r, props("""
                    {"RepositoryName": "app"}
                    """), new ProvisionContext(engine, REGION, "000000000000", "my-stack", "app"));

            assertEquals("arn:existing", r.getAttributes().get("Arn"));
            verify(ecr, never()).createRepository(anyString(), any(), any(), any(), any(), any(), any(), anyString());
        }

        @Test
        void inlinePoliciesAreApplied() {
            when(ecr.createRepository(anyString(), any(), any(), any(), any(), any(), any(), anyString()))
                    .thenReturn(repo("arn", "uri"));

            provisioner.provision(resource("Repo", "AWS::ECR::Repository"), props("""
                    {
                      "RepositoryName": "app",
                      "LifecyclePolicy": {"LifecyclePolicyText": "{\\"rules\\":[]}"},
                      "RepositoryPolicyText": "{\\"Version\\":\\"2012-10-17\\"}"
                    }
                    """), ctx);

            verify(ecr).putLifecyclePolicy("app", null, "{\"rules\":[]}", REGION);
            verify(ecr).setRepositoryPolicy("app", null, "{\"Version\":\"2012-10-17\"}", REGION);
        }

        @Test
        void deleteForcesRemovalSoANonEmptyRepositoryStillGoes() {
            provisioner.delete("AWS::ECR::Repository", "app", REGION);
            verify(ecr).deleteRepository("app", null, true, REGION);
        }
    }

    @Nested
    class Firehose {

        private final FirehoseService firehose = mock(FirehoseService.class);
        private final FirehoseCfnProvisioner provisioner = new FirehoseCfnProvisioner(firehose);

        @Test
        void refIsTheStreamNameAndGetAttExposesArn() {
            when(firehose.createDeliveryStream(eq("s"), any(), any())).thenReturn("arn:stream");

            StackResource r = resource("Stream", "AWS::KinesisFirehose::DeliveryStream");
            provisioner.provision(r, props("""
                    {"DeliveryStreamName": "s"}
                    """), ctx);

            assertEquals("s", r.getPhysicalId());
            assertEquals(Map.of("Arn", "arn:stream"), r.getAttributes());
        }

        @Test
        void bufferingHintsFallBackToTheAwsDefaults() {
            ArgumentCaptor<DeliveryStreamDescription.S3Destination> s3 =
                    ArgumentCaptor.forClass(DeliveryStreamDescription.S3Destination.class);
            when(firehose.createDeliveryStream(anyString(), s3.capture(), any())).thenReturn("arn");

            provisioner.provision(resource("Stream", "AWS::KinesisFirehose::DeliveryStream"), props("""
                    {
                      "DeliveryStreamName": "s",
                      "S3DestinationConfiguration": {
                        "BucketARN": "arn:bucket",
                        "BufferingHints": {"SizeInMBs": "7"}
                      }
                    }
                    """), ctx);

            assertEquals(7, s3.getValue().getBufferingHints().getSizeInMBs());
            assertEquals(300, s3.getValue().getBufferingHints().getIntervalInSeconds(),
                    "an omitted interval falls back to the AWS default, not zero");
        }

        @Test
        void blankDestinationFieldsBecomeNullRatherThanEmptyStrings() {
            ArgumentCaptor<DeliveryStreamDescription.S3Destination> s3 =
                    ArgumentCaptor.forClass(DeliveryStreamDescription.S3Destination.class);
            when(firehose.createDeliveryStream(anyString(), s3.capture(), any())).thenReturn("arn");

            provisioner.provision(resource("Stream", "AWS::KinesisFirehose::DeliveryStream"), props("""
                    {"DeliveryStreamName": "s", "S3DestinationConfiguration": {"Prefix": ""}}
                    """), ctx);

            assertNull(s3.getValue().getPrefix());
        }

        @Test
        void deleteReachesTheService() {
            provisioner.delete("AWS::KinesisFirehose::DeliveryStream", "s", REGION);
            verify(firehose).deleteDeliveryStream("s");
        }
    }

    @Nested
    class CdkMetadata {

        private final CdkMetadataCfnProvisioner provisioner = new CdkMetadataCfnProvisioner();

        @Test
        void getsAPhysicalIdAndNoAttributes() {
            StackResource r = resource("CDKMetadata", "AWS::CDK::Metadata");
            provisioner.provision(r, props("{}"), ctx);

            assertTrue(r.getPhysicalId().startsWith("cdk-metadata-"), r.getPhysicalId());
            // The stub arm would have added a fake Arn here; the real type has no attributes.
            assertTrue(r.getAttributes().isEmpty(), "CDK::Metadata exposes no Fn::GetAtt attributes");
        }
    }
}
