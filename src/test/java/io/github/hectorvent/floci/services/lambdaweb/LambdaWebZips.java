package io.github.hectorvent.floci.services.lambdaweb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Code packages for tests that deploy function code, Web Function revisions among them. */
public final class LambdaWebZips {

    private LambdaWebZips() {
    }

    public static byte[] indexJs(String source) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(source.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Files whose ZIP64 fields declare these sizes over empty data. */
    public static byte[] declaring(long... sizes) {
        byte[] empty = {3, 0};
        ByteBuffer out = ByteBuffer.allocate(256 * sizes.length + 22).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer central = ByteBuffer.allocate(128 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sizes.length; i++) {
            byte[] name = ("file" + i).getBytes(StandardCharsets.UTF_8);
            int offset = out.position();
            out.putInt(0x04034b50).putShort((short) 45).putShort((short) 0).putShort((short) 8)
                    .putInt(0).putInt(0).putInt(-1).putInt(-1).putShort((short) name.length).putShort((short) 20)
                    .put(name).putShort((short) 1).putShort((short) 16).putLong(sizes[i]).putLong(empty.length)
                    .put(empty);
            central.putInt(0x02014b50).putShort((short) 45).putShort((short) 45).putShort((short) 0)
                    .putShort((short) 8).putInt(0).putInt(0).putInt(-1).putInt(-1).putShort((short) name.length)
                    .putShort((short) 20).putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(0)
                    .putInt(offset).put(name).putShort((short) 1).putShort((short) 16).putLong(sizes[i])
                    .putLong(empty.length);
        }
        int centralOffset = out.position();
        out.put(central.flip()).putInt(0x06054b50).putShort((short) 0).putShort((short) 0)
                .putShort((short) sizes.length).putShort((short) sizes.length).putInt(central.limit())
                .putInt(centralOffset).putShort((short) 0);
        return Arrays.copyOf(out.array(), out.position());
    }
}
