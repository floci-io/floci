package com.floci.test;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.util.io.pem.PemReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.KeyPairInfo;
import software.amazon.awssdk.services.ec2.model.KeyType;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.io.StringReader;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation AWS::EC2::KeyPair")
class CloudFormationEc2KeyPairTest {

    private static CloudFormationClient cfn;
    private static Ec2Client ec2;
    private static SsmClient ssm;
    private String stackName;
    private boolean stackCreated;

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        ec2 = TestFixtures.ec2Client();
        ssm = TestFixtures.ssmClient();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-key-pair");
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        deleteStack();
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        ec2.close();
        ssm.close();
    }

    @Test
    @DisplayName("An RSA key keeps its PEM private key in /ec2/keypair/<KeyPairId>, fingerprinted as AWS fingerprints a created RSA key")
    void rsaKeyKeepsItsPrivateKeyInParameterStore() throws Exception {
        String name = stackName + "-rsa";
        createStack(name, null);

        KeyPairInfo key = assertIdentity(name, KeyType.RSA);
        String privateKey = privateKeyParameter(key.keyPairId());
        // Checked without printing it: it is a private key.
        assertThat(privateKey.startsWith("-----BEGIN RSA PRIVATE KEY-----\n")).as("a PEM RSA private key").isTrue();
        PEMKeyPair pem;
        try (PEMParser parser = new PEMParser(new StringReader(privateKey))) {
            pem = (PEMKeyPair) parser.readObject();
        }
        // "the SHA-1 digest of the DER encoded private key", in PKCS#8 as AWS digests it
        String fingerprint = HexFormat.ofDelimiter(":").formatHex(
                MessageDigest.getInstance("SHA-1").digest(pem.getPrivateKeyInfo().getEncoded()));
        assertThat(key.keyFingerprint()).as("the parameter holds this key pair's own private key")
                .isEqualTo(fingerprint);

        deleteStack();
        assertGone(name, key.keyPairId());
    }

    @Test
    @DisplayName("An ED25519 key keeps its OpenSSH private key in /ec2/keypair/<KeyPairId>, fingerprinted with SHA-256")
    void ed25519KeyKeepsItsPrivateKeyInParameterStore() throws Exception {
        String name = stackName + "-ed25519";
        createStack(name, "ed25519");

        KeyPairInfo key = assertIdentity(name, KeyType.ED25519);
        String privateKey = privateKeyParameter(key.keyPairId());
        // Checked without printing it: it is a private key.
        assertThat(privateKey.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n")).as("an OpenSSH private key").isTrue();
        Ed25519PrivateKeyParameters parsed;
        try (PemReader reader = new PemReader(new StringReader(privateKey))) {
            parsed = (Ed25519PrivateKeyParameters) OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(
                    reader.readPemObject().getContent());
        }
        byte[] publicBlob = OpenSSHPublicKeyUtil.encodePublicKey(parsed.generatePublicKey());
        assertThat(key.keyFingerprint()).as("the parameter holds this key pair's own private key")
                .isEqualTo(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicBlob)));

        deleteStack();
        assertGone(name, key.keyPairId());
    }

    /** Ref is the key name, and the GetAtt attributes are the key DescribeKeyPairs describes. */
    private KeyPairInfo assertIdentity(String name, KeyType keyType) {
        KeyPairInfo key = ec2.describeKeyPairs(r -> r.keyNames(name)).keyPairs().get(0);
        assertThat(key.keyType()).isEqualTo(keyType);
        assertThat(outputs()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Name", name, "Id", key.keyPairId(), "Fingerprint", key.keyFingerprint()));
        return key;
    }

    private String privateKeyParameter(String keyPairId) {
        Parameter parameter = ssm.getParameter(r -> r.name("/ec2/keypair/" + keyPairId).withDecryption(true))
                .parameter();
        assertThat(parameter.type()).isEqualTo(ParameterType.SECURE_STRING);
        return parameter.value();
    }

    private static void assertGone(String name, String keyPairId) {
        assertThatThrownBy(() -> ec2.describeKeyPairs(r -> r.keyNames(name)))
                .isInstanceOfSatisfying(Ec2Exception.class, e ->
                        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidKeyPair.NotFound"));
        assertThatThrownBy(() -> ssm.getParameter(r -> r.name("/ec2/keypair/" + keyPairId)))
                .isInstanceOf(ParameterNotFoundException.class);
    }

    private void createStack(String keyName, String keyType) throws InterruptedException {
        String template = """
                {
                  "Resources": {
                    "Key": {
                      "Type": "AWS::EC2::KeyPair",
                      "Properties": {"KeyName": "%s"%s}
                    }
                  },
                  "Outputs": {
                    "Name": {"Value": {"Ref": "Key"}},
                    "Id": {"Value": {"Fn::GetAtt": ["Key", "KeyPairId"]}},
                    "Fingerprint": {"Value": {"Fn::GetAtt": ["Key", "KeyFingerprint"]}}
                  }
                }
                """.formatted(keyName, keyType == null ? "" : ", \"KeyType\": \"" + keyType + "\"");
        cfn.createStack(r -> r.stackName(stackName).templateBody(template));
        stackCreated = true;
        TestFixtures.await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (status.endsWith("_FAILED") || status.contains("ROLLBACK")) {
                throw new AssertionError(stackName + " reached " + status + ": " + stack.stackStatusReason());
            }
            return "CREATE_COMPLETE".equals(status);
        }, stackName, "CREATE_COMPLETE", 60_000_000_000L, 100);
    }

    private Map<String, String> outputs() {
        List<Output> outputs = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0).outputs();
        return outputs.stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private void deleteStack() throws InterruptedException {
        if (!stackCreated) {
            return;
        }
        cfn.deleteStack(r -> r.stackName(stackName));
        TestFixtures.await(() -> {
            try {
                List<Stack> stacks = cfn.describeStacks(r -> r.stackName(stackName)).stacks();
                if (stacks.isEmpty()) {
                    return true;
                }
                Stack stack = stacks.get(0);
                if ("DELETE_FAILED".equals(stack.stackStatusAsString())) {
                    throw new AssertionError(stackName + " deletion failed: " + stack.stackStatusReason());
                }
                return "DELETE_COMPLETE".equals(stack.stackStatusAsString());
            } catch (CloudFormationException e) {
                if ("ValidationError".equals(e.awsErrorDetails().errorCode())
                        && e.getMessage().contains("does not exist")) {
                    return true;
                }
                throw e;
            }
        }, stackName, "stack deletion", 60_000_000_000L, 100);
        stackCreated = false;
    }
}
