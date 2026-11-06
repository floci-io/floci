package com.floci.test;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.KeyPairInfo;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EC2 key pair names under concurrent CreateKeyPair and ImportKeyPair")
class Ec2KeyPairConcurrentNameTest {

    private static final int REQUESTS = 8;

    @Test
    @DisplayName("Of concurrent requests for one name, one stores a key pair and the others are refused as duplicates")
    void oneOfConcurrentRequestsForANameGetsIt() throws Exception {
        String name = TestFixtures.uniqueName("contested-key");
        String publicKey = "ssh-ed25519 " + Base64.getEncoder().encodeToString(OpenSSHPublicKeyUtil.encodePublicKey(
                new Ed25519PrivateKeyParameters(new SecureRandom()).generatePublicKey()));
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            ExecutorService requests = Executors.newFixedThreadPool(REQUESTS);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> results = new ArrayList<>();
                for (int i = 0; i < REQUESTS; i++) {
                    boolean imported = i % 2 == 1;
                    results.add(requests.submit(() -> {
                        start.await();
                        return imported
                                ? ec2.importKeyPair(r -> r.keyName(name)
                                        .publicKeyMaterial(SdkBytes.fromUtf8String(publicKey))).keyPairId()
                                : ec2.createKeyPair(r -> r.keyName(name)).keyPairId();
                    }));
                }
                start.countDown();

                List<String> stored = new ArrayList<>();
                List<Throwable> refused = new ArrayList<>();
                for (Future<String> result : results) {
                    try {
                        stored.add(result.get(60, TimeUnit.SECONDS));
                    } catch (ExecutionException e) {
                        refused.add(e.getCause());
                    }
                }

                assertThat(stored).hasSize(1);
                // The message is not compared: AWS words it differently from Floci.
                assertThat(refused).hasSize(REQUESTS - 1).allSatisfy(e ->
                        assertThat(e).isInstanceOfSatisfying(Ec2Exception.class, refusal -> {
                            assertThat(refusal.awsErrorDetails().errorCode()).isEqualTo("InvalidKeyPair.Duplicate");
                            assertThat(refusal.statusCode()).isEqualTo(400);
                        }));
                assertThat(ec2.describeKeyPairs(r -> r.keyNames(name)).keyPairs())
                        .extracting(KeyPairInfo::keyPairId).containsExactlyElementsOf(stored);
            } finally {
                // Waits up to 60 s for the requests to finish before the name is deleted, so a slow one
                // is not left to store a key pair after the cleanup.
                requests.shutdown();
                if (!requests.awaitTermination(60, TimeUnit.SECONDS)) {
                    requests.shutdownNow();
                }
                ec2.deleteKeyPair(r -> r.keyName(name));
            }
        }
    }
}
