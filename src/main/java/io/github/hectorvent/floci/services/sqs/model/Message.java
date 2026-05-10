package io.github.hectorvent.floci.services.sqs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message {

    private String messageId;
    private String body;
    private Map<String, MessageAttributeValue> messageAttributes;
    private Instant sentTimestamp;
    private Instant firstReceiveTimestamp;
    private int receiveCount;
    private String md5OfBody;
    private String md5OfMessageAttributes;

    // FIFO queue fields
    private String messageGroupId;
    private String messageDeduplicationId;
    private long sequenceNumber;

    // Transient fields for visibility timeout tracking
    @JsonIgnore
    private String receiptHandle;
    @JsonIgnore
    private Instant visibleAt;

    public Message() {
        this.messageAttributes = new HashMap<>();
    }

    public Message(String body) {
        this.messageId = UUID.randomUUID().toString();
        this.body = body;
        this.messageAttributes = new HashMap<>();
        this.sentTimestamp = Instant.now();
        this.receiveCount = 0;
        this.md5OfBody = computeMd5(body);
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Map<String, MessageAttributeValue> getMessageAttributes() { return messageAttributes; }
    public void setMessageAttributes(Map<String, MessageAttributeValue> messageAttributes) { this.messageAttributes = messageAttributes; }

    public Instant getSentTimestamp() { return sentTimestamp; }
    public void setSentTimestamp(Instant sentTimestamp) { this.sentTimestamp = sentTimestamp; }

    public Instant getFirstReceiveTimestamp() { return firstReceiveTimestamp; }
    public void setFirstReceiveTimestamp(Instant firstReceiveTimestamp) { this.firstReceiveTimestamp = firstReceiveTimestamp; }

    public int getReceiveCount() { return receiveCount; }
    public void setReceiveCount(int receiveCount) { this.receiveCount = receiveCount; }

    public String getMd5OfBody() { return md5OfBody; }
    public void setMd5OfBody(String md5OfBody) { this.md5OfBody = md5OfBody; }

    public String getMd5OfMessageAttributes() { return md5OfMessageAttributes; }
    public void setMd5OfMessageAttributes(String md5OfMessageAttributes) { this.md5OfMessageAttributes = md5OfMessageAttributes; }

    public String getReceiptHandle() { return receiptHandle; }
    public void setReceiptHandle(String receiptHandle) { this.receiptHandle = receiptHandle; }

    public Instant getVisibleAt() { return visibleAt; }
    public void setVisibleAt(Instant visibleAt) { this.visibleAt = visibleAt; }

    public String getMessageGroupId() { return messageGroupId; }
    public void setMessageGroupId(String messageGroupId) { this.messageGroupId = messageGroupId; }

    public String getMessageDeduplicationId() { return messageDeduplicationId; }
    public void setMessageDeduplicationId(String messageDeduplicationId) { this.messageDeduplicationId = messageDeduplicationId; }

    public long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(long sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    @JsonIgnore
    public boolean isVisible() {
        return visibleAt == null || !Instant.now().isBefore(visibleAt);
    }

    public void updateMd5OfMessageAttributes() {
        if (messageAttributes == null || messageAttributes.isEmpty()) {
            this.md5OfMessageAttributes = null;
            return;
        }
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(bos);

            java.util.List<String> keys = new java.util.ArrayList<>(messageAttributes.keySet());
            java.util.Collections.sort(keys);

            for (String key : keys) {
                MessageAttributeValue val = messageAttributes.get(key);
                byte[] nameBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                dos.writeInt(nameBytes.length);
                dos.write(nameBytes);

                byte[] typeBytes = val.getDataType().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                dos.writeInt(typeBytes.length);
                dos.write(typeBytes);

                if (val.getBinaryValue() != null) {
                    dos.write(2); // Binary type
                    dos.writeInt(val.getBinaryValue().length);
                    dos.write(val.getBinaryValue());
                } else {
                    dos.write(1); // String or Number
                    byte[] valBytes = val.getStringValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    dos.writeInt(valBytes.length);
                    dos.write(valBytes);
                }
            }

            byte[] digest = md.digest(bos.toByteArray());
            var sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            this.md5OfMessageAttributes = sb.toString();
        } catch (Exception e) {
            this.md5OfMessageAttributes = null;
        }
    }

    private static String computeMd5(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }
}