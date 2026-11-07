package io.floci.aws.core.common;

import java.util.Map;

/**
 * The availability-zone id of a zone, the way AWS writes one: a short region prefix and
 * {@code -az<n>} ({@code use1-az1}, {@code apne1-az2}, {@code usgw1-az1}, {@code cnn1-az1}). The
 * id names the same physical zone in every account; it is the zone names that AWS maps per
 * account, which is why {@code us-east-1a} is {@code use1-az1} for one account and
 * {@code use1-az4} for another. Floci has one account's view, so it pairs zone {@code a} with
 * {@code az1}, {@code b} with {@code az2}, and so on.
 *
 * <p>The prefix abbreviates the region: its first segment, the initial letters of the middle
 * ones ({@code northeast} gives {@code ne}, {@code gov} gives {@code g}) and its number. That
 * holds for the commercial, China and GovCloud regions. AWS publishes no zone id for an ISO or
 * EUSC region, so a region with a middle segment this class does not know keeps its whole name
 * as the prefix ({@code us-iso-east-1-az1}) rather than a guessed abbreviation.
 *
 * <p>EC2, VPC flow logs and EFS all derive their ids here, so a subnet, a flow-log record and a
 * mount target in one zone report the same id.
 */
public final class AvailabilityZoneIds {

    private static final String SEPARATOR = "-az";

    private static final Map<String, String> SEGMENT_INITIALS = Map.ofEntries(
            Map.entry("north", "n"), Map.entry("south", "s"), Map.entry("east", "e"),
            Map.entry("west", "w"), Map.entry("central", "c"), Map.entry("northeast", "ne"),
            Map.entry("northwest", "nw"), Map.entry("southeast", "se"), Map.entry("southwest", "sw"),
            Map.entry("gov", "g"));

    private AvailabilityZoneIds() {
    }

    /**
     * The id of {@code zoneName} in {@code region}. A name that is not the region's
     * {@code <region><letter>} keeps the first zone's id.
     */
    public static String forZoneName(String region, String zoneName) {
        if (zoneName != null && zoneName.length() == region.length() + 1 && zoneName.startsWith(region)) {
            char letter = Character.toLowerCase(zoneName.charAt(zoneName.length() - 1));
            if (letter >= 'a' && letter <= 'z') {
                return forIndex(region, letter - 'a' + 1);
            }
        }
        return forIndex(region, 1);
    }

    /** The id of the {@code index}-th zone of {@code region}, counting from 1. */
    public static String forIndex(String region, int index) {
        return prefix(region) + SEPARATOR + index;
    }

    /**
     * The 1-based zone index {@code zoneId} names in {@code region}, or -1 when it is not one of
     * this region's ids.
     */
    public static int indexOf(String region, String zoneId) {
        String start = prefix(region) + SEPARATOR;
        if (zoneId == null || !zoneId.startsWith(start)) {
            return -1;
        }
        String digits = zoneId.substring(start.length());
        if (digits.isEmpty() || digits.length() > 2 || !digits.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        return Integer.parseInt(digits);
    }

    /** {@code use1} for {@code us-east-1}; the region itself where its segments are not known. */
    public static String prefix(String region) {
        String[] segments = region.split("-");
        if (segments.length < 3) {
            return region;
        }
        StringBuilder prefix = new StringBuilder(segments[0]);
        for (int i = 1; i < segments.length - 1; i++) {
            String initials = SEGMENT_INITIALS.get(segments[i]);
            if (initials == null) {
                return region;
            }
            prefix.append(initials);
        }
        return prefix.append(segments[segments.length - 1]).toString();
    }
}
