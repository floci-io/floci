package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.ses.model.Identity;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SesIdentitySigningHostedZoneTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "zone.floci.test";
    private static final String KEY = "identity::" + REGION + "::" + DOMAIN;
    private static final TypeReference<Map<String, Identity>> IDENTITY_TYPE = new TypeReference<>() {};

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createdDomainReloadsWithoutPersistingAComputedHostedZone(boolean useV1Verification) throws Exception {
        Path file = directory.resolve("ses-identities.json");
        PersistentStorage<String, Identity> original = new PersistentStorage<>(file, IDENTITY_TYPE);
        SesIdentityService identities = new SesIdentityService(original, null, Clock.systemUTC());
        Identity identity = useV1Verification ? identities.verifyDomainIdentity(DOMAIN, REGION)
                : identities.createEmailIdentity(DOMAIN, null, null, REGION, null);

        PersistentStorage<String, Identity> restored = new PersistentStorage<>(file, IDENTITY_TYPE);
        restored.load();
        assertEquals(identity.getDkimTokens(), restored.get(KEY).orElseThrow().getDkimTokens());
        assertFalse(new ObjectMapper().readTree(Files.readString(file)).path(KEY).has("DkimSigningHostedZone"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "dkim.previous.floci.test"})
    void recordsWithAnUnusedHostedZoneStillReload(String signingZone) throws Exception {
        Path file = directory.resolve("legacy-identities.json");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode identity = mapper.createObjectNode().put("Identity", DOMAIN).put("IdentityType", "Domain");
        if ("null".equals(signingZone)) {
            identity.putNull("DkimSigningHostedZone");
        } else if (!"missing".equals(signingZone)) {
            identity.put("DkimSigningHostedZone", signingZone);
        }
        ObjectNode records = mapper.createObjectNode();
        records.set(KEY, identity);
        Files.writeString(file, records.toString());

        PersistentStorage<String, Identity> restored = new PersistentStorage<>(file, IDENTITY_TYPE);
        restored.load();
        Identity legacy = restored.get(KEY).orElseThrow();
        assertEquals(DOMAIN, legacy.getIdentity());
        assertEquals("Domain", legacy.getIdentityType());
        restored.put(KEY, legacy);
        PersistentStorage<String, Identity> rewritten = new PersistentStorage<>(file, IDENTITY_TYPE);
        rewritten.load();
        assertEquals(DOMAIN, rewritten.get(KEY).orElseThrow().getIdentity());
        assertFalse(mapper.readTree(Files.readString(file)).path(KEY).has("DkimSigningHostedZone"));
    }
}
