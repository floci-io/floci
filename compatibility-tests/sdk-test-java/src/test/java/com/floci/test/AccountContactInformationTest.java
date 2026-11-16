package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.account.AccountClient;
import software.amazon.awssdk.services.account.model.ContactInformation;
import software.amazon.awssdk.services.account.model.GetContactInformationResponse;
import software.amazon.awssdk.services.account.model.PhoneNumberVerificationStatus;
import software.amazon.awssdk.services.account.model.ValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("AWS Account Management contact information")
class AccountContactInformationTest {

    @Test
    void putAndGetContactInformationUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Writes emulator account contact state");

        try (AccountClient account = TestFixtures.accountClient()) {
            account.putContactInformation(request -> request
                    .contactInformation(contact().companyName("Example Corp").build()));

            GetContactInformationResponse response = account.getContactInformation(request -> { });
            ContactInformation information = response.contactInformation();
            assertThat(information).isNotNull();
            assertThat(information.fullName()).isEqualTo("Jane Doe");
            assertThat(information.addressLine1()).isEqualTo("1 Main St");
            assertThat(information.addressLine2()).isNull();
            assertThat(information.city()).isEqualTo("Seattle");
            assertThat(information.stateOrRegion()).isEqualTo("WA");
            assertThat(information.postalCode()).isEqualTo("98101");
            assertThat(information.countryCode()).isEqualTo("US");
            assertThat(information.phoneNumber()).isEqualTo("+12065550100");
            assertThat(information.companyName()).isEqualTo("Example Corp");
            assertThat(response.verificationStatus()).isEqualTo(PhoneNumberVerificationStatus.NOT_SUPPORTED);
        }
    }

    @Test
    void putContactInformationSurfacesValidationExceptionThroughAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Asserts the emulator's validation message");

        try (AccountClient account = TestFixtures.accountClient()) {
            assertThatThrownBy(() -> account.putContactInformation(request -> request
                    .contactInformation(contact().phoneNumber("12065550100").build())))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("PhoneNumber");
        }
    }

    private static ContactInformation.Builder contact() {
        return ContactInformation.builder()
                .fullName("Jane Doe")
                .addressLine1("1 Main St")
                .city("Seattle")
                .stateOrRegion("WA")
                .postalCode("98101")
                .countryCode("US")
                .phoneNumber("+12065550100");
    }
}
