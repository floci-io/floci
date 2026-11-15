package io.floci.aws.services.route53;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.route53.model.ResourceRecord;
import io.floci.aws.services.route53.model.ResourceRecordSet;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Route53MultiValueStorageTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {true, false})
    void multiValueAnswerSurvivesCopyAndStorageReload(Boolean multiValueAnswer) {
        ResourceRecordSet record = new ResourceRecordSet();
        record.setName("lb.example.test.");
        record.setType("A");
        record.setSetIdentifier("instance-1");
        record.setTtl(10L);
        record.setRecords(List.of(new ResourceRecord("192.0.2.1")));
        record.setMultiValueAnswer(multiValueAnswer);
        ResourceRecordSet copy = new ResourceRecordSet(record);
        assertEquals(multiValueAnswer, copy.getMultiValueAnswer());

        Path path = tempDir.resolve("route53-records.json");
        TypeReference<Map<String, List<ResourceRecordSet>>> type = new TypeReference<>() {};
        PersistentStorage<String, List<ResourceRecordSet>> writer = new PersistentStorage<>(path, type);
        writer.put("zone", List.of(copy));
        PersistentStorage<String, List<ResourceRecordSet>> reader = new PersistentStorage<>(path, type);
        reader.load();

        ResourceRecordSet restored = reader.get("zone").orElseThrow().getFirst();
        assertEquals(record.getName(), restored.getName());
        assertEquals(record.getSetIdentifier(), restored.getSetIdentifier());
        assertEquals(multiValueAnswer, restored.getMultiValueAnswer());
    }
}
