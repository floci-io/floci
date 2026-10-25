package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads a list from an AWS Query request. The protocol numbers a list's members under the list's
 * name, in the generic {@code List.member.N} form, or as {@code List.Name.N} when the list's shape
 * names its member (the real Redshift SDK sends {@code SubnetIds.SubnetIdentifier.N}, not
 * {@code SubnetIds.member.N}). A bare {@code List.N} is read too. Every N is read, in order, with no
 * stop at a gap.
 */
public final class AwsQueryLists {

    private AwsQueryLists() {
    }

    /**
     * The keys of the members of {@code list}, in the generic form or under {@code member}, the name
     * its shape gives them; with a null {@code member}, the generic form only.
     */
    public static Pattern memberKeys(String list, String member) {
        String names = member == null ? "\\.member" : "\\.member|\\." + Pattern.quote(member);
        return Pattern.compile(Pattern.quote(list) + "(" + names + ")?\\.\\d+");
    }

    /** The non-blank values of the members of {@code list}, as {@link #memberKeys} reads them, in order. */
    public static List<String> values(MultivaluedMap<String, String> params, String list, String member) {
        Pattern keys = memberKeys(list, member);
        return params.keySet().stream()
                .filter(key -> keys.matcher(key).matches())
                .sorted(Comparator.comparingInt(AwsQueryLists::memberIndex))
                .map(params::getFirst)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    /** The number a member's key ends in, or {@link Integer#MAX_VALUE} for a key that ends in none. */
    public static int memberIndex(String key) {
        int lastDot = key.lastIndexOf('.');
        if (lastDot < 0 || lastDot == key.length() - 1) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(key.substring(lastDot + 1));
        } catch (NumberFormatException expected) {
            // Not a number, or one too large for an index: the key sorts after every member.
            return Integer.MAX_VALUE;
        }
    }
}
