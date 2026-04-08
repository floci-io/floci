package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class SecretsManagerJsonHandlerTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SecretsManagerJsonHandler handler;

    @BeforeEach
    void setUp() {
        SecretsManagerService service = new SecretsManagerService(new InMemoryStorage<>(), 30);
        handler = new SecretsManagerJsonHandler(service, MAPPER);
    }

    private String getRandomPassword(ObjectNode request) {
        Response response = handler.handle("GetRandomPassword", request, REGION);
        assertThat(response.getStatus(), is(200));
        return ((ObjectNode) response.getEntity()).get("RandomPassword").asText();
    }

    @Test
    void defaultLengthIs32() {
        assertThat(getRandomPassword(MAPPER.createObjectNode()), hasLength(32));
    }

    @Test
    void customLength() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("PasswordLength", 20);
        assertThat(getRandomPassword(request), hasLength(20));
    }

    @Test
    void lengthAbove4096Returns400() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("PasswordLength", 4097);
        assertThat(handler.handle("GetRandomPassword", request, REGION).getStatus(), is(400));
    }

    @Test
    void lengthBelowOneReturns400() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("PasswordLength", 0);
        assertThat(handler.handle("GetRandomPassword", request, REGION).getStatus(), is(400));
    }

    @Test
    void excludeLowercase() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("ExcludeLowercase", true);
        assertThat(getRandomPassword(request), not(matchesPattern(".*[a-z].*")));
    }

    @Test
    void excludeUppercase() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("ExcludeUppercase", true);
        assertThat(getRandomPassword(request), not(matchesPattern(".*[A-Z].*")));
    }

    @Test
    void excludeNumbers() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("ExcludeNumbers", true);
        assertThat(getRandomPassword(request), not(matchesPattern(".*[0-9].*")));
    }

    @Test
    void excludePunctuation() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("ExcludePunctuation", true);
        assertThat(getRandomPassword(request), not(matchesPattern(".*[!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~].*")));
    }

    @Test
    void includeSpace() {
        // Only spaces are possible, so every char must be a space
        ObjectNode request = MAPPER.createObjectNode();
        request.put("IncludeSpace", true);
        request.put("ExcludeLowercase", true);
        request.put("ExcludeUppercase", true);
        request.put("ExcludeNumbers", true);
        request.put("ExcludePunctuation", true);
        request.put("RequireEachIncludedType", true);
        request.put("PasswordLength", 5);
        assertThat(getRandomPassword(request), is("     "));
    }

    @Test
    void excludeCharacters() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("ExcludeCharacters", "aeiouAEIOU");
        assertThat(getRandomPassword(request), not(matchesPattern(".*[aeiouAEIOU].*")));
    }

    @Test
    void requireEachIncludedTypeDefaultsTrue() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("PasswordLength", 100);
        String password = getRandomPassword(request);
        assertThat(password, matchesPattern(".*[a-z].*"));
        assertThat(password, matchesPattern(".*[A-Z].*"));
        assertThat(password, matchesPattern(".*[0-9].*"));
        assertThat(password, hasLength(100));
    }

    @Test
    void requireEachIncludedTypeFalse() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("ExcludeLowercase", true);
        request.put("ExcludeUppercase", true);
        request.put("ExcludePunctuation", true);
        request.put("RequireEachIncludedType", false);
        assertThat(getRandomPassword(request), matchesPattern("[0-9]+"));
    }

    @Test
    void describeSecretResponseIncludesKmsKeyId() {
        ObjectNode createReq = MAPPER.createObjectNode();
        createReq.put("Name", "kms-secret");
        createReq.put("KmsKeyId", "my-kms-key");
        handler.handle("CreateSecret", createReq, REGION);

        ObjectNode describeReq = MAPPER.createObjectNode();
        describeReq.put("SecretId", "kms-secret");
        Response response = handler.handle("DescribeSecret", describeReq, REGION);
        
        assertThat(response.getStatus(), is(200));
        ObjectNode body = (ObjectNode) response.getEntity();
        assertThat(body.get("KmsKeyId").asText(), is("my-kms-key"));
    }

    @Test
    void listSecretsResponseIncludesKmsKeyId() {
        ObjectNode createReq = MAPPER.createObjectNode();
        createReq.put("Name", "list-kms-secret");
        createReq.put("KmsKeyId", "list-kms-key");
        handler.handle("CreateSecret", createReq, REGION);

        Response response = handler.handle("ListSecrets", MAPPER.createObjectNode(), REGION);
        
        assertThat(response.getStatus(), is(200));
        ObjectNode body = (ObjectNode) response.getEntity();
        ObjectNode secret = (ObjectNode) body.get("SecretList").get(0);
        assertThat(secret.get("KmsKeyId").asText(), is("list-kms-key"));
        assertThat(secret.has("CreatedDate"), is(true));
    }

    @Test
    void batchGetSecretValue() {
        ObjectNode createReq1 = MAPPER.createObjectNode();
        createReq1.put("Name", "secret1");
        createReq1.put("SecretString", "value1");
        handler.handle("CreateSecret", createReq1, REGION);

        ObjectNode createReq2 = MAPPER.createObjectNode();
        createReq2.put("Name", "secret2");
        createReq2.put("SecretString", "value2");
        handler.handle("CreateSecret", createReq2, REGION);

        ObjectNode batchReq = MAPPER.createObjectNode();
        batchReq.putArray("SecretIdList").add("secret1").add("secret2");
        Response response = handler.handle("BatchGetSecretValue", batchReq, REGION);

        assertThat(response.getStatus(), is(200));
        ObjectNode body = (ObjectNode) response.getEntity();
        assertThat(body.get("SecretValues").size(), is(2));
        assertThat(body.get("SecretValues").get(0).get("Name").asText(), anyOf(is("secret1"), is("secret2")));
    }

    @Test
    void batchGetSecretValueMissingParameters() {
        ObjectNode batchReq = MAPPER.createObjectNode();
        Response response = handler.handle("BatchGetSecretValue", batchReq, REGION);
        assertThat(response.getStatus(), is(400));
    }
}
