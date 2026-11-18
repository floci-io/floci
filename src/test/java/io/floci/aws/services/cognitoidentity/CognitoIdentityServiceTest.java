package io.floci.aws.services.cognitoidentity;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cognitoidentity.model.IdentityPool;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitoIdentityServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    private final InMemoryStorage<String, IdentityPool> raw = new InMemoryStorage<>();
    private final CognitoIdentityService service = new CognitoIdentityService(
            new InMemoryStorageFactory(ACCOUNT).withStore("cognitoidentity-pools.json",
                    new AccountAwareStorageBackend<>(raw, null, ACCOUNT)),
            new RegionResolver(REGION, ACCOUNT));

    /**
     * IAM's tag lookup reads the tags of the identity pool whose ARN it is given, as tag calls leave them, a tag call
     * giving the pool a new map rather than changing the one a reader holds, and those of a pool stored before storage
     * was account-aware, in place, without migrating it; nothing for an ARN of another account or Region, or of no
     * pool.
     */
    @Test
    void storedTagsAreTheTagsOfTheIdentityPoolTheArnNamesAndTaggingPutsANewMap() {
        IdentityPool spec = new IdentityPool();
        spec.setIdentityPoolName("tagged");
        spec.setIdentityPoolTags(new LinkedHashMap<>(Map.of("env", "dev")));
        String arn = service.createIdentityPool(spec, REGION).getArn();
        Map<String, String> held = service.listTagsForResource(arn, REGION);
        service.tagResource(arn, Map.of("team", "data"), REGION);
        service.untagResource(arn, List.of("env"), REGION);

        String legacyId = REGION + ":0b6b2c1e-1f6e-4d7a-9a51-3c1f7f0e2d4a";
        String legacyArn = "arn:aws:cognito-identity:" + REGION + ":" + ACCOUNT + ":identitypool/" + legacyId;
        IdentityPool legacy = new IdentityPool();
        legacy.setIdentityPoolId(legacyId);
        legacy.setIdentityPoolName("legacy");
        legacy.setArn(legacyArn);
        legacy.setIdentityPoolTags(Map.of("tier", "web"));
        raw.put(REGION + "::" + legacyId, legacy);

        assertEquals(Map.of("env", "dev"), held);
        assertEquals(Map.of("team", "data"), service.storedTags(arn));
        assertEquals(Map.of("tier", "web"), service.storedTags(legacyArn));
        assertTrue(raw.get(REGION + "::" + legacyId).isPresent());
        assertEquals(Map.of(), service.storedTags(arn.replace(":" + ACCOUNT + ":", ":111122223333:")));
        assertEquals(Map.of(), service.storedTags(arn.replace(":" + REGION + ":" + ACCOUNT, ":eu-west-1:" + ACCOUNT)));
        assertEquals(Map.of(), service.storedTags(arn + "0"));
        assertEquals(Map.of(), service.storedTags("arn:aws:cognito-idp:" + REGION + ":" + ACCOUNT + ":userpool/x"));
    }
}
