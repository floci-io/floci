package io.floci.aws.services.codeartifact;

import org.junit.jupiter.api.Test;

import static io.floci.aws.services.codeartifact.PackageGroupPatterns.MatchStrength.STRONG;
import static io.floci.aws.services.codeartifact.PackageGroupPatterns.MatchStrength.WEAK;
import static io.floci.aws.services.codeartifact.PackageGroupPatterns.isStrictAncestor;
import static io.floci.aws.services.codeartifact.PackageGroupPatterns.matchStrength;
import static io.floci.aws.services.codeartifact.PackageGroupPatterns.packagePath;
import static io.floci.aws.services.codeartifact.PackageGroupPatterns.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every case here is checked against the CodeArtifact User Guide's own "Package group definition
 * syntax and matching behavior" worked examples, not inferred: the six pattern shapes it lists,
 * the {@code /npm/*}-vs-{@code /npm/space/*} hierarchy example, the {@code foo~} word-boundary
 * example, and the {@code AsyncStorage}/strong-weak example.
 */
class PackageGroupPatternsTest {

    @Test
    void allFormatsPatternMatchesEveryFormat() {
        PackageGroupPatterns.ParsedPattern pattern = parse("/*");

        assertEquals(STRONG, matchStrength(pattern, packagePath("npm", null, "react")));
        assertEquals(STRONG, matchStrength(pattern, packagePath("maven", "com.example", "a")));
    }

    @Test
    void oneFormatPatternMatchesOnlyThatFormat() {
        PackageGroupPatterns.ParsedPattern pattern = parse("/npm/*");

        assertEquals(STRONG, matchStrength(pattern, packagePath("npm", null, "react")));
        assertNull(matchStrength(pattern, packagePath("pypi", null, "react")));
    }

    @Test
    void namespacePrefixPatternMatchesAWordBoundaryPrefixOfTheNamespace() {
        PackageGroupPatterns.ParsedPattern pattern = parse("/maven/com.anycompany~");

        assertEquals(STRONG, matchStrength(pattern, packagePath("maven", "com.anycompany", "a")));
        assertEquals(STRONG, matchStrength(pattern, packagePath("maven", "com.anycompany.foo", "bar")));
        assertNull(matchStrength(pattern, packagePath("maven", "com.anycompanyx", "a")));
    }

    @Test
    void namespaceExactPatternMatchesAnyNameUnderThatNamespace() {
        PackageGroupPatterns.ParsedPattern pattern = parse("/npm/space/*");

        assertEquals(STRONG, matchStrength(pattern, packagePath("npm", "space", "aui.components")));
        assertEquals(STRONG, matchStrength(pattern, packagePath("npm", "space", "amplify-ui-core")));
        assertNull(matchStrength(pattern, packagePath("npm", "other", "amplify-ui-core")));
    }

    @Test
    void namePrefixPatternMatchesAWordBoundaryPrefixOfTheName() {
        PackageGroupPatterns.ParsedPattern pattern = parse("/npm/space/anycompany-ui~");

        assertEquals(STRONG, matchStrength(pattern, packagePath("npm", "space", "anycompany-ui")));
        assertEquals(STRONG, matchStrength(pattern, packagePath("npm", "space", "anycompany-ui-components")));
        assertNull(matchStrength(pattern, packagePath("npm", "space", "anycompany-uix")));
    }

    @Test
    void fullCoordinatePatternMatchesOnlyThatExactNameUnderThatExactNamespace() {
        PackageGroupPatterns.ParsedPattern pattern = parse("/maven/org.apache.logging.log4j/log4j-core$");

        assertEquals(STRONG, matchStrength(pattern, packagePath("maven", "org.apache.logging.log4j", "log4j-core")));
        assertNull(matchStrength(pattern, packagePath("maven", "org.apache.logging.log4j", "log4j-api")));
        assertNull(matchStrength(pattern, packagePath("maven", "org.apache.logging", "log4j-core")));
    }

    @Test
    void namespacelessFormatUsesABlankNamespaceSegment() {
        PackageGroupPatterns.ParsedPattern pattern = parse("/python//requests$");

        assertEquals(STRONG, matchStrength(pattern, packagePath("python", null, "requests")));
        assertNull(matchStrength(pattern, packagePath("python", null, "requests2")));
    }

    /**
     * The User Guide's own word-boundary example: {@code /npm/space/foo~} matches {@code foo} and
     * {@code foo-bar}, but not {@code food} or {@code foot}, since {@code d}/{@code t} are word
     * characters that continue the same word rather than ending it.
     */
    @Test
    void prefixMatchRespectsWordBoundariesNotJustTextPrefixes() {
        PackageGroupPatterns.ParsedPattern pattern = parse("/npm/space/foo~");

        assertEquals(STRONG, matchStrength(pattern, packagePath("npm", "space", "foo")));
        assertEquals(STRONG, matchStrength(pattern, packagePath("npm", "space", "foo-bar")));
        assertNull(matchStrength(pattern, packagePath("npm", "space", "food")));
        assertNull(matchStrength(pattern, packagePath("npm", "space", "foot")));
    }

    /**
     * The User Guide's own strong/weak example: a pattern for the exact-cased name {@code
     * AsyncStorage} strong-matches that exact casing, but only weak-matches case variations like
     * {@code asyncStorage} or {@code asyncstorage}.
     */
    @Test
    void caseVariationOfAnExactPatternIsAWeakMatchNotAStrongOne() {
        PackageGroupPatterns.ParsedPattern pattern = parse("/npm//AsyncStorage$");

        assertEquals(STRONG, matchStrength(pattern, packagePath("npm", null, "AsyncStorage")));
        assertEquals(WEAK, matchStrength(pattern, packagePath("npm", null, "asyncStorage")));
        assertEquals(WEAK, matchStrength(pattern, packagePath("npm", null, "asyncstorage")));
    }

    /**
     * The User Guide's own "Additional variations" example: {@code foo-bar}, {@code foo.bar},
     * {@code foo..bar}, and {@code foo_bar} are all weak-match equivalents of each other, but
     * {@code foobar} (no separator at all) is not.
     */
    @Test
    void weakMatchTreatsDashDotAndUnderscoreRunsAsEquivalentButNotAsAbsent() {
        PackageGroupPatterns.ParsedPattern pattern = parse("/npm//foo-bar$");

        assertEquals(STRONG, matchStrength(pattern, packagePath("npm", null, "foo-bar")));
        assertEquals(WEAK, matchStrength(pattern, packagePath("npm", null, "foo.bar")));
        assertEquals(WEAK, matchStrength(pattern, packagePath("npm", null, "foo..bar")));
        assertEquals(WEAK, matchStrength(pattern, packagePath("npm", null, "foo_bar")));
        assertNull(matchStrength(pattern, packagePath("npm", null, "foobar")));
    }

    /**
     * The User Guide's own hierarchy example: {@code /npm/*} and {@code /npm/space/*} both match
     * {@code /npm/space/aui.components}, but {@code /npm/space/*} is the proper subset (every
     * package it matches also matches {@code /npm/*}, not the reverse), making it the more
     * specific pattern and {@code /npm/*} its parent.
     */
    @Test
    void moreSpecificNamespacePatternIsAStrictDescendantOfTheFormatOnlyPattern() {
        PackageGroupPatterns.ParsedPattern formatOnly = parse("/npm/*");
        PackageGroupPatterns.ParsedPattern namespaceExact = parse("/npm/space/*");

        assertTrue(isStrictAncestor(formatOnly, namespaceExact));
        assertFalse(isStrictAncestor(namespaceExact, formatOnly));
    }

    @Test
    void namePrefixPatternIsAStrictDescendantOfItsOwnNamespaceExactPattern() {
        PackageGroupPatterns.ParsedPattern namespaceExact = parse("/npm/space/*");
        PackageGroupPatterns.ParsedPattern namePrefix = parse("/npm/space/anycompany-ui~");

        assertTrue(isStrictAncestor(namespaceExact, namePrefix));
        assertFalse(isStrictAncestor(namePrefix, namespaceExact));
    }

    @Test
    void namespacePrefixPatternIsAStrictAncestorOfAFullCoordinatePatternItReaches() {
        PackageGroupPatterns.ParsedPattern namespacePrefix = parse("/maven/com.anycompany~");
        PackageGroupPatterns.ParsedPattern fullCoordinate = parse("/maven/com.anycompany.foo/bar$");

        assertTrue(isStrictAncestor(namespacePrefix, fullCoordinate));
    }

    @Test
    void disjointPatternsAreNeitherAncestorOfTheOther() {
        PackageGroupPatterns.ParsedPattern npm = parse("/npm/*");
        PackageGroupPatterns.ParsedPattern pypi = parse("/pypi/*");

        assertFalse(isStrictAncestor(npm, pypi));
        assertFalse(isStrictAncestor(pypi, npm));
    }

    @Test
    void aPatternIsNeverItsOwnAncestor() {
        PackageGroupPatterns.ParsedPattern pattern = parse("/npm/space/*");

        assertFalse(isStrictAncestor(pattern, pattern));
    }

    /**
     * Raised by Qodo's review of this PR: a descendant pattern that only case/separator-varies
     * from its true ancestor must still be recognized as structurally contained, the same
     * normalization {@link PackageGroupPatterns#matchStrength} already applies to a package path
     * (a weakly-matched package is itself documented as "associated with" the group it weakly
     * matches, so the subset relationship behind hierarchy follows the same rule).
     */
    @Test
    void aCaseAndSeparatorVariantPatternIsStillAStrictDescendantOfItsNormalizedAncestor() {
        PackageGroupPatterns.ParsedPattern ancestor = parse("/npm/space/*");
        PackageGroupPatterns.ParsedPattern caseVariantChild = parse("/npm/SPACE/foo$");
        assertTrue(isStrictAncestor(ancestor, caseVariantChild));

        // Same word, hyphen vs. underscore: both collapse to the same normalized "left.pad".
        PackageGroupPatterns.ParsedPattern separatorAncestor = parse("/npm/left-pad/*");
        PackageGroupPatterns.ParsedPattern separatorVariantChild = parse("/npm/left_pad/foo$");
        assertTrue(isStrictAncestor(separatorAncestor, separatorVariantChild));
    }

    /** A literal match still holds exactly as before normalization was added. */
    @Test
    void anExactMatchingDescendantIsUnaffectedByNormalization() {
        PackageGroupPatterns.ParsedPattern ancestor = parse("/npm/space/*");
        PackageGroupPatterns.ParsedPattern descendant = parse("/npm/space/foo$");

        assertTrue(isStrictAncestor(ancestor, descendant));
    }

    /**
     * Guards the cycle this PR's own normalization fix above first introduced and then had to
     * close off: two same-depth patterns that tie under {@link PackageGroupPatterns
     * #compareSpecificity} once differences are normalized away (same terminal kind, same
     * normalized literal) must never both claim to be the other's strict ancestor, or {@code
     * resolveEffectiveMode}'s {@code INHERIT} walk loops forever the moment both exist in the
     * same domain. Covers the case that actually hung a test run while this PR was in review: an
     * {@code EXACT} pair differing only by case in their terminal component, and an {@code EXACT}
     * pair differing only by case in a non-terminal component with an identical terminal.
     */
    @Test
    void sameDepthTiedSpecificityPatternsAreNeverEachOthersAncestorEitherWay() {
        PackageGroupPatterns.ParsedPattern lowerFoo = parse("/npm//foo$");
        PackageGroupPatterns.ParsedPattern upperFoo = parse("/npm//Foo$");
        assertFalse(isStrictAncestor(lowerFoo, upperFoo));
        assertFalse(isStrictAncestor(upperFoo, lowerFoo));

        PackageGroupPatterns.ParsedPattern lowerSpace = parse("/npm/space/foo$");
        PackageGroupPatterns.ParsedPattern upperSpace = parse("/npm/SPACE/foo$");
        assertFalse(isStrictAncestor(lowerSpace, upperSpace));
        assertFalse(isStrictAncestor(upperSpace, lowerSpace));

        PackageGroupPatterns.ParsedPattern lowerPrefix = parse("/npm/space/foo~");
        PackageGroupPatterns.ParsedPattern upperPrefix = parse("/npm/SPACE/foo~");
        assertFalse(isStrictAncestor(lowerPrefix, upperPrefix));
        assertFalse(isStrictAncestor(upperPrefix, lowerPrefix));
    }

    @Test
    void parseRejectsAPatternWithNoLeadingSlash() {
        assertThrows(IllegalArgumentException.class, () -> parse("npm/*"));
    }

    @Test
    void parseRejectsAPatternWithNoRecognizedSuffix() {
        assertThrows(IllegalArgumentException.class, () -> parse("/npm/space/anycompany"));
    }

    @Test
    void parseRejectsDollarBeforeTheNameLevel() {
        assertThrows(IllegalArgumentException.class, () -> parse("/npm/space$"));
    }

    @Test
    void parseRejectsMoreThanThreeComponents() {
        assertThrows(IllegalArgumentException.class, () -> parse("/npm/space/name/extra$"));
    }
}
