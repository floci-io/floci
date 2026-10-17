package io.github.hectorvent.floci.services.codeartifact;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NuGet's own version normalization, exactly as AWS's CodeArtifact User Guide documents it for
 * the {@code nuget} format ("NuGet package name, version, and asset name normalization"):
 * leading zeroes removed from each numeric segment, a trailing zero fourth segment omitted, and
 * SemVer 2.0.0 build metadata removed. Used to match a caller-supplied version against the
 * already-normalized, lowercased version strings BaGetter itself returns (its own package content
 * controller normalizes the same way, via {@code NuGetVersion.ToNormalizedString()}), not to
 * reimplement NuGet's full version model: a version this cannot parse as a plain dotted-numeric
 * release, with an optional prerelease label, is left exactly as given rather than guessed at,
 * so it simply fails to match anything BaGetter lists instead of silently normalizing it wrong.
 *
 * <p>Always pads a short release up to at least three segments (AWS's own worked example, "1.00
 * is treated as 1.0", doesn't; real {@code NuGetVersion.ToNormalizedString()} does, always
 * emitting at least Major.Minor.Patch), since matching what BaGetter's version list actually
 * contains is the entire point here, not reproducing AWS's own doc text byte-for-byte.
 */
final class NuGetVersions {

    private NuGetVersions() {
    }

    static String normalize(String version) {
        String withoutMetadata = version.split("\\+", 2)[0];
        int dash = withoutMetadata.indexOf('-');
        String release = dash < 0 ? withoutMetadata : withoutMetadata.substring(0, dash);
        String prerelease = dash < 0 ? null : withoutMetadata.substring(dash + 1);

        // -1: a plain split() silently drops a trailing empty string, so "1.0.0." (a real
        // version has no empty segment) would otherwise normalize identically to "1.0.0" and
        // match a genuinely published version under a caller-supplied, malformed one.
        String[] rawSegments = release.split("\\.", -1);
        List<String> segments = new ArrayList<>();
        for (String rawSegment : rawSegments) {
            if (!rawSegment.matches("[0-9]+")) {
                // Not a plain numeric segment (or not numeric at all, or empty, see above): give
                // up on normalizing and let the caller's own text through unchanged, so this only
                // ever actively changes a string it is sure it understood correctly.
                return version;
            }
            try {
                segments.add(String.valueOf(Long.parseLong(rawSegment)));
            } catch (NumberFormatException e) {
                // A segment with more digits than fit in a long: NuGetVersion itself has no such
                // limit, but nothing this talks to needs one either, so this is treated the same
                // as any other segment it isn't confident it understood correctly.
                return version;
            }
        }
        while (segments.size() < 3) {
            segments.add("0");
        }
        if (segments.size() > 4) {
            return version;
        }
        if (segments.size() == 4 && "0".equals(segments.get(3))) {
            segments.remove(3);
        }
        String normalized = String.join(".", segments);
        if (prerelease != null && !prerelease.isEmpty()) {
            normalized = normalized + "-" + prerelease;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
