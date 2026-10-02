package io.github.hectorvent.floci.services.eks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksClusterArgsTest {

    @Test
    void parsePrefixedKubeletArgWithKeyEqualsValue() {
        Map<String, String> tags = Map.of("floci:kubelet-arg:max-pods=250", "true");
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertEquals(List.of("--kubelet-arg=max-pods=250"), args);
    }

    @Test
    void parsePrefixedKubeletArgWithKeyValue() {
        Map<String, String> tags = Map.of("floci:kubelet-arg:max-pods", "250");
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertEquals(List.of("--kubelet-arg=max-pods=250"), args);
    }

    @Test
    void parseSingularKubeApiserverArg() {
        Map<String, String> tags = Map.of("floci:kube-apiserver-arg", "feature-gates=CSIStorageCapacity=true");
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertEquals(List.of("--kube-apiserver-arg=feature-gates=CSIStorageCapacity=true"), args);
    }

    @Test
    void parsePluralJsonArrayArgs() {
        Map<String, String> tags = Map.of(
                "floci:kubelet-args", "[\"max-pods=250\", \"image-gc-high-threshold-percent=80\"]"
        );
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertEquals(List.of(
                "--kubelet-arg=max-pods=250",
                "--kubelet-arg=image-gc-high-threshold-percent=80"
        ), args);
    }

    @Test
    void parsePluralNewlineSeparatedArgs() {
        Map<String, String> tags = Map.of(
                "floci:kubelet-args", "max-pods=250\nimage-gc-high-threshold-percent=80"
        );
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertEquals(List.of(
                "--kubelet-arg=max-pods=250",
                "--kubelet-arg=image-gc-high-threshold-percent=80"
        ), args);
    }

    @Test
    void stripsRedundantPrefixesAndDashes() {
        Map<String, String> tags = Map.of(
                "floci:kubelet-arg:--max-pods=250", "true",
                "floci:kube-apiserver-arg", "--kube-apiserver-arg=feature-gates=Test=true"
        );
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertTrue(args.contains("--kubelet-arg=max-pods=250"));
        assertTrue(args.contains("--kube-apiserver-arg=feature-gates=Test=true"));
    }

    @Test
    void supportsAllK3sComponents() {
        Map<String, String> tags = Map.of(
                "floci:kubelet-arg:max-pods=250", "true",
                "floci:kube-apiserver-arg:runtime-config=batch/v1=true", "true",
                "floci:kube-controller-manager-arg:leader-elect=false", "true",
                "floci:kube-scheduler-arg:leader-elect=false", "true",
                "floci:kube-cloud-controller-manager-arg:cloud-provider=aws", "true"
        );
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertTrue(args.contains("--kubelet-arg=max-pods=250"));
        assertTrue(args.contains("--kube-apiserver-arg=runtime-config=batch/v1=true"));
        assertTrue(args.contains("--kube-controller-manager-arg=leader-elect=false"));
        assertTrue(args.contains("--kube-scheduler-arg=leader-elect=false"));
        assertTrue(args.contains("--kube-cloud-controller-manager-arg=cloud-provider=aws"));
    }

    @Test
    void supportsShortComponentAliases() {
        Map<String, String> tags = Map.of(
                "floci:apiserver-arg:runtime-config=batch/v1=true", "true",
                "floci:controller-manager-arg:leader-elect=false", "true",
                "floci:scheduler-arg:leader-elect=false", "true"
        );
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertTrue(args.contains("--kube-apiserver-arg=runtime-config=batch/v1=true"));
        assertTrue(args.contains("--kube-controller-manager-arg=leader-elect=false"));
        assertTrue(args.contains("--kube-scheduler-arg=leader-elect=false"));
    }

    @Test
    void preservesModifierSuffixOnValidArguments() {
        Map<String, String> tags = Map.of(
                "floci:kubelet-arg:max-pods+=10", "true",
                "floci:kube-apiserver-arg:feature-gates+=CSIStorageCapacity=true", "true"
        );
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertTrue(args.contains("--kubelet-arg=max-pods+=10"));
        assertTrue(args.contains("--kube-apiserver-arg=feature-gates+=CSIStorageCapacity=true"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "provider-id",
            "node-labels",
            "system-reserved",
            "kube-reserved",
            "eviction-hard",
            "--provider-id=custom-id",
            "providerId=custom-id",
            "node-labels=topology.kubernetes.io/zone=custom",
            "provider-id+=aws:///custom-id",
            "node-labels-=zone=custom"
    })
    void rejectsCollidingKubeletArguments(String collidingFlag) {
        Map<String, String> tags = Map.of("floci:kubelet-arg:" + collidingFlag, "true");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EksClusterArgs.parseAndValidateClusterArgs(tags, "demo"));
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("collides with Floci-managed kubelet argument"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "authentication-token-webhook-config-file",
            "authentication-token-webhook-version",
            "authentication-token-webhook-cache-ttl",
            "service-account-issuer",
            "service-account-key-file",
            "service-account-signing-key-file",
            "audit-policy-file",
            "audit-log-path",
            "audit-log-maxage",
            "audit-log-maxbackup",
            "audit-log-maxsize",
            "--service-account-issuer=https://override.example.com",
            "auditPolicyFile=/custom/path",
            "audit-policy-file+=/custom/path",
            "audit-policy-file-=/custom/path"
    })
    void rejectsCollidingKubeApiserverArguments(String collidingFlag) {
        Map<String, String> tags = Map.of("floci:kube-apiserver-arg:" + collidingFlag, "true");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EksClusterArgs.parseAndValidateClusterArgs(tags, "demo"));
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("collides with Floci-managed kube-apiserver argument"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "storage-backend",
            "etcd-servers",
            "authorization-mode",
            "authorization-mode=AlwaysAllow",
            "authorization-mode+=AlwaysAllow",
            "authorization-mode-=AlwaysAllow",
            "authorization-mode+",
            "storage-backend=etcd3",
            "storage-backend+=etcd3",
            "etcd-servers=https://127.0.0.1:2379"
    })
    void rejectsRefusedApiserverArguments(String refusedFlag) {
        Map<String, String> tags = Map.of("floci:kube-apiserver-arg:" + refusedFlag, "true");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EksClusterArgs.parseAndValidateClusterArgs(tags, "demo"));
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("is refused outright"));
    }

    @Test
    void malformedInputLogsWarningAndContinuesWithoutThrowing() {
        // Empty flag name
        Map<String, String> tags = Map.of(
                "floci:kubelet-arg:", "",
                "floci:kubelet-arg:max-pods=250", "true"
        );
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertEquals(List.of("--kubelet-arg=max-pods=250"), args);
    }

    @Test
    void controlCharactersLogWarningAndAreSkipped() {
        Map<String, String> tags = Map.of(
                "floci:kubelet-arg:bad\u0000flag=1", "true",
                "floci:kubelet-arg:max-pods=250", "true"
        );
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertEquals(List.of("--kubelet-arg=max-pods=250"), args);
    }

    @Test
    void unparseableJsonArrayLogsWarningAndIsSkipped() {
        Map<String, String> tags = Map.of(
                "floci:kubelet-args", "[not-valid-json",
                "floci:kubelet-arg:max-pods=250", "true"
        );
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertEquals(List.of("--kubelet-arg=max-pods=250"), args);
    }

    @Test
    void nonReservedTagsAreIgnored() {
        Map<String, String> tags = Map.of(
                "Environment", "production",
                "Team", "core",
                "floci:kubelet-arg:max-pods=250", "true"
        );
        List<String> args = EksClusterArgs.parseAndValidateClusterArgs(tags, "demo");
        assertEquals(List.of("--kubelet-arg=max-pods=250"), args);
    }
}
