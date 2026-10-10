package io.github.hectorvent.floci.services.ses.model;

/**
 * One entry of the SES V2 {@code Content.Simple.Attachments} list, with {@code RawContent} already
 * decoded from its base64 wire form. Optional members are {@code null} when the request omitted them.
 */
public record MessageAttachment(String fileName, byte[] rawContent, String contentType,
                                Disposition contentDisposition, String contentDescription,
                                String contentId, TransferEncoding contentTransferEncoding) {

    /** The {@code ContentDisposition} values AWS accepts. */
    public enum Disposition {
        ATTACHMENT,
        INLINE
    }

    /** The {@code ContentTransferEncoding} values AWS accepts. */
    public enum TransferEncoding {
        BASE64,
        QUOTED_PRINTABLE,
        SEVEN_BIT
    }

    /** The disposition the message carries; an omitted one is a regular attachment. */
    public Disposition effectiveDisposition() {
        return contentDisposition == null ? Disposition.ATTACHMENT : contentDisposition;
    }

    public int size() {
        return rawContent == null ? 0 : rawContent.length;
    }
}
