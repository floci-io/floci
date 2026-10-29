package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignedMarkersTest {

    private final SignedMarkers markers = new SignedMarkers();

    @Test
    void anIssuedMarkerNamesItsCursor() {
        assertEquals("role.with.dots", markers.cursorOf(markers.issue("role.with.dots")));
    }

    @Test
    void aMarkerIssuedByAnotherInstanceIsNotRecognised() {
        assertNull(markers.cursorOf(new SignedMarkers().issue("role-b")));
    }

    @Test
    void anUnsignedOrUndecodableMarkerIsNotRecognised() {
        assertNull(markers.cursorOf(Base64Url.encode("role-b")));
        assertNull(markers.cursorOf(Base64Url.encode("role-b.forged")));
        assertNull(markers.cursorOf("not base64!"));
    }

    @Test
    void aScopedMarkerResumesOnlyItsOwnScope() {
        String marker = markers.issue("account-a\nListUsers", "user-b");

        assertEquals("user-b", markers.cursorOf("account-a\nListUsers", marker));
        assertNull(markers.cursorOf("account-b\nListUsers", marker));
        assertNull(markers.cursorOf("account-a\nListGroups", marker));
        assertNull(markers.cursorOf(marker), "an unscoped read does not accept a scoped marker");
        assertNull(markers.cursorOf("account-a\nListUsers", markers.issue("user-b")),
                "a scoped read does not accept an unscoped marker");
    }

    /** The scope is length-prefixed, so moving characters between scope and cursor breaks the signature. */
    @Test
    void theScopeAndCursorCannotTradeCharacters() {
        String marker = markers.issue("ab", "c");

        assertNull(markers.cursorOf("a", Base64Url.encode(
                new String(Base64.getUrlDecoder().decode(marker)).replaceFirst("^c", "bc"))));
    }

    /** A cursor too long for the limit is named by digest, and still resumes only its own scope. */
    @Test
    void aCursorTooLongForTheLimitStillFitsAndResolves() {
        SignedMarkers bounded = new SignedMarkers(320);
        String arn = "arn:aws:iam::000000000000:policy/" + "p/".repeat(250) + "name";
        String marker = bounded.issue("account-a\nListAttachedRolePolicies", arn);

        assertTrue(marker.length() <= 320, marker.length() + " characters");
        assertEquals(arn, bounded.cursorOf("account-a\nListAttachedRolePolicies", marker));
        assertNull(bounded.cursorOf("account-b\nListAttachedRolePolicies", marker));
        assertNull(markers.cursorOf("account-a\nListAttachedRolePolicies", marker),
                "another instance holds neither the key nor the cursor");
        assertEquals("user-b", bounded.cursorOf("s", bounded.issue("s", "user-b")),
                "a cursor that fits is carried as it is");
    }

    /** The long cursors an instance holds are bounded; the least recently used is forgotten. */
    @Test
    void anEvictedLongCursorIsNotRecognisedButARecentOneResumes() {
        SignedMarkers bounded = new SignedMarkers(320);
        String prefix = "arn:aws:iam::000000000000:policy/" + "p/".repeat(250);
        String oldest = bounded.issue("s", prefix + "oldest");
        for (int i = 0; i < SignedMarkers.MAX_LONG_CURSORS; i++) {
            bounded.issue("s", prefix + i);
        }
        String recent = bounded.issue("s", prefix + "recent");

        assertNull(bounded.cursorOf("s", oldest), "an evicted digest resolves like an unknown one");
        assertEquals(prefix + "recent", bounded.cursorOf("s", recent));
    }
}
