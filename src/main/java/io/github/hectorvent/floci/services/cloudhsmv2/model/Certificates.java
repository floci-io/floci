package io.github.hectorvent.floci.services.cloudhsmv2.model;

/**
 * Certificate bundle for a CloudHSM v2 cluster.
 *
 * <p>Maps to the AWS {@code Certificates} structure returned in {@code DescribeClusters}.
 * The CSR is generated at cluster creation; the remaining fields are populated during
 * initialization.
 */
public class Certificates {

    private String clusterCsr;
    private String hsmCertificate;
    private String awsHardwareCertificate;
    private String manufacturerHardwareCertificate;
    private String clusterCertificate;

    public String getClusterCsr() {
        return clusterCsr;
    }

    public void setClusterCsr(String clusterCsr) {
        this.clusterCsr = clusterCsr;
    }

    public String getHsmCertificate() {
        return hsmCertificate;
    }

    public void setHsmCertificate(String hsmCertificate) {
        this.hsmCertificate = hsmCertificate;
    }

    public String getAwsHardwareCertificate() {
        return awsHardwareCertificate;
    }

    public void setAwsHardwareCertificate(String awsHardwareCertificate) {
        this.awsHardwareCertificate = awsHardwareCertificate;
    }

    public String getManufacturerHardwareCertificate() {
        return manufacturerHardwareCertificate;
    }

    public void setManufacturerHardwareCertificate(String manufacturerHardwareCertificate) {
        this.manufacturerHardwareCertificate = manufacturerHardwareCertificate;
    }

    public String getClusterCertificate() {
        return clusterCertificate;
    }

    public void setClusterCertificate(String clusterCertificate) {
        this.clusterCertificate = clusterCertificate;
    }
}
