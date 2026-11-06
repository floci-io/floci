package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.RequestLaunchTemplateData;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ec2DescribeLaunchTemplatesTest {

    @Test
    void missingNameCanTriggerCreateThenDescribe() {
        String name = "sdk-describe-" + UUID.randomUUID();
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            Ec2Exception error = assertThrows(Ec2Exception.class,
                    () -> ec2.describeLaunchTemplates(r -> r.launchTemplateNames(name)));
            assertThat(error.statusCode()).isEqualTo(400);
            assertThat(error.awsErrorDetails().errorCode())
                    .isEqualTo("InvalidLaunchTemplateName.NotFoundException");

            String id = ec2.createLaunchTemplate(r -> r.launchTemplateName(name)
                    .launchTemplateData(RequestLaunchTemplateData.builder()
                            .imageId("ami-0abcdef1234567890").build()))
                    .launchTemplate().launchTemplateId();
            try {
                assertThat(ec2.describeLaunchTemplates(r -> r.launchTemplateNames(name)).launchTemplates())
                        .singleElement().satisfies(t -> assertThat(t.launchTemplateId()).isEqualTo(id));
                assertThat(ec2.describeLaunchTemplates(r -> r.launchTemplateIds(id)).launchTemplates())
                        .singleElement().satisfies(t -> assertThat(t.launchTemplateName()).isEqualTo(name));
                Ec2Exception mixed = assertThrows(Ec2Exception.class,
                        () -> ec2.describeLaunchTemplates(r -> r.launchTemplateNames(name, name + "-missing")));
                assertThat(mixed.awsErrorDetails().errorCode())
                        .isEqualTo("InvalidLaunchTemplateName.NotFoundException");
                Ec2Exception mixedIds = assertThrows(Ec2Exception.class,
                        () -> ec2.describeLaunchTemplates(r -> r.launchTemplateIds(id, "lt-00000000000000000")));
                assertThat(mixedIds.awsErrorDetails().errorCode()).isEqualTo("InvalidLaunchTemplateId.NotFound");
                assertThat(ec2.describeLaunchTemplates(r -> r.launchTemplateNames(name)
                        .filters(f -> f.name("launch-template-name").values(name + "-missing"))).launchTemplates())
                        .isEmpty();
            } finally {
                ec2.deleteLaunchTemplate(r -> r.launchTemplateId(id));
            }
        }
    }

    @Test
    void missingIdIsAnSdkError() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            Ec2Exception error = assertThrows(Ec2Exception.class,
                    () -> ec2.describeLaunchTemplates(r -> r.launchTemplateIds("lt-00000000000000000")));
            assertThat(error.statusCode()).isEqualTo(400);
            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidLaunchTemplateId.NotFound");
        }
    }

    @Test
    void unmatchedFilterStillReturnsAnEmptyList() {
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            assertThat(ec2.describeLaunchTemplates(r -> r.filters(f -> f.name("launch-template-name")
                    .values("missing-" + UUID.randomUUID()))).launchTemplates()).isEmpty();
        }
    }
}
