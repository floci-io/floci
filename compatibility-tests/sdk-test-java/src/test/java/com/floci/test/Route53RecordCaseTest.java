package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.route53.model.Route53Exception;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class Route53RecordCaseTest {

    @Test
    void changeBatchUsesLowercaseNameIdentityAndPreservesValues() {
        try (Route53Client client = TestFixtures.route53Client()) {
            String zoneName = TestFixtures.uniqueName("record-case") + ".example.com";
            String zoneId = client.createHostedZone(request -> request.name(zoneName)
                    .callerReference(TestFixtures.uniqueName("record-case-ref"))).hostedZone().id();
            try {
                String name = "www." + zoneName + ".";
                ResourceRecordSet initial = record(name.toUpperCase(Locale.ROOT), "192.0.2.1");
                change(client, zoneId, ChangeAction.CREATE, initial);
                assertThat(records(client, zoneId)).singleElement()
                        .satisfies(actual -> assertThat(actual.name()).isEqualTo(name));
                Route53Exception duplicate = catchThrowableOfType(() -> change(client, zoneId,
                        ChangeAction.CREATE, record(name, "192.0.2.2")), Route53Exception.class);
                assertThat(duplicate).isNotNull();
                assertThat(duplicate.awsErrorDetails().errorCode()).isEqualTo("InvalidChangeBatch");
                change(client, zoneId, ChangeAction.UPSERT, record(name, "192.0.2.2"));
                assertThat(records(client, zoneId)).singleElement().satisfies(actual -> {
                    assertThat(actual.name()).isEqualTo(name);
                    assertThat(actual.resourceRecords()).extracting(ResourceRecord::value).containsExactly("192.0.2.2");
                });
                Route53Exception mismatch = catchThrowableOfType(() -> change(client, zoneId,
                        ChangeAction.DELETE, initial), Route53Exception.class);
                assertThat(mismatch).isNotNull();
                assertThat(mismatch.awsErrorDetails().errorCode()).isEqualTo("InvalidChangeBatch");
                change(client, zoneId, ChangeAction.DELETE,
                        record(name.toUpperCase(Locale.ROOT), "192.0.2.2"));
                assertThat(records(client, zoneId)).isEmpty();
            } finally {
                for (ResourceRecordSet record : records(client, zoneId)) {
                    change(client, zoneId, ChangeAction.DELETE, record);
                }
                client.deleteHostedZone(request -> request.id(zoneId));
            }
        }
    }

    private static ResourceRecordSet record(String name, String value) {
        return ResourceRecordSet.builder().name(name).type("A").ttl(60L)
                .resourceRecords(ResourceRecord.builder().value(value).build()).build();
    }

    private static List<ResourceRecordSet> records(Route53Client client, String zoneId) {
        return client.listResourceRecordSets(request -> request.hostedZoneId(zoneId))
                .resourceRecordSets().stream().filter(record -> "A".equals(record.typeAsString())).toList();
    }

    private static void change(Route53Client client, String zoneId, ChangeAction action, ResourceRecordSet record) {
        client.changeResourceRecordSets(request -> request.hostedZoneId(zoneId)
                .changeBatch(batch -> batch.changes(Change.builder().action(action).resourceRecordSet(record).build())));
    }
}
