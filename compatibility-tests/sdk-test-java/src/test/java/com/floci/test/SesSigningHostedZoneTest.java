package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityResponse;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SesSigningHostedZoneTest {

    @Test
    void createAndGetReturnTheSameSigningHostedZone() {
        String domain = TestFixtures.uniqueName("compat-ses-signing-zone") + ".floci.test";
        try (SesV2Client ses = TestFixtures.sesV2Client()) {
            CreateEmailIdentityResponse created = ses.createEmailIdentity(CreateEmailIdentityRequest.builder()
                    .emailIdentity(domain).build());
            try {
                assertThat(created.dkimAttributes().signingHostedZone()).isNotBlank();
                GetEmailIdentityResponse fetched = ses.getEmailIdentity(GetEmailIdentityRequest.builder()
                        .emailIdentity(domain).build());
                assertThat(fetched.dkimAttributes().signingHostedZone())
                        .isEqualTo(created.dkimAttributes().signingHostedZone());
            } finally {
                ses.deleteEmailIdentity(DeleteEmailIdentityRequest.builder().emailIdentity(domain).build());
            }
        }
    }
}
