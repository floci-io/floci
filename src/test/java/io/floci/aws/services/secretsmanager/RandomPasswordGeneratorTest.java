package io.floci.aws.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RandomPasswordGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultGenerates32Chars() {
        String password = RandomPasswordGenerator.generate(MAPPER.createObjectNode());
        assertThat(password, hasLength(32));
    }

    @Test
    void nullNodeGenerates32Chars() {
        String password = RandomPasswordGenerator.generate(null);
        assertThat(password, hasLength(32));
    }

    @Test
    void customLength() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("PasswordLength", 64);
        assertThat(RandomPasswordGenerator.generate(node), hasLength(64));
    }

    @Test
    void lengthOfOne_withRequireEachDisabled() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("PasswordLength", 1);
        node.put("RequireEachIncludedType", false);
        assertThat(RandomPasswordGenerator.generate(node), hasLength(1));
    }

    @Test
    void lengthOfOne_withRequireEachEnabled_returnsMinimum4() {
        // RequireEachIncludedType defaults to true, forcing minimum 4 (one per char type)
        ObjectNode node = MAPPER.createObjectNode();
        node.put("PasswordLength", 1);
        assertThat(RandomPasswordGenerator.generate(node), hasLength(4));
    }

    @Test
    void lengthAbove4096Throws() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("PasswordLength", 4097);
        assertThrows(IllegalArgumentException.class, () -> RandomPasswordGenerator.generate(node));
    }

    @Test
    void lengthZeroThrows() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("PasswordLength", 0);
        assertThrows(IllegalArgumentException.class, () -> RandomPasswordGenerator.generate(node));
    }

    @Test
    void negativeLengthThrows() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("PasswordLength", -5);
        assertThrows(IllegalArgumentException.class, () -> RandomPasswordGenerator.generate(node));
    }

    @Test
    void excludeLowercase() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ExcludeLowercase", true);
        node.put("PasswordLength", 100);
        assertThat(RandomPasswordGenerator.generate(node), not(matchesPattern(".*[a-z].*")));
    }

    @Test
    void excludeUppercase() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ExcludeUppercase", true);
        node.put("PasswordLength", 100);
        assertThat(RandomPasswordGenerator.generate(node), not(matchesPattern(".*[A-Z].*")));
    }

    @Test
    void excludeNumbers() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ExcludeNumbers", true);
        node.put("PasswordLength", 100);
        assertThat(RandomPasswordGenerator.generate(node), not(matchesPattern(".*[0-9].*")));
    }

    @Test
    void excludePunctuation() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ExcludePunctuation", true);
        node.put("PasswordLength", 100);
        assertThat(RandomPasswordGenerator.generate(node),
                not(matchesPattern(".*[!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~].*")));
    }

    @Test
    void includeSpace() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("IncludeSpace", true);
        node.put("ExcludeLowercase", true);
        node.put("ExcludeUppercase", true);
        node.put("ExcludeNumbers", true);
        node.put("ExcludePunctuation", true);
        node.put("PasswordLength", 10);
        assertThat(RandomPasswordGenerator.generate(node), is("          ")); // 10 spaces
    }

    @Test
    void excludeCharacters() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ExcludeCharacters", "abcABC123");
        node.put("PasswordLength", 100);
        String password = RandomPasswordGenerator.generate(node);
        assertThat(password, not(matchesPattern(".*[abcABC123].*")));
    }

    @Test
    void requireEachIncludedTypeDefaultTrue() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("PasswordLength", 100);
        String password = RandomPasswordGenerator.generate(node);
        assertThat(password, matchesPattern(".*[a-z].*"));
        assertThat(password, matchesPattern(".*[A-Z].*"));
        assertThat(password, matchesPattern(".*[0-9].*"));
    }

    @Test
    void requireEachIncludedTypeFalse() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ExcludeLowercase", true);
        node.put("ExcludeUppercase", true);
        node.put("ExcludePunctuation", true);
        node.put("RequireEachIncludedType", false);
        node.put("PasswordLength", 32);
        assertThat(RandomPasswordGenerator.generate(node), matchesPattern("[0-9]+"));
    }

    @Test
    void emptyCharsetThrows() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ExcludeLowercase", true);
        node.put("ExcludeUppercase", true);
        node.put("ExcludeNumbers", true);
        node.put("ExcludePunctuation", true);
        assertThrows(IllegalArgumentException.class, () -> RandomPasswordGenerator.generate(node));
    }

    @Test
    void maxLength4096Works() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("PasswordLength", 4096);
        assertThat(RandomPasswordGenerator.generate(node), hasLength(4096));
    }

    @Test
    void excludeAllButDigitsWithExcludeCharacters() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ExcludeLowercase", true);
        node.put("ExcludeUppercase", true);
        node.put("ExcludePunctuation", true);
        node.put("ExcludeCharacters", "02468");
        node.put("PasswordLength", 20);
        // Only odd digits should remain: 1, 3, 5, 7, 9
        assertThat(RandomPasswordGenerator.generate(node), matchesPattern("[13579]+"));
    }
}
