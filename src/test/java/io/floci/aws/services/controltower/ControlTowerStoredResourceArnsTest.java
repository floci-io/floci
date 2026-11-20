package io.floci.aws.services.controltower;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.controltower.model.LandingZone;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlTowerStoredResourceArnsTest {

    private static final String ACCOUNT = "000000000101";
    private static final String REGION = "us-east-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryStorage<String, LandingZone> rawLandingZones = new InMemoryStorage<>();
    private final ControlTowerService landingZones = new ControlTowerService(
            new AccountAwareStorageBackend<>(rawLandingZones, null, ACCOUNT),
            AccountAwareStorageBackend.inMemory(ACCOUNT));
    private final ControlTowerControlService controls =
            new ControlTowerControlService(AccountAwareStorageBackend.inMemory(ACCOUNT));
    private final ControlTowerStoredResourceArns stored =
            new ControlTowerStoredResourceArns(new RegionResolver(REGION, ACCOUNT), landingZones, controls);

    /**
     * IAM's lookup reads the tags of the landing zone or the enabled control the ARN names, as the tag calls leave
     * them, and nothing for another account's, another Region's or an unknown one, an enabled baseline or another
     * service.
     */
    @Test
    void storedTagsAreTheTagsOfTheLandingZoneOrTheEnabledControlTheArnNames() throws Exception {
        String landingZone = landingZone("web");
        landingZones.tagLandingZone(ACCOUNT, REGION, landingZone, Map.of("team", "data"));
        String control = controls.enable(ACCOUNT, REGION, json("""
                {"controlIdentifier":"arn:aws:controlcatalog:::control/fixture",
                 "targetIdentifier":"arn:aws:organizations::000000000101:ou/o-test/ou-stored1",
                 "tags":{"team":"web","env":"dev"}}
                """)).arn();
        controls.untagResource(REGION, control, List.of("env"));

        assertEquals("controltower", stored.namespace());
        assertEquals(Map.of("team", "data"), stored.storedTags(landingZone));
        assertEquals(Map.of("team", "web"), stored.storedTags(control));
        assertTrue(stored.storedTags(landingZone.replace(ACCOUNT, "111122223333")).isEmpty());
        assertTrue(stored.storedTags(control.replace(REGION, "us-west-2")).isEmpty());
        assertTrue(stored.storedTags(control + "0").isEmpty());
        assertTrue(stored.storedTags("arn:aws:controltower:us-east-1:000000000101:enabledbaseline/ABC").isEmpty());
        assertTrue(stored.storedTags("arn:aws:guardduty:us-east-1:000000000101:detector/abc").isEmpty());
    }

    /** A landing zone stored before storage was account-aware is read in place: the lookup migrates nothing. */
    @Test
    void storedTagsReadAPreAccountAwareLandingZoneInPlace() throws Exception {
        String landingZone = landingZone("data");
        rawLandingZones.put(REGION, rawLandingZones.get(ACCOUNT + "/" + REGION).orElseThrow());
        rawLandingZones.delete(ACCOUNT + "/" + REGION);

        assertEquals(Map.of("team", "data"), stored.storedTags(landingZone));
        assertEquals(Set.of(REGION), rawLandingZones.keys());
    }

    /** The ARN of the landing zone created for the account in the Region, tagged with the team. */
    private String landingZone(String team) throws Exception {
        return landingZones.createLandingZone(ACCOUNT, REGION, json("""
                {"version":"4.0","tags":{"team":"%s"},
                 "manifest":{"governedRegions":["us-east-1"],"accessManagement":{"enabled":true}}}
                """.formatted(team))).arn();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
