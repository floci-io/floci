package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A virtual MFA device, keyed by its serial number, which for a virtual device is its own ARN,
 * {@code arn:aws:iam::<account>:mfa/<name>}.
 *
 * <p>A device exists independently of any user: CreateVirtualMFADevice makes it unassigned, and
 * EnableMFADevice is what attaches it to one. {@code userName} and {@code enableDate} are therefore
 * both null until then, and null again after DeactivateMFADevice. That pair is exactly what
 * ListVirtualMFADevices filters on with {@code AssignmentStatus}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VirtualMfaDevice {
    private String serialNumber;
    private String deviceName;
    private String path = "/";
    /** The base32 secret, as handed out in {@code Base32StringSeed} and used to compute TOTP codes. */
    private String base32Seed;
    /** The assigned user, or null while the device is unassigned. */
    private String userName;
    /** When the device was assigned, or null while it is unassigned. */
    private Instant enableDate;
    private Instant createDate = Instant.now();
    private Map<String, String> tags = new ConcurrentHashMap<>();

    public VirtualMfaDevice() {}

    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getBase32Seed() { return base32Seed; }
    public void setBase32Seed(String base32Seed) { this.base32Seed = base32Seed; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public Instant getEnableDate() { return enableDate; }
    public void setEnableDate(Instant enableDate) { this.enableDate = enableDate; }
    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(tags);
    }

    /** Whether the device is attached to a user, which is what {@code AssignmentStatus} selects on. */
    public boolean isAssigned() {
        return userName != null;
    }
}
