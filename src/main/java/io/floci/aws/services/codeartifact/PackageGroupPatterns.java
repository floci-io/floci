package io.floci.aws.services.codeartifact;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Package group pattern parsing, matching, and specificity, confirmed against the CodeArtifact
 * User Guide's "Package group definition syntax and matching behavior" page rather than inferred:
 * a pattern follows a package path's own shape (a leading {@code /}, then up to three
 * {@code /}-separated components for format, namespace, and name, in that order), terminated by
 * one of three suffix characters that determine how its own last specified component matches:
 * {@code $} for an exact match of the full remaining coordinate, {@code ~} for a prefix match on
 * a word boundary, or {@code *} (always alone as its own segment) for "every value of this
 * component, and everything deeper." A component the pattern never reaches at all (because an
 * earlier component already terminated the pattern) is implicitly unconstrained, the same as a
 * trailing {@code *} would make it.
 *
 * <p>Deliberately not modeled: Unicode confusable-character normalization (the User Guide's
 * "Additional variations" section mentions it only in passing, as a defense against visually
 * similar characters from different alphabets, and implementing it faithfully needs a real
 * Unicode confusables table this emulator does not have). Weak-match normalization here only
 * casefolds and collapses runs of {@code -}/{@code .}/{@code _} into a single {@code .}, the
 * part of that same section with a concrete, testable rule.
 */
final class PackageGroupPatterns {

    private PackageGroupPatterns() {
    }

    /** How the pattern's own last specified component is matched. */
    enum TerminalKind { EXACT, PREFIX, WILDCARD }

    /** Whether a package matched a pattern exactly as written, or only a casefolded/separator variation of it. */
    enum MatchStrength { STRONG, WEAK }

    /**
     * A package group pattern, parsed once rather than re-parsed on every candidate it is tested
     * against. {@code literalSegments} holds every component's own literal text (the terminal
     * component's text has its suffix character already stripped); every non-terminal component
     * is always a plain exact value, never itself carrying a suffix, since only the pattern's own
     * last specified component can terminate it.
     */
    record ParsedPattern(String raw, List<String> literalSegments, TerminalKind terminalKind) {
        int segmentCount() {
            return literalSegments.size();
        }
    }

    /**
     * A package's own coordinate, reduced to the same three-component shape a pattern describes.
     * {@code namespace} is the empty string (never {@code null}) for a namespace-less format, the
     * same "blank namespace section" the User Guide describes for a pattern's own namespace
     * component in that case, so the two sides compare on equal footing.
     */
    record PackagePath(String format, String namespace, String name) {
        PackagePath {
            namespace = namespace == null ? "" : namespace;
        }
    }

    /**
     * Parses {@code pattern} into its component segments and terminal kind. Throws {@link
     * IllegalArgumentException} for anything this emulator cannot confidently interpret: no
     * leading {@code /}, more than three components, a non-terminal component carrying a suffix
     * character, or a terminal component whose suffix is not one of {@code $}/{@code ~}/{@code *}.
     * {@code $} is only accepted on the third (name-level) component: the User Guide's own
     * examples never show it used to terminate a pattern any shallower than that, and a shallow
     * exact match leaves every deeper component just as unconstrained as a trailing {@code *}
     * would, which is already how every shown example expresses that instead.
     */
    static ParsedPattern parse(String pattern) {
        if (pattern == null || !pattern.startsWith("/")) {
            throw new IllegalArgumentException("packageGroup pattern must start with '/'.");
        }
        String[] rawSegments = pattern.substring(1).split("/", -1);
        if (rawSegments.length == 0 || rawSegments.length > 3) {
            throw new IllegalArgumentException("packageGroup pattern must have between 1 and 3 "
                    + "'/'-separated components.");
        }
        List<String> literalSegments = new ArrayList<>(rawSegments.length);
        for (int i = 0; i < rawSegments.length - 1; i++) {
            String segment = rawSegments[i];
            if (segment.endsWith("$") || segment.endsWith("~") || segment.equals("*")) {
                throw new IllegalArgumentException("Only the last component of a packageGroup pattern may end "
                        + "with '$', '~', or be '*'.");
            }
            literalSegments.add(segment);
        }
        String terminalSegment = rawSegments[rawSegments.length - 1];
        TerminalKind terminalKind;
        String terminalLiteral;
        if (terminalSegment.equals("*")) {
            terminalKind = TerminalKind.WILDCARD;
            terminalLiteral = "";
        } else if (terminalSegment.endsWith("~") && terminalSegment.length() > 1) {
            terminalKind = TerminalKind.PREFIX;
            terminalLiteral = terminalSegment.substring(0, terminalSegment.length() - 1);
        } else if (terminalSegment.endsWith("$") && terminalSegment.length() > 1) {
            if (rawSegments.length != 3) {
                throw new IllegalArgumentException("A packageGroup pattern can only end with '$' when it "
                        + "specifies format, namespace, and name.");
            }
            terminalKind = TerminalKind.EXACT;
            terminalLiteral = terminalSegment.substring(0, terminalSegment.length() - 1);
        } else {
            throw new IllegalArgumentException("A packageGroup pattern must end with '$', '~', or '*'.");
        }
        literalSegments.add(terminalLiteral);
        return new ParsedPattern(pattern, literalSegments, terminalKind);
    }

    /**
     * Casefolds and collapses runs of {@code -}/{@code .}/{@code _} into a single {@code .}, the
     * documented rule behind weak-match "Additional variations" (confusable-character
     * normalization excluded, see this class's own javadoc).
     */
    private static String normalizeForWeakMatch(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[-._]+", ".");
    }

    /**
     * Whether {@code candidate} starts with {@code prefix} and, if it is longer than {@code
     * prefix}, the very next character is not itself part of the same "word" (the User Guide's
     * own regex, {@code [\p{L}\p{N}][\p{L}\p{N}\p{M}]*}): {@code foo~} matches {@code foo} and
     * {@code foo-bar} but not {@code food} or {@code foot}.
     */
    private static boolean matchesWordBoundaryPrefix(String candidate, String prefix) {
        if (!candidate.startsWith(prefix)) {
            return false;
        }
        if (candidate.length() == prefix.length()) {
            return true;
        }
        char next = candidate.charAt(prefix.length());
        return !Character.isLetterOrDigit(next) && Character.getType(next) != Character.NON_SPACING_MARK
                && Character.getType(next) != Character.COMBINING_SPACING_MARK
                && Character.getType(next) != Character.ENCLOSING_MARK;
    }

    private static boolean componentMatches(ParsedPattern pattern, int index, String candidateComponent,
            boolean weak) {
        String patternText = pattern.literalSegments().get(index);
        String candidateText = weak ? normalizeForWeakMatch(candidateComponent) : candidateComponent;
        String patternComparedText = weak ? normalizeForWeakMatch(patternText) : patternText;
        boolean isTerminal = index == pattern.segmentCount() - 1;
        if (!isTerminal) {
            return candidateText.equals(patternComparedText);
        }
        return switch (pattern.terminalKind()) {
            case WILDCARD -> true;
            case EXACT -> candidateText.equals(patternComparedText);
            case PREFIX -> matchesWordBoundaryPrefix(candidateText, patternComparedText);
        };
    }

    /**
     * The strongest way {@code path} matches {@code pattern}, or {@code null} for no match at
     * all. Checked strong first, then weak, exactly like {@link #componentMatches} computes each
     * independently: a package matching strongly on every component pattern specifies is a
     * {@code STRONG} match; one that only matches once every compared component is casefolded and
     * separator-normalized is a {@code WEAK} match.
     */
    static MatchStrength matchStrength(ParsedPattern pattern, PackagePath path) {
        String[] components = {path.format(), path.namespace(), path.name()};
        if (pattern.segmentCount() > components.length) {
            return null;
        }
        boolean strong = true;
        boolean weak = true;
        for (int i = 0; i < pattern.segmentCount(); i++) {
            if (!componentMatches(pattern, i, components[i], false)) {
                strong = false;
            }
            if (!componentMatches(pattern, i, components[i], true)) {
                weak = false;
            }
        }
        if (strong) {
            return MatchStrength.STRONG;
        }
        return weak ? MatchStrength.WEAK : null;
    }

    /**
     * A pattern's own specificity, for picking the single most specific match among several
     * patterns that all match the same package: a deeper pattern (more components specified) is
     * always more specific than a shallower one, since every package the deeper one matches also
     * matches the shallower one but not the reverse; at the same depth, {@code EXACT} is more
     * specific than {@code PREFIX} (a proper subset of it), which is more specific than {@code
     * WILDCARD}; two {@code PREFIX} patterns at the same depth rank by their own literal text
     * length, since a longer prefix matches a proper subset of what a shorter one does.
     */
    static int compareSpecificity(ParsedPattern a, ParsedPattern b) {
        if (a.segmentCount() != b.segmentCount()) {
            return Integer.compare(a.segmentCount(), b.segmentCount());
        }
        int kindRank = terminalKindRank(a.terminalKind()) - terminalKindRank(b.terminalKind());
        if (kindRank != 0) {
            return kindRank;
        }
        if (a.terminalKind() == TerminalKind.PREFIX) {
            String aliteral = a.literalSegments().get(a.segmentCount() - 1);
            String bliteral = b.literalSegments().get(b.segmentCount() - 1);
            return Integer.compare(aliteral.length(), bliteral.length());
        }
        return 0;
    }

    private static int terminalKindRank(TerminalKind kind) {
        return switch (kind) {
            case WILDCARD -> 0;
            case PREFIX -> 1;
            case EXACT -> 2;
        };
    }

    /**
     * Whether every package {@code descendant} matches also matches {@code ancestor}, with
     * {@code descendant} a strict, proper subset: the structural test behind package group
     * hierarchy (a group's {@code parent} is the most specific other existing group this returns
     * true for), since the real parent/child relationship is a property of the two patterns
     * themselves, not something stored on either one directly, and must stay correct as sibling
     * groups are freely created and deleted around them.
     *
     * <p>Compares every literal component through {@link #normalizeForWeakMatch}, the same
     * normalization {@link #matchStrength} applies to a package path: the User Guide defines a
     * group's parent as the more specific pattern whose own matched packages are "a proper subset
     * of the packages that match" the less specific one, and a weakly-matched package is itself
     * documented as "associated with the group" it weakly matches, so that subset relationship is
     * computed over the same (strong-or-weak) association weak match already grants a package,
     * not case/separator-literal text alone. Raised by Qodo's review of this PR: without this, a
     * descendant pattern that only case/separator-varies from its true ancestor (e.g.
     * {@code /npm/SPACE/foo$} under {@code /npm/space/*}) found no parent at all, so its own
     * {@code INHERIT} setting resolved to the top-of-hierarchy {@code ALLOW} default instead of
     * the broader group's actual restriction.
     *
     * <p>Requires {@code descendant} to be <em>strictly</em> more specific than {@code ancestor}
     * under {@link #compareSpecificity}'s own existing ranking (segment depth, terminal-kind
     * rank, then prefix literal length) before anything else: two patterns normalization makes
     * "equal enough" in every component, e.g. {@code /npm//foo$} and {@code /npm//Foo$}, or
     * {@code /npm/space/foo$} and {@code /npm/SPACE/foo$}, match the exact same (strong-or-weak)
     * package set, not a proper subset of one another, so without this check normalizing the
     * component comparisons below would make each pattern call the other its own strict ancestor
     * at once. {@link #resolveEffectiveMode}'s {@code INHERIT} walk has no cycle detection and
     * loops forever the moment that happens; found by this PR's own regression test for a
     * different fix hanging the test run. {@code compareSpecificity} ties at 0 for exactly the
     * pairs that pose this risk (same depth, same terminal kind, and for two {@code PREFIX}
     * patterns, equal raw literal length) and is strictly positive for every genuine
     * specialization (a deeper pattern, a more specific terminal kind, or a longer raw prefix),
     * so this rejects every tie without having to special-case which component normalization
     * could have caused it.
     */
    static boolean isStrictAncestor(ParsedPattern ancestor, ParsedPattern descendant) {
        if (ancestor.raw().equals(descendant.raw())) {
            return false;
        }
        if (compareSpecificity(descendant, ancestor) <= 0) {
            return false;
        }
        for (int i = 0; i < ancestor.segmentCount() - 1; i++) {
            if (!normalizeForWeakMatch(ancestor.literalSegments().get(i))
                    .equals(normalizeForWeakMatch(descendant.literalSegments().get(i)))) {
                return false;
            }
        }
        int lastIndex = ancestor.segmentCount() - 1;
        if (ancestor.terminalKind() == TerminalKind.WILDCARD) {
            return true;
        }
        String ancestorLiteral = normalizeForWeakMatch(ancestor.literalSegments().get(lastIndex));
        boolean descendantTerminalHere = descendant.segmentCount() == ancestor.segmentCount();
        if (descendantTerminalHere) {
            String descendantLiteral = normalizeForWeakMatch(descendant.literalSegments().get(lastIndex));
            if (ancestor.terminalKind() == TerminalKind.EXACT) {
                // Unreachable in practice: EXACT already has compareSpecificity's highest
                // terminal-kind rank, so nothing can be strictly more specific than it at the
                // same depth, and EXACT's own segmentCount is already the pattern's maximum
                // depth, so the guard above rejects every same-depth pair before reaching here.
                // Left in place (rather than asserting it away) because this branch stays
                // correct, not just unreachable, if that ranking ever changes.
                return descendant.terminalKind() == TerminalKind.EXACT && ancestorLiteral.equals(descendantLiteral);
            }
            // ancestor.terminalKind() == PREFIX
            if (descendant.terminalKind() == TerminalKind.WILDCARD) {
                return false;
            }
            return matchesWordBoundaryPrefix(descendantLiteral, ancestorLiteral);
        }
        // descendant goes deeper than ancestor's own terminal component, which (being non-terminal
        // for descendant) is always a plain literal value there, never a suffix of its own.
        String descendantValueHere = normalizeForWeakMatch(descendant.literalSegments().get(lastIndex));
        if (ancestor.terminalKind() == TerminalKind.EXACT) {
            return ancestorLiteral.equals(descendantValueHere);
        }
        return matchesWordBoundaryPrefix(descendantValueHere, ancestorLiteral);
    }

    /** A package path for a format/namespace/name coordinate, for {@link #matchStrength}. */
    static PackagePath packagePath(String format, String namespace, String name) {
        return new PackagePath(format, namespace, name);
    }
}
