package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class SecretsManagerJsonHandlerTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SecretsManagerService service;
    private SecretsManagerJsonHandler handler;

    @BeforeEach
    void setUp() {
        service = new SecretsManagerService(new InMemoryStorage<>(), 30);
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
    void targetAttachmentOwnershipIsNotExposedByPublicResponses() {
        ObjectNode createReq = MAPPER.createObjectNode();
        createReq.put("Name", "attached-secret");
        handler.handle("CreateSecret", createReq, REGION);
        service.claimTargetAttachment("attached-secret", "stack/Attachment", REGION);

        ObjectNode describeReq = MAPPER.createObjectNode();
        describeReq.put("SecretId", "attached-secret");
        ObjectNode described = (ObjectNode) handler
                .handle("DescribeSecret", describeReq, REGION)
                .getEntity();
        ObjectNode listed = (ObjectNode) ((ObjectNode) handler
                .handle("ListSecrets", MAPPER.createObjectNode(), REGION)
                .getEntity())
                .path("SecretList")
                .path(0);

        assertThat(described.has("targetAttachmentOwner"), is(false));
        assertThat(described.has("TargetAttachmentOwner"), is(false));
        assertThat(listed.has("targetAttachmentOwner"), is(false));
        assertThat(listed.has("TargetAttachmentOwner"), is(false));
    }

    @Test
    void owningServiceIsReportedByDescribeAndList() {
        service.createSecret("rds!db-1234", "value", null, null, null, null, "rds", REGION);

        ObjectNode describeReq = MAPPER.createObjectNode();
        describeReq.put("SecretId", "rds!db-1234");
        ObjectNode described = (ObjectNode) handler
                .handle("DescribeSecret", describeReq, REGION)
                .getEntity();
        ObjectNode listed = (ObjectNode) ((ObjectNode) handler
                .handle("ListSecrets", MAPPER.createObjectNode(), REGION)
                .getEntity())
                .path("SecretList")
                .path(0);

        assertThat(described.get("OwningService").asText(), is("rds"));
        assertThat(listed.get("OwningService").asText(), is("rds"));
    }

    @Test
    void listSecretsFiltersByOwningService() {
        service.createSecret("rds!db-1234", "value", null, null, null, null, "rds", REGION);
        ObjectNode createReq = MAPPER.createObjectNode();
        createReq.put("Name", "ordinary-secret");
        handler.handle("CreateSecret", createReq, REGION);

        ObjectNode listReq = MAPPER.createObjectNode();
        listReq.putArray("Filters").addObject().put("Key", "owning-service").putArray("Values").add("rds");
        ObjectNode owned = (ObjectNode) handler.handle("ListSecrets", listReq, REGION).getEntity();

        assertThat(owned.get("SecretList").size(), is(1));
        assertThat(owned.get("SecretList").get(0).get("Name").asText(), is("rds!db-1234"));

        // A leading "!" negates a filter, so this selects the secrets no service owns.
        ObjectNode negatedReq = MAPPER.createObjectNode();
        negatedReq.putArray("Filters").addObject().put("Key", "owning-service").putArray("Values").add("!rds");
        ObjectNode unowned = (ObjectNode) handler.handle("ListSecrets", negatedReq, REGION).getEntity();

        assertThat(unowned.get("SecretList").size(), is(1));
        assertThat(unowned.get("SecretList").get(0).get("Name").asText(), is("ordinary-secret"));
    }

    @Test
    void ownerlessSecretsOmitOwningService() {
        ObjectNode createReq = MAPPER.createObjectNode();
        createReq.put("Name", "ordinary-secret");
        handler.handle("CreateSecret", createReq, REGION);

        ObjectNode describeReq = MAPPER.createObjectNode();
        describeReq.put("SecretId", "ordinary-secret");
        ObjectNode described = (ObjectNode) handler
                .handle("DescribeSecret", describeReq, REGION)
                .getEntity();

        assertThat(described.has("OwningService"), is(false));
    }

    @Test
    void rotateServiceManagedSecretSucceedsOverTheWire() {
        service.createSecret("rds!db-5678", "value", null, null, null, null, "rds", REGION);

        // The call terraform's aws_secretsmanager_secret_rotation makes for a managed secret:
        // rotation rules, no RotationLambdaARN.
        ObjectNode rotateReq = MAPPER.createObjectNode();
        rotateReq.put("SecretId", "rds!db-5678");
        rotateReq.putObject("RotationRules").put("AutomaticallyAfterDays", 7);
        Response response = handler.handle("RotateSecret", rotateReq, REGION);

        assertThat(response.getStatus(), is(200));
        assertThat(((ObjectNode) response.getEntity()).get("Name").asText(), is("rds!db-5678"));

        ObjectNode describeReq = MAPPER.createObjectNode();
        describeReq.put("SecretId", "rds!db-5678");
        ObjectNode described = (ObjectNode) handler
                .handle("DescribeSecret", describeReq, REGION)
                .getEntity();
        assertThat(described.get("RotationEnabled").asBoolean(), is(true));
        assertThat(described.path("RotationRules").get("AutomaticallyAfterDays").asInt(), is(7));
        assertThat(described.has("RotationLambdaARN"), is(false));
    }

    @Test
    void rotateServiceManagedSecretReturnsAVersionThatResolves() {
        service.createSecret("rds!db-9012", "value", null, null, null, null, "rds", REGION);

        ObjectNode rotateReq = MAPPER.createObjectNode();
        rotateReq.put("SecretId", "rds!db-9012");
        rotateReq.putObject("RotationRules").put("AutomaticallyAfterDays", 7);
        ObjectNode rotated = (ObjectNode) handler.handle("RotateSecret", rotateReq, REGION).getEntity();

        // Nothing stages a version for the request token here, so the reported VersionId has to be
        // one a caller can actually read back.
        ObjectNode getReq = MAPPER.createObjectNode();
        getReq.put("SecretId", "rds!db-9012");
        getReq.put("VersionId", rotated.get("VersionId").asText());
        Response response = handler.handle("GetSecretValue", getReq, REGION);

        assertThat(response.getStatus(), is(200));
        assertThat(((ObjectNode) response.getEntity()).get("SecretString").asText(), is("value"));
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

    private void createSecret(String name) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Name", name);
        handler.handle("CreateSecret", req, REGION);
    }

    @Test
    void listSecretsHonorsMaxResultsAndPaginatesWithNextToken() {
        for (int i = 1; i <= 5; i++) {
            createSecret("secret-" + i);
        }

        ObjectNode pageReq = MAPPER.createObjectNode();
        pageReq.put("MaxResults", 2);
        ObjectNode page1 = (ObjectNode) handler.handle("ListSecrets", pageReq, REGION).getEntity();
        assertThat(page1.get("SecretList").size(), is(2));
        assertThat(page1.has("NextToken"), is(true));

        // Walk the remaining pages via NextToken; every secret appears exactly once.
        int total = page1.get("SecretList").size();
        String nextToken = page1.get("NextToken").asText();
        while (nextToken != null) {
            ObjectNode req = MAPPER.createObjectNode();
            req.put("MaxResults", 2);
            req.put("NextToken", nextToken);
            ObjectNode page = (ObjectNode) handler.handle("ListSecrets", req, REGION).getEntity();
            assertThat(page.get("SecretList").size(), lessThanOrEqualTo(2));
            total += page.get("SecretList").size();
            nextToken = page.has("NextToken") ? page.get("NextToken").asText() : null;
        }
        assertThat(total, is(5));
    }

    @Test
    void listSecretsWithoutMaxResultsReturnsAllAndNoNextToken() {
        for (int i = 1; i <= 3; i++) {
            createSecret("secret-" + i);
        }

        ObjectNode body = (ObjectNode) handler.handle("ListSecrets", MAPPER.createObjectNode(), REGION).getEntity();
        assertThat(body.get("SecretList").size(), is(3));
        assertThat(body.has("NextToken"), is(false));
    }

    @Test
    void listSecretsRejectsMaxResultsOutOfRange() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("MaxResults", 101);
        Response response = handler.handle("ListSecrets", req, REGION);
        assertThat(response.getStatus(), is(400));
        // ListSecrets does not model ValidationException; AWS returns InvalidParameterException.
        assertThat(((io.github.hectorvent.floci.core.common.AwsErrorResponse) response.getEntity()).type(),
                is("InvalidParameterException"));
    }

    @Test
    void listSecretsRejectsInvalidNextToken() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("NextToken", "not-a-number");
        assertThat(handler.handle("ListSecrets", req, REGION).getStatus(), is(400));
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
        assertThat(((AwsErrorResponse) response.getEntity()).message(), containsString("You must specify either SecretIdList or Filters"));
    }

    @Test
    void batchGetSecretValueMutuallyExclusiveParameters() {
        ObjectNode batchReq = MAPPER.createObjectNode();
        batchReq.putArray("SecretIdList").add("secret1");
        batchReq.putArray("Filters").addObject().put("Key", "name").putArray("Values").add("secret1");
        Response response = handler.handle("BatchGetSecretValue", batchReq, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).message(), containsString("You cannot specify both SecretIdList and Filters"));
    }

    @Test
    void batchGetSecretValueWithFilters() {
        // Create matching/non-matching secrets
        ObjectNode createReq1 = MAPPER.createObjectNode();
        createReq1.put("Name", "prod-db-url");
        createReq1.put("Description", "Production Database URL");
        createReq1.put("SecretString", "postgres://prod");
        createReq1.putArray("Tags").addObject().put("Key", "Env").put("Value", "Production");
        handler.handle("CreateSecret", createReq1, REGION);

        ObjectNode createReq2 = MAPPER.createObjectNode();
        createReq2.put("Name", "dev-db-url");
        createReq2.put("Description", "Development Database URL");
        createReq2.put("SecretString", "postgres://dev");
        createReq2.putArray("Tags").addObject().put("Key", "Env").put("Value", "Development");
        handler.handle("CreateSecret", createReq2, REGION);

        // Filter by name (begins with)
        ObjectNode filterReq = MAPPER.createObjectNode();
        filterReq.putArray("Filters").addObject().put("Key", "name").putArray("Values").add("prod");
        Response response = handler.handle("BatchGetSecretValue", filterReq, REGION);
        assertThat(response.getStatus(), is(200));
        ObjectNode body = (ObjectNode) response.getEntity();
        assertThat(body.get("SecretValues").size(), is(1));
        assertThat(body.get("SecretValues").get(0).get("Name").asText(), is("prod-db-url"));

        // Filter by description (case-insensitive)
        filterReq = MAPPER.createObjectNode();
        filterReq.putArray("Filters").addObject().put("Key", "description").putArray("Values").add("production");
        response = handler.handle("BatchGetSecretValue", filterReq, REGION);
        assertThat(response.getStatus(), is(200));
        body = (ObjectNode) response.getEntity();
        assertThat(body.get("SecretValues").size(), is(1));
        assertThat(body.get("SecretValues").get(0).get("Name").asText(), is("prod-db-url"));

        // Filter by tag-key
        filterReq = MAPPER.createObjectNode();
        filterReq.putArray("Filters").addObject().put("Key", "tag-key").putArray("Values").add("Env");
        response = handler.handle("BatchGetSecretValue", filterReq, REGION);
        assertThat(response.getStatus(), is(200));
        body = (ObjectNode) response.getEntity();
        assertThat(body.get("SecretValues").size(), is(2));

        // Filter by tag-value negation
        filterReq = MAPPER.createObjectNode();
        filterReq.putArray("Filters").addObject().put("Key", "tag-value").putArray("Values").add("!Production");
        response = handler.handle("BatchGetSecretValue", filterReq, REGION);
        assertThat(response.getStatus(), is(200));
        body = (ObjectNode) response.getEntity();
        assertThat(body.get("SecretValues").size(), is(1));
        assertThat(body.get("SecretValues").get(0).get("Name").asText(), is("dev-db-url"));
    }

    @Test
    void batchGetSecretValueWithFiltersPagination() {
        for (int i = 0; i < 5; i++) {
            ObjectNode createReq = MAPPER.createObjectNode();
            createReq.put("Name", "paged-secret-" + i);
            createReq.put("SecretString", "val-" + i);
            handler.handle("CreateSecret", createReq, REGION);
        }

        // Fetch page 1 (MaxResults = 2)
        ObjectNode filterReq = MAPPER.createObjectNode();
        filterReq.putArray("Filters").addObject().put("Key", "name").putArray("Values").add("paged-");
        filterReq.put("MaxResults", 2);
        Response response = handler.handle("BatchGetSecretValue", filterReq, REGION);
        assertThat(response.getStatus(), is(200));
        ObjectNode body = (ObjectNode) response.getEntity();
        assertThat(body.get("SecretValues").size(), is(2));
        assertThat(body.has("NextToken"), is(true));
        String nextToken = body.get("NextToken").asText();

        // Fetch page 2
        filterReq = MAPPER.createObjectNode();
        filterReq.putArray("Filters").addObject().put("Key", "name").putArray("Values").add("paged-");
        filterReq.put("MaxResults", 2);
        filterReq.put("NextToken", nextToken);
        response = handler.handle("BatchGetSecretValue", filterReq, REGION);
        assertThat(response.getStatus(), is(200));
        body = (ObjectNode) response.getEntity();
        assertThat(body.get("SecretValues").size(), is(2));
        assertThat(body.has("NextToken"), is(true));
        nextToken = body.get("NextToken").asText();

        // Fetch page 3 (remaining 1)
        filterReq = MAPPER.createObjectNode();
        filterReq.putArray("Filters").addObject().put("Key", "name").putArray("Values").add("paged-");
        filterReq.put("MaxResults", 2);
        filterReq.put("NextToken", nextToken);
        response = handler.handle("BatchGetSecretValue", filterReq, REGION);
        assertThat(response.getStatus(), is(200));
        body = (ObjectNode) response.getEntity();
        assertThat(body.get("SecretValues").size(), is(1));
        assertThat(body.has("NextToken"), is(false));
    }

    @Test
    void batchGetSecretValueRejectsNegativeNextToken() {
        // A negative offset is an invalid token, not a valid query with no results.
        ObjectNode filterReq = MAPPER.createObjectNode();
        filterReq.putArray("Filters").addObject().put("Key", "name").putArray("Values").add("any");
        filterReq.put("NextToken", "-1");
        Response response = handler.handle("BatchGetSecretValue", filterReq, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), containsString("InvalidNextTokenException"));
    }

    @Test
    void listSecretsWithFilters() {
        ObjectNode createReq1 = MAPPER.createObjectNode();
        createReq1.put("Name", "test-secret-a");
        createReq1.put("SecretString", "valA");
        handler.handle("CreateSecret", createReq1, REGION);

        ObjectNode createReq2 = MAPPER.createObjectNode();
        createReq2.put("Name", "test-secret-b");
        createReq2.put("SecretString", "valB");
        handler.handle("CreateSecret", createReq2, REGION);

        ObjectNode listReq = MAPPER.createObjectNode();
        listReq.putArray("Filters").addObject().put("Key", "name").putArray("Values").add("test-secret-a");
        Response response = handler.handle("ListSecrets", listReq, REGION);
        assertThat(response.getStatus(), is(200));
        ObjectNode body = (ObjectNode) response.getEntity();
        assertThat(body.get("SecretList").size(), is(1));
        assertThat(body.get("SecretList").get(0).get("Name").asText(), is("test-secret-a"));
    }

    @Test
    void batchGetSecretValuePartialMissingReturns200WithErrorsList() {
        String existingSecretName = "exists-secret";
        String missingSecretName = "does-not-exist";

        ObjectNode createReq = MAPPER.createObjectNode();
        createReq.put("Name", existingSecretName);
        createReq.put("SecretString", "val");
        handler.handle("CreateSecret", createReq, REGION);

        ObjectNode batchReq = MAPPER.createObjectNode();
        batchReq.putArray("SecretIdList").add(existingSecretName).add(missingSecretName);

        Response response = handler.handle("BatchGetSecretValue", batchReq, REGION);

        assertThat(response.getStatus(), is(200));
        ObjectNode body = (ObjectNode) response.getEntity();
        assertThat(body.get("SecretValues").size(), is(1));
        assertThat(body.get("SecretValues").get(0).get("Name").asText(), is(existingSecretName));
        assertThat(body.get("Errors").size(), is(1));
        assertThat(body.get("Errors").get(0).get("SecretId").asText(), is(missingSecretName));
        assertThat(body.get("Errors").get(0).get("ErrorCode").asText(), is("ResourceNotFoundException"));
    }

    @Test
    void batchGetSecretValueAllMissingReturns200WithEmptyValuesAndErrors() {
        ObjectNode batchReq = MAPPER.createObjectNode();
        batchReq.putArray("SecretIdList").add("no-such-secret-1").add("no-such-secret-2");

        Response response = handler.handle("BatchGetSecretValue", batchReq, REGION);

        assertThat(response.getStatus(), is(200));
        ObjectNode body = (ObjectNode) response.getEntity();
        assertThat(body.get("SecretValues").size(), is(0));
        assertThat(body.get("Errors").size(), is(2));
    }

    @Test
    void rotateSecretParsesRotationRules() {
        ObjectNode createReq = MAPPER.createObjectNode();
        createReq.put("Name", "rotate-test-secret");
        handler.handle("CreateSecret", createReq, REGION);

        ObjectNode rotateReq = MAPPER.createObjectNode();
        rotateReq.put("SecretId", "rotate-test-secret");
        rotateReq.put("RotationLambdaARN", "arn:aws:lambda:us-east-1:000000000000:function:rotate");
        ObjectNode rules = MAPPER.createObjectNode();
        rules.put("ScheduleExpression", "cron(0 16 ? * 2 *)");
        rotateReq.set("RotationRules", rules);

        Response response = handler.handle("RotateSecret", rotateReq, REGION);
        assertThat(response.getStatus(), is(200));
        
        ObjectNode describeReq = MAPPER.createObjectNode();
        describeReq.put("SecretId", "rotate-test-secret");
        Response describeResponse = handler.handle("DescribeSecret", describeReq, REGION);
        ObjectNode body = (ObjectNode) describeResponse.getEntity();
        assertThat(body.has("RotationRules"), is(true));
        assertThat(body.get("RotationRules").get("ScheduleExpression").asText(), is("cron(0 16 ? * 2 *)"));
        assertThat(body.get("RotationRules").has("AutomaticallyAfterDays"), is(false));
    }

    @Test
    void rotateSecretFailsWithMutuallyExclusiveRotationRules() {
        ObjectNode createReq = MAPPER.createObjectNode();
        createReq.put("Name", "rotate-test-secret-2");
        handler.handle("CreateSecret", createReq, REGION);

        ObjectNode rotateReq = MAPPER.createObjectNode();
        rotateReq.put("SecretId", "rotate-test-secret-2");
        rotateReq.put("RotationLambdaARN", "arn:aws:lambda:us-east-1:000000000000:function:rotate");
        ObjectNode rules = MAPPER.createObjectNode();
        rules.put("AutomaticallyAfterDays", 30);
        rules.put("ScheduleExpression", "cron(0 16 ? * 2 *)");
        rotateReq.set("RotationRules", rules);

        io.github.hectorvent.floci.core.common.AwsException ex = org.junit.jupiter.api.Assertions.assertThrows(
                io.github.hectorvent.floci.core.common.AwsException.class, 
                () -> handler.handle("RotateSecret", rotateReq, REGION)
        );
        assertThat(ex.getErrorCode(), is("InvalidParameterException"));
    }

    @Test
    void rotateSecretParsesAllPascalCaseRules() {
        ObjectNode createReq = MAPPER.createObjectNode();
        createReq.put("Name", "rotate-test-secret-all-pascal");
        handler.handle("CreateSecret", createReq, REGION);

        ObjectNode rotateReq = MAPPER.createObjectNode();
        rotateReq.put("SecretId", "rotate-test-secret-all-pascal");
        rotateReq.put("RotationLambdaARN", "arn:aws:lambda:us-east-1:000000000000:function:rotate");
        rotateReq.put("RotateImmediately", false);
        
        ObjectNode rules = MAPPER.createObjectNode();
        rules.put("AutomaticallyAfterDays", 45);
        rules.put("Duration", "2h");
        rotateReq.set("RotationRules", rules);

        Response response = handler.handle("RotateSecret", rotateReq, REGION);
        assertThat(response.getStatus(), is(200));

        ObjectNode describeReq = MAPPER.createObjectNode();
        describeReq.put("SecretId", "rotate-test-secret-all-pascal");
        Response describeResponse = handler.handle("DescribeSecret", describeReq, REGION);
        ObjectNode body = (ObjectNode) describeResponse.getEntity();
        
        assertThat(body.has("RotationRules"), is(true));
        assertThat(body.get("RotationRules").get("AutomaticallyAfterDays").asInt(), is(45));
        assertThat(body.get("RotationRules").get("Duration").asText(), is("2h"));
    }

    // ─── Resource policy ─────────────────────────────────────────────────────

    private static final String RESOURCE_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"secretsmanager:GetSecretValue\",\"Resource\":\"*\"}]}";

    private String createSecretReturningArn(String name) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("Name", name);
        request.put("SecretString", "value");
        Response response = handler.handle("CreateSecret", request, REGION);
        assertThat(response.getStatus(), is(200));
        return ((ObjectNode) response.getEntity()).get("ARN").asText();
    }

    @Test
    void putResourcePolicyReturnsArnAndNameAndRoundTripsThroughGet() {
        // Address the secret by full ARN: the terraform provider always sends secret_arn as
        // SecretId, and uses the ARN echoed back by PutResourcePolicy as the resource id.
        String arn = createSecretReturningArn("policy-secret");

        // A scoped principal, because BlockPublicPolicy is now enforced: the terraform provider
        // sends block_public_policy, and a wildcard principal under it is refused.
        ObjectNode put = MAPPER.createObjectNode();
        put.put("SecretId", arn);
        put.put("ResourcePolicy", SCOPED_RESOURCE_POLICY);
        put.put("BlockPublicPolicy", true);
        Response putResponse = handler.handle("PutResourcePolicy", put, REGION);
        assertThat(putResponse.getStatus(), is(200));
        ObjectNode putBody = (ObjectNode) putResponse.getEntity();
        assertThat(putBody.get("ARN").asText(), is(arn));
        assertThat(putBody.get("Name").asText(), is("policy-secret"));

        ObjectNode get = MAPPER.createObjectNode();
        get.put("SecretId", arn);
        Response getResponse = handler.handle("GetResourcePolicy", get, REGION);
        assertThat(getResponse.getStatus(), is(200));
        ObjectNode getBody = (ObjectNode) getResponse.getEntity();
        assertThat(getBody.get("ARN").asText(), is(arn));
        assertThat(getBody.get("Name").asText(), is("policy-secret"));
        assertThat(getBody.get("ResourcePolicy").asText(), is(SCOPED_RESOURCE_POLICY));
    }

    @Test
    void getResourcePolicyOmitsResourcePolicyWhenNoneAttached() {
        createSecretReturningArn("no-policy-secret");

        ObjectNode get = MAPPER.createObjectNode();
        get.put("SecretId", "no-policy-secret");
        Response response = handler.handle("GetResourcePolicy", get, REGION);
        assertThat(response.getStatus(), is(200));
        ObjectNode body = (ObjectNode) response.getEntity();
        // AWS omits the member entirely when no policy is attached; the terraform provider
        // reads the absent field as "no policy", not as an error.
        assertThat(body.has("ResourcePolicy"), is(false));
        assertThat(body.get("Name").asText(), is("no-policy-secret"));
    }

    @Test
    void deleteResourcePolicyClearsPolicyAndReturnsArnAndName() {
        String arn = createSecretReturningArn("delete-policy-secret");

        ObjectNode put = MAPPER.createObjectNode();
        put.put("SecretId", arn);
        put.put("ResourcePolicy", RESOURCE_POLICY);
        assertThat(handler.handle("PutResourcePolicy", put, REGION).getStatus(), is(200));

        ObjectNode delete = MAPPER.createObjectNode();
        delete.put("SecretId", arn);
        Response deleteResponse = handler.handle("DeleteResourcePolicy", delete, REGION);
        assertThat(deleteResponse.getStatus(), is(200));
        ObjectNode deleteBody = (ObjectNode) deleteResponse.getEntity();
        assertThat(deleteBody.get("ARN").asText(), is(arn));
        assertThat(deleteBody.get("Name").asText(), is("delete-policy-secret"));

        ObjectNode get = MAPPER.createObjectNode();
        get.put("SecretId", arn);
        ObjectNode getBody = (ObjectNode) handler.handle("GetResourcePolicy", get, REGION).getEntity();
        assertThat(getBody.has("ResourcePolicy"), is(false));
    }

    @Test
    void deleteResourcePolicyWithoutPolicyAttachedSucceeds() {
        String arn = createSecretReturningArn("never-had-policy-secret");

        ObjectNode delete = MAPPER.createObjectNode();
        delete.put("SecretId", arn);
        Response response = handler.handle("DeleteResourcePolicy", delete, REGION);
        assertThat(response.getStatus(), is(200));
        assertThat(((ObjectNode) response.getEntity()).get("ARN").asText(), is(arn));
    }

    @Test
    void putResourcePolicyRejectsMissingOrEmptyPolicy() {
        String arn = createSecretReturningArn("missing-policy-secret");

        ObjectNode missing = MAPPER.createObjectNode();
        missing.put("SecretId", arn);
        Response missingResponse = handler.handle("PutResourcePolicy", missing, REGION);
        assertThat(missingResponse.getStatus(), is(400));
        assertThat(((AwsErrorResponse) missingResponse.getEntity()).type(), is("InvalidParameterException"));

        ObjectNode empty = MAPPER.createObjectNode();
        empty.put("SecretId", arn);
        empty.put("ResourcePolicy", "");
        assertThat(handler.handle("PutResourcePolicy", empty, REGION).getStatus(), is(400));
    }

    @Test
    void putResourcePolicyRejectsMalformedPolicyJson() {
        String arn = createSecretReturningArn("malformed-policy-secret");

        ObjectNode put = MAPPER.createObjectNode();
        put.put("SecretId", arn);
        put.put("ResourcePolicy", "not-a-json-policy");
        Response response = handler.handle("PutResourcePolicy", put, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("MalformedPolicyDocumentException"));
    }

    @Test
    void resourcePolicyOpsOnUnknownSecretThrowResourceNotFound() {
        for (String action : new String[] { "GetResourcePolicy", "DeleteResourcePolicy" }) {
            ObjectNode request = MAPPER.createObjectNode();
            request.put("SecretId", "missing-secret");
            io.github.hectorvent.floci.core.common.AwsException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    io.github.hectorvent.floci.core.common.AwsException.class,
                    () -> handler.handle(action, request, REGION));
            assertThat(ex.getErrorCode(), is("ResourceNotFoundException"));
        }

        ObjectNode put = MAPPER.createObjectNode();
        put.put("SecretId", "missing-secret");
        put.put("ResourcePolicy", RESOURCE_POLICY);
        io.github.hectorvent.floci.core.common.AwsException ex = org.junit.jupiter.api.Assertions.assertThrows(
                io.github.hectorvent.floci.core.common.AwsException.class,
                () -> handler.handle("PutResourcePolicy", put, REGION));
        assertThat(ex.getErrorCode(), is("ResourceNotFoundException"));
    }

    @Test
    void resourcePolicyOpsOnSecretMarkedForDeletionThrowInvalidRequest() {
        String arn = createSecretReturningArn("pending-deletion-policy-secret");
        ObjectNode deleteSecret = MAPPER.createObjectNode();
        deleteSecret.put("SecretId", arn);
        assertThat(handler.handle("DeleteSecret", deleteSecret, REGION).getStatus(), is(200));

        // The message substring is a compatibility contract: the terraform provider matches
        // "marked for deletion" on GetResourcePolicy to treat the policy as gone.
        for (String action : new String[] { "GetResourcePolicy", "DeleteResourcePolicy" }) {
            ObjectNode request = MAPPER.createObjectNode();
            request.put("SecretId", arn);
            io.github.hectorvent.floci.core.common.AwsException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    io.github.hectorvent.floci.core.common.AwsException.class,
                    () -> handler.handle(action, request, REGION));
            assertThat(ex.getErrorCode(), is("InvalidRequestException"));
            assertThat(ex.getMessage(), containsString("marked for deletion"));
        }

        ObjectNode put = MAPPER.createObjectNode();
        put.put("SecretId", arn);
        put.put("ResourcePolicy", RESOURCE_POLICY);
        io.github.hectorvent.floci.core.common.AwsException ex = org.junit.jupiter.api.Assertions.assertThrows(
                io.github.hectorvent.floci.core.common.AwsException.class,
                () -> handler.handle("PutResourcePolicy", put, REGION));
        assertThat(ex.getErrorCode(), is("InvalidRequestException"));
        assertThat(ex.getMessage(), containsString("marked for deletion"));
    }

    // ─── CancelRotateSecret ────────────────────────────────────────────────────

    private static final String LAMBDA_ARN =
            "arn:aws:lambda:us-east-1:000000000000:function:rotate";

    /**
     * Turns rotation on without starting one: with no rotation Lambda wired into the service
     * under test and no AWSPENDING version staged, the {@code RotateImmediately: false} path
     * runs its background task as a no-op, so the stored state is settled when this returns.
     */
    private void enableRotation(String secretName) {
        ObjectNode rotate = MAPPER.createObjectNode();
        rotate.put("SecretId", secretName);
        rotate.put("RotationLambdaARN", LAMBDA_ARN);
        rotate.put("RotateImmediately", false);
        ObjectNode rules = rotate.putObject("RotationRules");
        rules.put("AutomaticallyAfterDays", 14);
        assertThat(handler.handle("RotateSecret", rotate, REGION).getStatus(), is(200));
    }

    private ObjectNode describe(String secretName) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", secretName);
        return (ObjectNode) handler.handle("DescribeSecret", req, REGION).getEntity();
    }

    @Test
    void cancelRotateSecretTurnsRotationOffAndReturnsArnAndName() {
        createSecret("cancel-me");
        enableRotation("cancel-me");
        assertThat(describe("cancel-me").get("RotationEnabled").asBoolean(), is(true));

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "cancel-me");
        Response response = handler.handle("CancelRotateSecret", req, REGION);

        assertThat(response.getStatus(), is(200));
        ObjectNode body = (ObjectNode) response.getEntity();
        assertThat(body.get("Name").asText(), is("cancel-me"));
        assertThat(body.get("ARN").asText(), startsWith("arn:aws:secretsmanager:"));
        assertThat(describe("cancel-me").get("RotationEnabled").asBoolean(), is(false));
    }

    @Test
    void cancelRotateSecretKeepsTheRotationRulesForLaterReEnabling() {
        // AWS: "If the secret previously had rotation turned on, but it is now turned off, this
        // field shows the previous rotation schedule and rotation function."
        createSecret("cancel-keeps-rules");
        enableRotation("cancel-keeps-rules");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "cancel-keeps-rules");
        handler.handle("CancelRotateSecret", req, REGION);

        ObjectNode described = describe("cancel-keeps-rules");
        assertThat(described.get("RotationEnabled").asBoolean(), is(false));
        assertThat(described.get("RotationLambdaARN").asText(), is(LAMBDA_ARN));
        assertThat(described.get("RotationRules").get("AutomaticallyAfterDays").asInt(), is(14));
    }

    @Test
    void cancelRotateSecretOmitsVersionIdWhenNoRotationIsInProgress() {
        createSecret("cancel-no-pending");
        enableRotation("cancel-no-pending");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "cancel-no-pending");
        ObjectNode body = (ObjectNode) handler.handle("CancelRotateSecret", req, REGION).getEntity();

        assertThat(body.has("VersionId"), is(false));
    }

    @Test
    void cancelRotateSecretReportsThePartiallyCreatedPendingVersion() {
        // AWS returns the id of the version the cancelled rotation created so the caller can
        // strip AWSPENDING from it; a stale AWSPENDING blocks every future rotation.
        createSecret("cancel-with-pending");
        String pendingToken = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        service.putSecretValue("cancel-with-pending", "half-rotated", null,
                pendingToken, REGION, java.util.List.of("AWSPENDING"));

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "cancel-with-pending");
        ObjectNode body = (ObjectNode) handler.handle("CancelRotateSecret", req, REGION).getEntity();

        assertThat(body.get("VersionId").asText(), is(pendingToken));
    }

    @Test
    void cancelRotateSecretOnAServiceManagedSecretThrows() {
        // Outside callers can't turn off rotation the owning service performs. Enforced on the
        // wire rather than in the service, so RDS can still manage its own secret internally.
        service.createSecret("rds!db-managed", "value", null, null, null, null, "rds", REGION);

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "rds!db-managed");
        assertThat(expectAwsException("CancelRotateSecret", req).getErrorCode(),
                is("InvalidRequestException"));
    }

    @Test
    void cancelRotateSecretOnUnknownSecretThrowsResourceNotFound() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "nope");
        io.github.hectorvent.floci.core.common.AwsException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        io.github.hectorvent.floci.core.common.AwsException.class,
                        () -> handler.handle("CancelRotateSecret", req, REGION));
        assertThat(ex.getErrorCode(), is("ResourceNotFoundException"));
    }

    // ─── DeleteSecret recovery-window validation ───────────────────────────────

    private void assertDeleteSecretRejected(ObjectNode request) {
        io.github.hectorvent.floci.core.common.AwsException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        io.github.hectorvent.floci.core.common.AwsException.class,
                        () -> handler.handle("DeleteSecret", request, REGION));
        assertThat(ex.getErrorCode(), is("InvalidParameterException"));
    }

    @Test
    void deleteSecretRejectsRecoveryWindowBelowSevenDays() {
        createSecret("short-window");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "short-window");
        req.put("RecoveryWindowInDays", 6);
        assertDeleteSecretRejected(req);
    }

    @Test
    void deleteSecretRejectsRecoveryWindowAboveThirtyDays() {
        createSecret("long-window");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "long-window");
        req.put("RecoveryWindowInDays", 31);
        assertDeleteSecretRejected(req);
    }

    @Test
    void deleteSecretRejectsRecoveryWindowOfZero() {
        // Zero used to be accepted as a synonym for ForceDeleteWithoutRecovery; AWS rejects it.
        createSecret("zero-window");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "zero-window");
        req.put("RecoveryWindowInDays", 0);
        assertDeleteSecretRejected(req);
    }

    @Test
    void deleteSecretRejectsForceDeleteCombinedWithRecoveryWindow() {
        createSecret("both-params");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "both-params");
        req.put("RecoveryWindowInDays", 7);
        req.put("ForceDeleteWithoutRecovery", true);
        assertDeleteSecretRejected(req);
    }

    @Test
    void deleteSecretAcceptsTheBoundaryRecoveryWindows() {
        for (int window : new int[] { 7, 30 }) {
            String name = "boundary-" + window;
            createSecret(name);
            ObjectNode req = MAPPER.createObjectNode();
            req.put("SecretId", name);
            req.put("RecoveryWindowInDays", window);
            Response response = handler.handle("DeleteSecret", req, REGION);
            assertThat(response.getStatus(), is(200));
            assertThat(((ObjectNode) response.getEntity()).has("DeletionDate"), is(true));
        }
    }

    @Test
    void deleteSecretStillForceDeletesWithoutARecoveryWindow() {
        createSecret("force-me");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "force-me");
        req.put("ForceDeleteWithoutRecovery", true);
        assertThat(handler.handle("DeleteSecret", req, REGION).getStatus(), is(200));

        ObjectNode list = (ObjectNode) handler.handle("ListSecrets", MAPPER.createObjectNode(), REGION).getEntity();
        assertThat(list.get("SecretList").size(), is(0));
    }

    // ─── ListSecrets fidelity ──────────────────────────────────────────────────

    private ObjectNode listSecrets(ObjectNode request) {
        Response response = handler.handle("ListSecrets", request, REGION);
        assertThat(response.getStatus(), is(200));
        return (ObjectNode) response.getEntity();
    }

    @Test
    void listSecretsIncludesVersionStagesAndRotationFields() {
        createSecret("rich-entry");
        enableRotation("rich-entry");

        ObjectNode entry = (ObjectNode) listSecrets(MAPPER.createObjectNode()).get("SecretList").get(0);

        assertThat(entry.get("RotationLambdaARN").asText(), is(LAMBDA_ARN));
        assertThat(entry.get("RotationRules").get("AutomaticallyAfterDays").asInt(), is(14));
        // SecretVersionsToStages appears in AWS's own ListSecrets example response.
        ObjectNode stages = (ObjectNode) entry.get("SecretVersionsToStages");
        assertThat(stages.size(), is(1));
        assertThat(stages.elements().next().get(0).asText(), is("AWSCURRENT"));
        assertThat(entry.has("NextRotationDate"), is(true));
    }

    @Test
    void listSecretsOmitsSecretsPendingDeletionByDefault() {
        createSecret("staying");
        createSecret("going");
        ObjectNode delete = MAPPER.createObjectNode();
        delete.put("SecretId", "going");
        handler.handle("DeleteSecret", delete, REGION);

        ObjectNode list = listSecrets(MAPPER.createObjectNode());
        assertThat(list.get("SecretList").size(), is(1));
        assertThat(list.get("SecretList").get(0).get("Name").asText(), is("staying"));
    }

    @Test
    void listSecretsIncludesPlannedDeletionWhenAsked() {
        createSecret("staying");
        createSecret("going");
        ObjectNode delete = MAPPER.createObjectNode();
        delete.put("SecretId", "going");
        handler.handle("DeleteSecret", delete, REGION);

        ObjectNode req = MAPPER.createObjectNode();
        req.put("IncludePlannedDeletion", true);
        ObjectNode list = listSecrets(req);

        assertThat(list.get("SecretList").size(), is(2));
        ObjectNode deleted = null;
        for (com.fasterxml.jackson.databind.JsonNode node : list.get("SecretList")) {
            if ("going".equals(node.get("Name").asText())) {
                deleted = (ObjectNode) node;
            }
        }
        assertThat(deleted, is(notNullValue()));
        assertThat(deleted.has("DeletedDate"), is(true));
    }

    @Test
    void listSecretsSortsByNameDescending() {
        createSecret("secret-a");
        createSecret("secret-b");
        createSecret("secret-c");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SortBy", "name");
        req.put("SortOrder", "desc");
        ObjectNode list = listSecrets(req);

        assertThat(list.get("SecretList").get(0).get("Name").asText(), is("secret-c"));
        assertThat(list.get("SecretList").get(1).get("Name").asText(), is("secret-b"));
        assertThat(list.get("SecretList").get(2).get("Name").asText(), is("secret-a"));
    }

    @Test
    void listSecretsSortsByNameAscending() {
        createSecret("secret-c");
        createSecret("secret-a");
        createSecret("secret-b");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SortBy", "name");
        ObjectNode list = listSecrets(req);

        assertThat(list.get("SecretList").get(0).get("Name").asText(), is("secret-a"));
        assertThat(list.get("SecretList").get(2).get("Name").asText(), is("secret-c"));
    }

    @Test
    void listSecretsRejectsUnknownSortBy() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SortBy", "sideways");
        Response response = handler.handle("ListSecrets", req, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("InvalidParameterException"));
    }

    @Test
    void listSecretsRejectsUnknownSortOrder() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SortOrder", "sideways");
        Response response = handler.handle("ListSecrets", req, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("InvalidParameterException"));
    }

    @Test
    void listSecretsRejectsMoreThanTenFilters() {
        ObjectNode req = MAPPER.createObjectNode();
        ArrayNodeBuilder filters = new ArrayNodeBuilder(req.putArray("Filters"));
        for (int i = 0; i < 11; i++) {
            filters.addNameFilter("secret-" + i);
        }
        Response response = handler.handle("ListSecrets", req, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("InvalidParameterException"));
    }

    /** Small helper so the filter-cap test stays readable. */
    private static final class ArrayNodeBuilder {
        private final com.fasterxml.jackson.databind.node.ArrayNode array;

        ArrayNodeBuilder(com.fasterxml.jackson.databind.node.ArrayNode array) {
            this.array = array;
        }

        void addNameFilter(String value) {
            ObjectNode filter = array.addObject();
            filter.put("Key", "name");
            filter.putArray("Values").add(value);
        }
    }

    // ─── CreateSecret ClientRequestToken ───────────────────────────────────────

    @Test
    void createSecretUsesClientRequestTokenAsTheVersionId() {
        // AWS: "This value becomes the VersionId of the new version."
        String token = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Name", "tokened");
        req.put("SecretString", "value");
        req.put("ClientRequestToken", token);

        ObjectNode body = (ObjectNode) handler.handle("CreateSecret", req, REGION).getEntity();
        assertThat(body.get("VersionId").asText(), is(token));

        // The version is addressable by that id, which is what a rotation Lambda relies on.
        ObjectNode get = MAPPER.createObjectNode();
        get.put("SecretId", "tokened");
        get.put("VersionId", token);
        ObjectNode value = (ObjectNode) handler.handle("GetSecretValue", get, REGION).getEntity();
        assertThat(value.get("SecretString").asText(), is("value"));
    }

    @Test
    void createSecretWithoutClientRequestTokenStillGetsAGeneratedVersionId() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Name", "untokened");
        req.put("SecretString", "value");

        ObjectNode body = (ObjectNode) handler.handle("CreateSecret", req, REGION).getEntity();
        assertThat(body.get("VersionId").asText(), is(not(emptyString())));
    }

    // ─── CreateSecret / UpdateSecret validation ────────────────────────────────

    private io.github.hectorvent.floci.core.common.AwsException expectAwsException(
            String action, ObjectNode request) {
        return org.junit.jupiter.api.Assertions.assertThrows(
                io.github.hectorvent.floci.core.common.AwsException.class,
                () -> handler.handle(action, request, REGION));
    }

    @Test
    void createSecretRejectsBothSecretStringAndSecretBinary() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Name", "both-values");
        req.put("SecretString", "value");
        req.put("SecretBinary", "dmFsdWU=");
        assertThat(expectAwsException("CreateSecret", req).getErrorCode(), is("InvalidParameterException"));
    }

    @Test
    void createSecretRejectsNamesWithCharactersAwsDoesNotAllow() {
        // AWS allows ASCII letters, numbers and /_+=.@- only.
        for (String name : new String[] { "bad name", "bad#name", "bad$name", "bad:name" }) {
            ObjectNode req = MAPPER.createObjectNode();
            req.put("Name", name);
            req.put("SecretString", "value");
            assertThat("name " + name + " should be rejected",
                    expectAwsException("CreateSecret", req).getErrorCode(), is("InvalidParameterException"));
        }
    }

    @Test
    void createSecretAcceptsEveryCharacterClassAwsAllows() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Name", "path/to_my+secret=v1.0@prod-1");
        req.put("SecretString", "value");
        assertThat(handler.handle("CreateSecret", req, REGION).getStatus(), is(200));
    }

    @Test
    void createSecretRejectsANameLongerThan512Characters() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Name", "a".repeat(513));
        req.put("SecretString", "value");
        assertThat(expectAwsException("CreateSecret", req).getErrorCode(), is("InvalidParameterException"));
    }

    @Test
    void updateSecretOnAServiceManagedSecretThrows() {
        // AWS: "The secret is managed by another service, and you must use that service to
        // update it." RotateSecret already guarded this; UpdateSecret did not.
        service.createSecret("rds!db-managed", "value", null, null, null, null, "rds", REGION);

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "rds!db-managed");
        req.put("Description", "hijacked");
        assertThat(expectAwsException("UpdateSecret", req).getErrorCode(), is("InvalidRequestException"));
    }

    @Test
    void updateSecretRejectsBothSecretStringAndSecretBinary() {
        createSecret("update-both");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "update-both");
        req.put("SecretString", "value");
        req.put("SecretBinary", "dmFsdWU=");
        assertThat(expectAwsException("UpdateSecret", req).getErrorCode(), is("InvalidParameterException"));
    }

    @Test
    void describeSecretOmitsVersionsThatNoLongerCarryStagingLabels() {
        // AWS: "A list of the versions of the secret that have staging labels attached.
        // Versions that don't have staging labels are considered deprecated."
        createSecret("deprecating");
        String pendingToken = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        service.putSecretValue("deprecating", "pending", null, pendingToken, REGION,
                java.util.List.of("AWSPENDING"));
        service.updateSecretVersionStage("deprecating", null, pendingToken, "AWSPENDING", REGION);

        ObjectNode stages = (ObjectNode) describe("deprecating").get("VersionIdsToStages");
        assertThat(stages.has(pendingToken), is(false));
        assertThat(stages.size(), is(1));
    }

    // ─── Replication over the wire ─────────────────────────────────────────────

    private static final String REPLICA_REGION = "eu-west-1";

    private ObjectNode replicateOverWire(String name, String targetRegion, boolean force) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", name);
        ObjectNode target = req.putArray("AddReplicaRegions").addObject();
        target.put("Region", targetRegion);
        if (force) {
            req.put("ForceOverwriteReplicaSecret", true);
        }
        Response response = handler.handle("ReplicateSecretToRegions", req, REGION);
        assertThat(response.getStatus(), is(200));
        return (ObjectNode) response.getEntity();
    }

    @Test
    void replicateSecretToRegionsReturnsPrimaryArnAndReplicationStatus() {
        createSecret("wire-multi");
        ObjectNode body = replicateOverWire("wire-multi", REPLICA_REGION, false);

        assertThat(body.get("ARN").asText(), containsString(REGION));
        assertThat(body.get("ReplicationStatus").size(), is(1));
        ObjectNode status = (ObjectNode) body.get("ReplicationStatus").get(0);
        assertThat(status.get("Region").asText(), is(REPLICA_REGION));
        assertThat(status.get("Status").asText(), is("InSync"));
        assertThat(status.has("StatusMessage"), is(true));
    }

    @Test
    void describeSecretReportsReplicationStatusAndPrimaryRegion() {
        createSecret("wire-multi");
        replicateOverWire("wire-multi", REPLICA_REGION, false);

        ObjectNode primary = describe("wire-multi");
        assertThat(primary.get("PrimaryRegion").asText(), is(REGION));
        assertThat(primary.get("ReplicationStatus").size(), is(1));

        ObjectNode replicaReq = MAPPER.createObjectNode();
        replicaReq.put("SecretId", "wire-multi");
        ObjectNode replica = (ObjectNode) handler.handle("DescribeSecret", replicaReq, REPLICA_REGION).getEntity();
        // Seen from the replica, PrimaryRegion points back at the primary.
        assertThat(replica.get("PrimaryRegion").asText(), is(REGION));
        assertThat(replica.has("ReplicationStatus"), is(false));
    }

    @Test
    void createSecretReplicatesWhenAddReplicaRegionsIsGiven() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Name", "born-replicated");
        req.put("SecretString", "value");
        req.putArray("AddReplicaRegions").addObject().put("Region", REPLICA_REGION);

        ObjectNode body = (ObjectNode) handler.handle("CreateSecret", req, REGION).getEntity();
        assertThat(body.get("ReplicationStatus").size(), is(1));
        assertThat(body.get("ReplicationStatus").get(0).get("Status").asText(), is("InSync"));

        ObjectNode get = MAPPER.createObjectNode();
        get.put("SecretId", "born-replicated");
        ObjectNode value = (ObjectNode) handler.handle("GetSecretValue", get, REPLICA_REGION).getEntity();
        assertThat(value.get("SecretString").asText(), is("value"));
    }

    @Test
    void createSecretWithoutReplicasOmitsReplicationStatus() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Name", "solo");
        req.put("SecretString", "value");
        ObjectNode body = (ObjectNode) handler.handle("CreateSecret", req, REGION).getEntity();
        assertThat(body.has("ReplicationStatus"), is(false));
    }

    @Test
    void removeRegionsFromReplicationReturnsTheRemainingReplicas() {
        createSecret("wire-multi");
        replicateOverWire("wire-multi", REPLICA_REGION, false);

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "wire-multi");
        req.putArray("RemoveReplicaRegions").add(REPLICA_REGION);
        ObjectNode body = (ObjectNode) handler.handle("RemoveRegionsFromReplication", req, REGION).getEntity();

        assertThat(body.get("ReplicationStatus").size(), is(0));
        assertThat(body.get("ARN").asText(), containsString(REGION));
    }

    @Test
    void stopReplicationToReplicaReturnsThePromotedReplicaArn() {
        createSecret("wire-multi");
        replicateOverWire("wire-multi", REPLICA_REGION, false);

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "wire-multi");
        Response response = handler.handle("StopReplicationToReplica", req, REPLICA_REGION);

        assertThat(response.getStatus(), is(200));
        ObjectNode body = (ObjectNode) response.getEntity();
        assertThat(body.get("ARN").asText(), containsString(REPLICA_REGION));
    }

    @Test
    void listSecretsReportsTheReplicationStatusOfAPrimary() {
        createSecret("wire-multi");
        replicateOverWire("wire-multi", REPLICA_REGION, false);

        ObjectNode entry = (ObjectNode) listSecrets(MAPPER.createObjectNode()).get("SecretList").get(0);
        assertThat(entry.get("ReplicationStatus").size(), is(1));
        assertThat(entry.get("PrimaryRegion").asText(), is(REGION));
    }

    @Test
    void replicateSecretToRegionsRequiresAtLeastOneRegion() {
        createSecret("wire-multi");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "wire-multi");
        assertThat(expectAwsException("ReplicateSecretToRegions", req).getErrorCode(),
                is("InvalidParameterException"));
    }

    // ─── Required parameters that were silently no-ops ─────────────────────────

    @Test
    void tagResourceRequiresTags() {
        createSecret("tagless");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "tagless");
        Response response = handler.handle("TagResource", req, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("InvalidParameterException"));
    }

    @Test
    void untagResourceRequiresTagKeys() {
        createSecret("keyless");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "keyless");
        Response response = handler.handle("UntagResource", req, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("InvalidParameterException"));
    }

    // ─── BlockPublicPolicy ─────────────────────────────────────────────────────

    private Response putResourcePolicy(String secretName, String policy, Boolean blockPublic) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", secretName);
        req.put("ResourcePolicy", policy);
        if (blockPublic != null) {
            req.put("BlockPublicPolicy", blockPublic);
        }
        return handler.handle("PutResourcePolicy", req, REGION);
    }

    @Test
    void putResourcePolicyBlocksAWildcardPrincipalWhenBlockPublicPolicyIsSet() {
        createSecret("blocked-policy");
        Response response = putResourcePolicy("blocked-policy", RESOURCE_POLICY, true);

        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("PublicPolicyException"));
    }

    @Test
    void putResourcePolicyAllowsAWildcardPrincipalByDefault() {
        // AWS: "By default, public policies aren't blocked."
        createSecret("open-policy");
        assertThat(putResourcePolicy("open-policy", RESOURCE_POLICY, null).getStatus(), is(200));
        assertThat(putResourcePolicy("open-policy", RESOURCE_POLICY, false).getStatus(), is(200));
    }

    @Test
    void putResourcePolicyAcceptsAScopedPolicyWithBlockPublicPolicySet() {
        createSecret("scoped-policy");
        assertThat(putResourcePolicy("scoped-policy", SCOPED_RESOURCE_POLICY, true).getStatus(), is(200));
    }

    @Test
    void putResourcePolicyRejectsAPolicyLongerThan20480Characters() {
        createSecret("huge-policy");
        String huge = "{\"Version\":\"2012-10-17\",\"Statement\":[],\"Pad\":\"" + "a".repeat(20480) + "\"}";
        Response response = putResourcePolicy("huge-policy", huge, null);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("InvalidParameterException"));
    }

    // ─── Empty VersionStages is omitted, not sent as [] ────────────────────────

    @Test
    void getSecretValueOmitsVersionStagesForADeprecatedVersion() {
        // AWS models VersionStages with "Array Members: Minimum number of 1 item", so an empty
        // array is not a shape it ever emits - the field is simply absent.
        createSecret("deprecated-read");
        String token = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        service.putSecretValue("deprecated-read", "pending", null, token, REGION,
                java.util.List.of("AWSPENDING"));
        service.updateSecretVersionStage("deprecated-read", null, token, "AWSPENDING", REGION);

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "deprecated-read");
        req.put("VersionId", token);
        ObjectNode body = (ObjectNode) handler.handle("GetSecretValue", req, REGION).getEntity();

        assertThat(body.get("SecretString").asText(), is("pending"));
        assertThat(body.has("VersionStages"), is(false));
    }

    @Test
    void getSecretValueStillReportsStagesForACurrentVersion() {
        createSecret("current-read");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "current-read");
        ObjectNode body = (ObjectNode) handler.handle("GetSecretValue", req, REGION).getEntity();

        assertThat(body.get("VersionStages").get(0).asText(), is("AWSCURRENT"));
    }

    // ─── GetRandomPassword input caps ──────────────────────────────────────────

    @Test
    void getRandomPasswordRejectsExcludeCharactersLongerThan4096() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("ExcludeCharacters", "a".repeat(4097));
        Response response = handler.handle("GetRandomPassword", req, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("InvalidParameterException"));
    }

    // ─── BatchGetSecretValue caps ──────────────────────────────────────────────

    @Test
    void batchGetSecretValueRejectsMoreThanTwentySecretIds() {
        // AWS caps SecretIdList at 20. Returning 25 results here would let code pass against
        // floci and then fail against AWS, which is the one outcome an emulator must avoid.
        ObjectNode req = MAPPER.createObjectNode();
        ArrayNode ids = req.putArray("SecretIdList");
        for (int i = 0; i < 21; i++) {
            ids.add("secret-" + i);
        }
        Response response = handler.handle("BatchGetSecretValue", req, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("InvalidParameterException"));
    }

    @Test
    void batchGetSecretValueAcceptsExactlyTwentySecretIds() {
        ObjectNode req = MAPPER.createObjectNode();
        ArrayNode ids = req.putArray("SecretIdList");
        for (int i = 0; i < 20; i++) {
            ids.add("secret-" + i);
        }
        assertThat(handler.handle("BatchGetSecretValue", req, REGION).getStatus(), is(200));
    }

    @Test
    void batchGetSecretValueRejectsMoreThanTenFilters() {
        ObjectNode req = MAPPER.createObjectNode();
        ArrayNodeBuilder filters = new ArrayNodeBuilder(req.putArray("Filters"));
        for (int i = 0; i < 11; i++) {
            filters.addNameFilter("secret-" + i);
        }
        Response response = handler.handle("BatchGetSecretValue", req, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("InvalidParameterException"));
    }

    // ─── PutSecretValue / UpdateSecret value rules ─────────────────────────────

    @Test
    void putSecretValueRequiresOneOfSecretStringOrSecretBinary() {
        // AWS: "You must include SecretBinary or SecretString, but not both." Neither is as
        // invalid as both - it would otherwise store a valueless version.
        createSecret("needs-a-value");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "needs-a-value");
        assertThat(expectAwsException("PutSecretValue", req).getErrorCode(),
                is("InvalidParameterException"));
    }

    @Test
    void putSecretValueRejectsBothValueForms() {
        createSecret("not-both");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "not-both");
        req.put("SecretString", "text");
        req.put("SecretBinary", "YmluYXJ5");
        assertThat(expectAwsException("PutSecretValue", req).getErrorCode(),
                is("InvalidParameterException"));
    }

    @Test
    void updateSecretRejectsAClientRequestTokenThatAlreadyNamesAVersion() {
        // Unlike PutSecretValue and CreateSecret, UpdateSecret is NOT idempotent. AWS: "If you
        // call this operation with a ClientRequestToken that matches an existing version's
        // VersionId, the operation results in an error."
        String token = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        ObjectNode create = MAPPER.createObjectNode();
        create.put("Name", "update-token");
        create.put("SecretString", "v1");
        create.put("ClientRequestToken", token);
        handler.handle("CreateSecret", create, REGION);

        ObjectNode update = MAPPER.createObjectNode();
        update.put("SecretId", "update-token");
        update.put("SecretString", "v1");
        update.put("ClientRequestToken", token);
        assertThat(expectAwsException("UpdateSecret", update).getErrorCode(),
                is("ResourceExistsException"));
    }

    @Test
    void updateSecretWithAFreshClientRequestTokenCreatesThatVersion() {
        String token = "b1b2c3d4-e5f6-7890-abcd-ef1234567890";
        createSecret("update-fresh");

        ObjectNode update = MAPPER.createObjectNode();
        update.put("SecretId", "update-fresh");
        update.put("SecretString", "v2");
        update.put("ClientRequestToken", token);
        ObjectNode body = (ObjectNode) handler.handle("UpdateSecret", update, REGION).getEntity();

        assertThat(body.get("VersionId").asText(), is(token));
    }

    @Test
    void updateSecretWithAnEmptyKmsKeyIdFallsBackToTheAwsManagedKey() {
        // AWS: "If you set this to an empty string, Secrets Manager uses the AWS managed key
        // aws/secretsmanager" - and DescribeSecret omits KmsKeyId for the managed key.
        ObjectNode create = MAPPER.createObjectNode();
        create.put("Name", "kms-cleared");
        create.put("SecretString", "v1");
        create.put("KmsKeyId", "some-key");
        handler.handle("CreateSecret", create, REGION);

        ObjectNode update = MAPPER.createObjectNode();
        update.put("SecretId", "kms-cleared");
        update.put("KmsKeyId", "");
        handler.handle("UpdateSecret", update, REGION);

        assertThat(describe("kms-cleared").has("KmsKeyId"), is(false));
    }

    // ─── PrimaryRegion is a multi-region-only field ────────────────────────────

    @Test
    void describeSecretOmitsPrimaryRegionForASecretThatIsNotReplicated() {
        // AWS "only returns fields that have a value", and its DescribeSecret example - a plain
        // secret with rotation configured - carries no PrimaryRegion. The field shows up only
        // once a secret is part of a multi-region set.
        createSecret("standalone");
        assertThat(describe("standalone").has("PrimaryRegion"), is(false));
    }

    @Test
    void listSecretsOmitsPrimaryRegionForASecretThatIsNotReplicated() {
        createSecret("standalone");
        ObjectNode entry = (ObjectNode) listSecrets(MAPPER.createObjectNode()).get("SecretList").get(0);
        assertThat(entry.has("PrimaryRegion"), is(false));
    }

    // ─── RotationRules validation ──────────────────────────────────────────────

    private ObjectNode rotateRequest(String secretId, java.util.function.Consumer<ObjectNode> rules) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", secretId);
        req.put("RotationLambdaARN", LAMBDA_ARN);
        req.put("RotateImmediately", false);
        rules.accept(req.putObject("RotationRules"));
        return req;
    }

    @Test
    void rotateSecretRejectsAutomaticallyAfterDaysOutsideOneToAThousand() {
        createSecret("rot-days");
        for (int days : new int[] { 0, -1, 1001 }) {
            ObjectNode req = rotateRequest("rot-days", r -> r.put("AutomaticallyAfterDays", days));
            assertThat("days " + days, expectAwsException("RotateSecret", req).getErrorCode(),
                    is("InvalidParameterException"));
        }
    }

    @Test
    void rotateSecretAcceptsTheAutomaticallyAfterDaysBoundaries() {
        for (int days : new int[] { 1, 1000 }) {
            String name = "rot-bound-" + days;
            createSecret(name);
            ObjectNode req = rotateRequest(name, r -> r.put("AutomaticallyAfterDays", days));
            assertThat(handler.handle("RotateSecret", req, REGION).getStatus(), is(200));
        }
    }

    @Test
    void rotateSecretRejectsADurationThatIsNotHours() {
        createSecret("rot-duration");
        for (String duration : new String[] { "3", "3d", "300h", "h" }) {
            ObjectNode req = rotateRequest("rot-duration", r -> {
                r.put("AutomaticallyAfterDays", 7);
                r.put("Duration", duration);
            });
            assertThat("duration " + duration,
                    expectAwsException("RotateSecret", req).getErrorCode(), is("InvalidParameterException"));
        }
    }

    @Test
    void rotateSecretAcceptsAnHoursDuration() {
        createSecret("rot-duration-ok");
        ObjectNode req = rotateRequest("rot-duration-ok", r -> {
            r.put("AutomaticallyAfterDays", 7);
            r.put("Duration", "3h");
        });
        assertThat(handler.handle("RotateSecret", req, REGION).getStatus(), is(200));
    }

    @Test
    void rotateSecretRejectsARateFasterThanFourHours() {
        // AWS: "You can rotate a secret as often as every four hours."
        createSecret("rot-fast");
        ObjectNode req = rotateRequest("rot-fast", r -> r.put("ScheduleExpression", "rate(1 hours)"));
        assertThat(expectAwsException("RotateSecret", req).getErrorCode(), is("InvalidParameterException"));
    }

    @Test
    void rotateSecretRejectsRateUnitsSecretsManagerDoesNotSupport() {
        // Secrets Manager rate() is hours or days only, unlike EventBridge Scheduler.
        createSecret("rot-units");
        for (String expression : new String[] { "rate(30 minutes)", "rate(2 weeks)" }) {
            ObjectNode req = rotateRequest("rot-units", r -> r.put("ScheduleExpression", expression));
            assertThat("expression " + expression,
                    expectAwsException("RotateSecret", req).getErrorCode(), is("InvalidParameterException"));
        }
    }

    @Test
    void rotateSecretAcceptsAFourHourRate() {
        createSecret("rot-four");
        ObjectNode req = rotateRequest("rot-four", r -> r.put("ScheduleExpression", "rate(4 hours)"));
        assertThat(handler.handle("RotateSecret", req, REGION).getStatus(), is(200));
    }

    // ─── CreateSecret idempotency ──────────────────────────────────────────────

    @Test
    void repeatingCreateSecretWithTheSameTokenAndValueIsIgnored() {
        // AWS: "If a version with this value already exists and the version SecretString and
        // SecretBinary values are the same as those in the request, then the request is ignored."
        String token = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Name", "idempotent");
        req.put("SecretString", "value");
        req.put("ClientRequestToken", token);

        ObjectNode first = (ObjectNode) handler.handle("CreateSecret", req, REGION).getEntity();
        ObjectNode second = (ObjectNode) handler.handle("CreateSecret", req, REGION).getEntity();

        assertThat(second.get("ARN").asText(), is(first.get("ARN").asText()));
        assertThat(second.get("VersionId").asText(), is(token));
    }

    @Test
    void repeatingCreateSecretWithTheSameTokenButADifferentValueFails() {
        String token = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        ObjectNode first = MAPPER.createObjectNode();
        first.put("Name", "idempotent-conflict");
        first.put("SecretString", "value");
        first.put("ClientRequestToken", token);
        handler.handle("CreateSecret", first, REGION);

        ObjectNode second = first.deepCopy();
        second.put("SecretString", "different");
        assertThat(expectAwsException("CreateSecret", second).getErrorCode(), is("ResourceExistsException"));
    }

    @Test
    void recreatingAnExistingNameWithoutATokenStillFails() {
        createSecret("taken");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Name", "taken");
        req.put("SecretString", "value");
        assertThat(expectAwsException("CreateSecret", req).getErrorCode(), is("ResourceExistsException"));
    }

    // ─── ListSecretVersionIds ──────────────────────────────────────────────────

    /** Leaves {@code deprecated} staged nowhere, alongside the AWSCURRENT version. */
    private String stageThenDeprecate(String secretName) {
        String token = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        service.putSecretValue(secretName, "pending", null, token, REGION,
                java.util.List.of("AWSPENDING"));
        service.updateSecretVersionStage(secretName, null, token, "AWSPENDING", REGION);
        return token;
    }

    @Test
    void listSecretVersionIdsExcludesDeprecatedVersionsByDefault() {
        // AWS: "By default, versions without staging labels aren't included."
        createSecret("versioned");
        String deprecated = stageThenDeprecate("versioned");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "versioned");
        ObjectNode body = (ObjectNode) handler.handle("ListSecretVersionIds", req, REGION).getEntity();

        assertThat(body.get("Versions").size(), is(1));
        assertThat(body.get("Versions").get(0).get("VersionId").asText(), is(not(deprecated)));
    }

    @Test
    void listSecretVersionIdsIncludesDeprecatedVersionsWhenAsked() {
        createSecret("versioned");
        String deprecated = stageThenDeprecate("versioned");

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "versioned");
        req.put("IncludeDeprecated", true);
        ObjectNode body = (ObjectNode) handler.handle("ListSecretVersionIds", req, REGION).getEntity();

        assertThat(body.get("Versions").size(), is(2));
        ObjectNode deprecatedEntry = null;
        for (com.fasterxml.jackson.databind.JsonNode node : body.get("Versions")) {
            if (deprecated.equals(node.get("VersionId").asText())) {
                deprecatedEntry = (ObjectNode) node;
            }
        }
        assertThat(deprecatedEntry, is(notNullValue()));
        // AWS's example omits VersionStages entirely for a version that has none.
        assertThat(deprecatedEntry.has("VersionStages"), is(false));
    }

    @Test
    void listSecretVersionIdsPaginates() {
        createSecret("versioned");
        for (int i = 1; i <= 3; i++) {
            service.putSecretValue("versioned", "v" + i, null, null, REGION,
                    java.util.List.of("stage-" + i));
        }

        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "versioned");
        req.put("MaxResults", 2);
        ObjectNode page1 = (ObjectNode) handler.handle("ListSecretVersionIds", req, REGION).getEntity();
        assertThat(page1.get("Versions").size(), is(2));
        assertThat(page1.has("NextToken"), is(true));

        ObjectNode next = MAPPER.createObjectNode();
        next.put("SecretId", "versioned");
        next.put("MaxResults", 2);
        next.put("NextToken", page1.get("NextToken").asText());
        ObjectNode page2 = (ObjectNode) handler.handle("ListSecretVersionIds", next, REGION).getEntity();
        assertThat(page2.get("Versions").size(), is(2));
        assertThat(page2.has("NextToken"), is(false));
    }

    @Test
    void listSecretVersionIdsRejectsMaxResultsOutOfRange() {
        createSecret("versioned");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "versioned");
        req.put("MaxResults", 101);
        Response response = handler.handle("ListSecretVersionIds", req, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("InvalidParameterException"));
    }

    // ─── ValidateResourcePolicy ────────────────────────────────────────────────

    /** Named principal, unlike {@code RESOURCE_POLICY}, which deliberately uses a wildcard. */
    private static final String SCOPED_RESOURCE_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
            "Principal":{"AWS":"arn:aws:iam::123456789012:root"},\
            "Action":"secretsmanager:GetSecretValue","Resource":"*"}]}""";

    @Test
    void validateResourcePolicyPassesAScopedPolicy() {
        createSecret("policy-target");
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "policy-target");
        req.put("ResourcePolicy", SCOPED_RESOURCE_POLICY);

        Response response = handler.handle("ValidateResourcePolicy", req, REGION);
        assertThat(response.getStatus(), is(200));
        ObjectNode body = (ObjectNode) response.getEntity();
        assertThat(body.get("PolicyValidationPassed").asBoolean(), is(true));
        assertThat(body.get("ValidationErrors").size(), is(0));
    }

    @Test
    void validateResourcePolicyFailsAWildcardPrincipal() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("ResourcePolicy", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
                "Principal":"*","Action":"secretsmanager:GetSecretValue","Resource":"*"}]}""");

        ObjectNode body = (ObjectNode) handler.handle("ValidateResourcePolicy", req, REGION).getEntity();
        assertThat(body.get("PolicyValidationPassed").asBoolean(), is(false));
        assertThat(body.get("ValidationErrors").size(), greaterThan(0));
        assertThat(body.get("ValidationErrors").get(0).get("CheckName").asText(),
                is("REFLEXIVE_PRINCIPAL_CHECK"));
    }

    @Test
    void validateResourcePolicyFailsAWildcardAwsPrincipal() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("ResourcePolicy", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
                "Principal":{"AWS":"*"},"Action":"secretsmanager:GetSecretValue","Resource":"*"}]}""");

        ObjectNode body = (ObjectNode) handler.handle("ValidateResourcePolicy", req, REGION).getEntity();
        assertThat(body.get("PolicyValidationPassed").asBoolean(), is(false));
    }

    @Test
    void validateResourcePolicyRejectsMalformedJson() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("ResourcePolicy", "not json at all");
        Response response = handler.handle("ValidateResourcePolicy", req, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("MalformedPolicyDocumentException"));
    }

    @Test
    void validateResourcePolicyRequiresAPolicy() {
        ObjectNode req = MAPPER.createObjectNode();
        Response response = handler.handle("ValidateResourcePolicy", req, REGION);
        assertThat(response.getStatus(), is(400));
        assertThat(((AwsErrorResponse) response.getEntity()).type(), is("InvalidParameterException"));
    }

    @Test
    void validateResourcePolicyOnAnUnknownSecretThrowsResourceNotFound() {
        // SecretId is optional, but when given it must resolve.
        ObjectNode req = MAPPER.createObjectNode();
        req.put("SecretId", "nope");
        req.put("ResourcePolicy", SCOPED_RESOURCE_POLICY);
        assertThat(expectAwsException("ValidateResourcePolicy", req).getErrorCode(),
                is("ResourceNotFoundException"));
    }

    @Test
    void createSecretRejectsAClientRequestTokenOutsideThe32To64Range() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Name", "bad-token");
        req.put("SecretString", "value");
        req.put("ClientRequestToken", "too-short");

        io.github.hectorvent.floci.core.common.AwsException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        io.github.hectorvent.floci.core.common.AwsException.class,
                        () -> handler.handle("CreateSecret", req, REGION));
        assertThat(ex.getErrorCode(), is("InvalidParameterException"));
    }
}
