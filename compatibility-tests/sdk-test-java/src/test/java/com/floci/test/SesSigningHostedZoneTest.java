package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ChangeBatch;
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.CreateHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.DeleteHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityResponse;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DkimStatus;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SesSigningHostedZoneTest {

    @Test
    void returnedSigningHostedZoneBuildsCnamesThatVerifyTheIdentity() throws InterruptedException {
        String domain = TestFixtures.uniqueName("compat-ses-signing-zone") + ".floci.test";
        try (SesV2Client ses = TestFixtures.sesV2Client(); Route53Client route53 = TestFixtures.route53Client()) {
            CreateEmailIdentityResponse created = ses.createEmailIdentity(CreateEmailIdentityRequest.builder()
                    .emailIdentity(domain).build());
            String zoneId = null;
            List<ResourceRecordSet> records = List.of();
            boolean published = false;
            try {
                assertThat(created.dkimAttributes().tokens()).hasSize(3);
                assertThat(created.dkimAttributes().signingHostedZone()).isNotBlank();
                GetEmailIdentityResponse fetched = ses.getEmailIdentity(GetEmailIdentityRequest.builder()
                        .emailIdentity(domain).build());
                assertThat(fetched.dkimAttributes().signingHostedZone())
                        .isEqualTo(created.dkimAttributes().signingHostedZone());
                records = created.dkimAttributes().tokens().stream().map(token -> ResourceRecordSet.builder()
                        .name(token + "._domainkey." + domain).type(RRType.CNAME).ttl(60L)
                        .resourceRecords(ResourceRecord.builder()
                                .value(token + "." + created.dkimAttributes().signingHostedZone()).build())
                        .build()).toList();
                zoneId = route53.createHostedZone(CreateHostedZoneRequest.builder().name(domain)
                        .callerReference(TestFixtures.uniqueName("ses-signing-zone")).build()).hostedZone().id();
                changeRecords(route53, zoneId, records, ChangeAction.CREATE);
                published = true;
                for (int attempt = 0; attempt < 15 && fetched.dkimAttributes().status() != DkimStatus.SUCCESS; attempt++) {
                    Thread.sleep(500L);
                    fetched = ses.getEmailIdentity(GetEmailIdentityRequest.builder().emailIdentity(domain).build());
                }
                assertThat(fetched.dkimAttributes().status()).isEqualTo(DkimStatus.SUCCESS);
                assertThat(fetched.verifiedForSendingStatus()).isTrue();
            } finally {
                if (zoneId != null) {
                    if (published) {
                        changeRecords(route53, zoneId, records, ChangeAction.DELETE);
                    }
                    route53.deleteHostedZone(DeleteHostedZoneRequest.builder().id(zoneId).build());
                }
                ses.deleteEmailIdentity(DeleteEmailIdentityRequest.builder().emailIdentity(domain).build());
            }
        }
    }

    private void changeRecords(Route53Client route53, String zoneId, List<ResourceRecordSet> records,
                               ChangeAction action) {
        List<Change> changes = records.stream().map(record -> Change.builder().action(action)
                .resourceRecordSet(record).build()).toList();
        route53.changeResourceRecordSets(ChangeResourceRecordSetsRequest.builder().hostedZoneId(zoneId)
                .changeBatch(ChangeBatch.builder().changes(changes).build()).build());
    }
}
