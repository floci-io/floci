package io.github.hectorvent.floci.services.opensearch;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

class OpenSearchInstanceTypesTest {

    @Test
    void listAllIncludesEbsAndOr1Families() {
        assertThat(OpenSearchInstanceTypes.listAll(),
                hasItems("m5.large.search", "m6g.large.search", "m7g.large.search",
                        "or1.medium.search", "or1.2xlarge.search",
                        "i3.large.search"));
    }

    @Test
    void or1FamilyIsS3BackedAndNotEbsAttachable() {
        OpenSearchInstanceTypes.InstanceTypeSpec spec = OpenSearchInstanceTypes.specOf("or1.2xlarge.search");
        assertThat(spec, notNullValue());
        // or1 backs storage by S3 — not EBS. Real fidelity here matters for
        // SDK clients (Terraform, CDK) that reject EBS config on or1.
        assertThat(spec.storageType(), equalTo("s3-backed"));
        assertThat(spec.ebsAttachable(), is(false));
        assertThat(spec.maxVolumeSizeGib(), greaterThan(8000));
    }

    @Test
    void ebsFamilyHas3584GibCeiling() {
        // 3584 GiB ceiling matches AWS's published gp2/gp3 limit; regression
        // here would mislead cost calculators into allowing impossible volume
        // sizes.
        OpenSearchInstanceTypes.InstanceTypeSpec spec = OpenSearchInstanceTypes.specOf("m6g.2xlarge.search");
        assertThat(spec.storageType(), equalTo("ebs"));
        assertThat(spec.ebsAttachable(), is(true));
        assertThat(spec.maxVolumeSizeGib(), equalTo(3584));
    }

    @Test
    void i3FamilyHasInstanceStoreNotEbs() {
        OpenSearchInstanceTypes.InstanceTypeSpec spec = OpenSearchInstanceTypes.specOf("i3.4xlarge.search");
        assertThat(spec.storageType(), equalTo("instance-store"));
    }

    @Test
    void specOfReturnsNullForUnknownType() {
        assertThat(OpenSearchInstanceTypes.specOf("imaginary.10xlarge.search"), nullValue());
    }

    @Test
    void genericFallbackIsEbsWithStandardLimits() {
        OpenSearchInstanceTypes.InstanceTypeSpec spec = OpenSearchInstanceTypes.genericFallback("future.99xlarge.search");
        assertThat(spec.storageType(), equalTo("ebs"));
        assertThat(spec.maxVolumeSizeGib(), equalTo(3584));
        assertThat(spec.ebsAttachable(), is(true));
    }
}
