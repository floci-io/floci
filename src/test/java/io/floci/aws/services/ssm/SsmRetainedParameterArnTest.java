package io.floci.aws.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.ssm.model.Parameter;
import io.floci.aws.services.ssm.model.ParameterHistory;
import io.floci.aws.testing.PartitionMatrix.PartitionCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SsmRetainedParameterArnTest {

    @TempDir
    Path directory;

    @Test
    void readingLegacyFlatNameReturnsCanonicalArn() {
        InMemoryStorage<String, Parameter> parameters = new InMemoryStorage<>();
        Parameter legacy = new Parameter("example-endpoint", "retained-value", "String");
        legacy.setArn("arn:aws:ssm:eu-west-2:000000000000:parameterexample-endpoint");
        parameters.put("eu-west-2::example-endpoint", legacy);
        SsmService service = new SsmService(parameters, new InMemoryStorage<>(), 5);

        assertEquals("arn:aws:ssm:eu-west-2:000000000000:parameter/example-endpoint",
                service.getParameter("example-endpoint", "eu-west-2").getArn());
    }

    @ParameterizedTest
    @MethodSource("io.floci.aws.testing.PartitionMatrix#cases")
    void normalizationPreservesStoredPartitionRegionAndAccount(PartitionCase partitionCase) {
        String prefix = "arn:" + partitionCase.partition() + ":ssm:" + partitionCase.region() + ":123456789012:";
        InMemoryStorage<String, Parameter> parameters = new InMemoryStorage<>();
        Parameter legacy = new Parameter("endpoint", "value", "String");
        legacy.setArn(prefix + "parameterendpoint");
        parameters.put(partitionCase.region() + "::endpoint", legacy);
        SsmService service = new SsmService(parameters, new InMemoryStorage<>(), 5);

        assertEquals(prefix + "parameter/endpoint", service.getParameter("endpoint", partitionCase.region()).getArn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"endpoint", "/endpoint", "/app/endpoint"})
    void retainedStoreReadsPreserveMetadataHistoryAndFiles(String name) throws IOException {
        String region = "eu-west-2";
        String key = region + "::" + name;
        String prefix = "arn:aws:ssm:eu-west-2:000000000000:";
        String canonicalArn = prefix + SsmService.parameterResource(name);
        Path parameterFile = directory.resolve("ssm-parameters.json");
        String original = """
                {"%s":{
                  "Name":"%s","Value":"retained-value","Type":"SecureString","Version":2,
                  "LastModifiedDate":"2026-10-01T12:00:00Z","Description":"retained-description",
                  "ARN":"%sparameter%s","KeyId":"alias/retained","EncryptedValue":"retained-ciphertext",
                  "AllowedPattern":".*","Tier":"Advanced","DataType":"text","Tags":{"env":"test"},
                  "Policies":[{"Type":"Expiration","Version":"1.0","Attributes":{"Timestamp":"2030-01-01T00:00:00Z"}}]
                }}
                """.formatted(key, name, prefix, name);
        Files.writeString(parameterFile, original);
        PersistentStorage<String, Parameter> parameters = new PersistentStorage<>(parameterFile, new TypeReference<>() {});
        parameters.load();
        Parameter retained = parameters.get(key).orElseThrow();
        ParameterHistory previous = new ParameterHistory(retained);
        previous.setValue("previous-value");
        previous.setVersion(1);
        previous.setLabels(List.of("release"));
        Path historyFile = directory.resolve("ssm-history.json");
        PersistentStorage<String, List<ParameterHistory>> history = new PersistentStorage<>(historyFile,
                new TypeReference<>() {});
        history.put(key, List.of(previous, new ParameterHistory(retained)));
        String originalHistory = Files.readString(historyFile);
        SsmService service = new SsmService(parameters, history, 5);

        Parameter current = service.getParameter(name, region);
        assertEquals(canonicalArn, current.getArn());
        assertEquals("retained-value", current.getValue());
        assertEquals("SecureString", current.getType());
        assertEquals(2, current.getVersion());
        assertEquals(Instant.parse("2026-10-01T12:00:00Z"), current.getLastModifiedDate());
        assertEquals("retained-description", current.getDescription());
        assertEquals("alias/retained", current.getKeyId());
        assertEquals("retained-ciphertext", current.getEncryptedValue());
        assertEquals(".*", current.getAllowedPattern());
        assertEquals("Advanced", current.getTier());
        assertEquals("text", current.getDataType());
        assertEquals(Map.of("env", "test"), service.listTagsForResource(name, region));
        assertEquals("Expiration", current.getPolicies().getFirst().get("Type").asText());
        assertEquals(canonicalArn, service.getParameter(canonicalArn, region).getArn());
        assertEquals(canonicalArn, service.getParameters(List.of(name), true, region).getFirst().getArn());
        assertEquals(canonicalArn, service.describeParameters(region).getFirst().getArn());
        for (String selector : List.of("1", "release")) {
            Parameter selected = service.getParameter(name + ":" + selector, region);
            assertEquals(canonicalArn, selected.getArn());
            assertEquals("previous-value", selected.getValue());
            assertEquals(1, selected.getVersion());
            assertEquals(":" + selector, selected.getSelector());
        }
        if (name.startsWith("/")) {
            assertEquals(canonicalArn, service.getParametersByPath("/", true, region).getFirst().getArn());
        }
        assertEquals(List.of(1L, 2L), service.getParameterHistory(name, region).stream()
                .map(ParameterHistory::getVersion).toList());
        assertEquals(List.of("release"), service.getParameterHistory(name, region).getFirst().getLabels());
        assertEquals(original, Files.readString(parameterFile));
        assertEquals(originalHistory, Files.readString(historyFile));

        parameters.flush();
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(canonicalArn, mapper.readTree(parameterFile.toFile()).get(key).get("ARN").asText());
        parameters.load();
        assertEquals(canonicalArn, service.getParameter(name, region).getArn());
        assertEquals(2, service.getParameter(name, region).getVersion());
        assertEquals(originalHistory, Files.readString(historyFile));
    }

    @ParameterizedTest
    @CsvSource({
            "endpoint, arn:aws:ssm:eu-west-2:000000000000:parameter/endpoint",
            "endpoint, arn:aws:ssm:eu-west-2:000000000000:parameterdifferent-name",
            "endpoint, arn:aws:secretsmanager:eu-west-2:000000000000:parameterendpoint",
            "/aws/reference/secretsmanager/endpoint, arn:aws:secretsmanager:eu-west-2:000000000000:secret:endpoint-AbCdEf",
            "endpoint, malformed-arn"
    })
    void unrelatedOrCanonicalArnsAreUnchanged(String name, String arn) {
        Parameter parameter = new Parameter(name, "value", "String");
        parameter.setArn(arn);
        assertEquals(arn, parameter.getArn());
    }

    @Test
    void absentArnOrNameRemainsReadable() {
        Parameter parameter = new Parameter();
        assertNull(parameter.getArn());
        parameter.setArn("arn:aws:ssm:eu-west-2:000000000000:parameterendpoint");
        assertEquals("arn:aws:ssm:eu-west-2:000000000000:parameterendpoint", parameter.getArn());
    }
}
