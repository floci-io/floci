package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.ses.model.Identity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SesIdentitySigningHostedZoneTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "zone.floci.test";
    private static final String KEY = "identity::" + REGION + "::" + DOMAIN;
    private static final String SIGNING_ZONE = "dkim.identity-specific.floci.test";
    private static final TypeReference<Map<String, Identity>> IDENTITY_TYPE = new TypeReference<>() {};

    @TempDir
    Path directory;

    @Test
    void signingHostedZonePersistsAcrossStorageReload() {
        Path file = directory.resolve("ses-identities.json");
        PersistentStorage<String, Identity> original = new PersistentStorage<>(file, IDENTITY_TYPE);
        Identity identity = new Identity(DOMAIN, "Domain");
        identity.setDkimSigningHostedZone(SIGNING_ZONE);
        original.put(KEY, identity);

        PersistentStorage<String, Identity> restored = new PersistentStorage<>(file, IDENTITY_TYPE);
        restored.load();
        assertEquals(SIGNING_ZONE, restored.get(KEY).orElseThrow().getDkimSigningHostedZone());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyMissingOrNullHostedZoneKeepsTheExistingDnsTarget(boolean explicitNull) throws Exception {
        Path file = directory.resolve("legacy-identities.json");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode identity = mapper.createObjectNode().put("Identity", DOMAIN).put("IdentityType", "Domain");
        if (explicitNull) {
            identity.putNull("DkimSigningHostedZone");
        }
        ObjectNode records = mapper.createObjectNode();
        records.set(KEY, identity);
        Files.writeString(file, records.toString());

        PersistentStorage<String, Identity> restored = new PersistentStorage<>(file, IDENTITY_TYPE);
        restored.load();
        Identity legacy = restored.get(KEY).orElseThrow();
        assertEquals("dkim.amazonses.com", legacy.getDkimSigningHostedZone());
        restored.put(KEY, legacy);
        PersistentStorage<String, Identity> rewritten = new PersistentStorage<>(file, IDENTITY_TYPE);
        rewritten.load();
        assertEquals("dkim.amazonses.com", rewritten.get(KEY).orElseThrow().getDkimSigningHostedZone());
    }

    @Test
    void keyRotationAndEmailInheritancePreserveTheDomainHostedZone() {
        SesIdentityService identities = new SesIdentityService(new InMemoryStorage<>(), null, Clock.systemUTC());
        Identity domain = identities.createEmailIdentity(DOMAIN, null, null, REGION, null);
        domain.setDkimSigningHostedZone(SIGNING_ZONE);
        identities.save(domain, REGION);
        identities.putDkimSigningAttributes(DOMAIN, "AWS_SES", null, "RSA_1024_BIT", REGION);
        assertEquals(SIGNING_ZONE, identities.find(DOMAIN, REGION).orElseThrow().getDkimSigningHostedZone());

        Identity email = identities.createEmailIdentity("user@" + DOMAIN, null, null, REGION, null);
        assertEquals(SIGNING_ZONE, identities.effectiveDkimSource(email, REGION).getDkimSigningHostedZone());
        identities.putDkimSigningAttributes(DOMAIN, "EXTERNAL", "selector", null, REGION);
        assertEquals(SIGNING_ZONE, identities.find(DOMAIN, REGION).orElseThrow().getDkimSigningHostedZone());
        identities.putDkimSigningAttributes(DOMAIN, "AWS_SES", null, "RSA_2048_BIT", REGION);
        assertEquals(SIGNING_ZONE, identities.effectiveDkimSource(email, REGION).getDkimSigningHostedZone());
    }
}
