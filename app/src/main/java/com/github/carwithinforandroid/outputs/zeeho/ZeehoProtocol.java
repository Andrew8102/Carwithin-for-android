package com.github.carwithinforandroid.outputs.zeeho;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 极核 PXC 帧封装，移植自 CarWithin App/Zeeho62Legacy.swift 的 TailingProtocol。 */
public final class ZeehoProtocol {

    private ZeehoProtocol() {
    }

    public static final class Control {
        public final long command;
        public final long identifier;
        public final byte[] payload;

        public Control(long command, long identifier, byte[] payload) {
            this.command = command;
            this.identifier = identifier;
            this.payload = payload;
        }
    }

    public static final class Auxiliary {
        public final int command;
        public final long token;
        public final byte[] payload;

        public Auxiliary(int command, long token, byte[] payload) {
            this.command = command;
            this.token = token;
            this.payload = payload;
        }
    }

    /** 控制帧：u32 cmd / u32 size / u32 cmd^size / u32 identifier + payload，小端。 */
    public static byte[] control(long command, long identifier, byte[] payload) {
        if (payload.length > 65536 - 16) {
            throw new IllegalArgumentException("控制帧过长");
        }
        ByteBuffer buffer = ByteBuffer.allocate(16 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        int size = 16 + payload.length;
        buffer.putInt((int) command);
        buffer.putInt(size);
        buffer.putInt((int) (command ^ size));
        buffer.putInt((int) identifier);
        buffer.put(payload);
        return buffer.array();
    }

    public static Control readControl(InputStream in) throws IOException {
        byte[] head = readFully(in, 16);
        ByteBuffer buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        long command = Integer.toUnsignedLong(buffer.getInt());
        long size = Integer.toUnsignedLong(buffer.getInt());
        long check = Integer.toUnsignedLong(buffer.getInt());
        if (size < 16 || size > 65536 || check != (command ^ size)) {
            throw new IOException(String.format("控制帧校验失败 cmd=0x%x size=%d", command, size));
        }
        long identifier = Integer.toUnsignedLong(buffer.getInt());
        return new Control(command, identifier, readFully(in, (int) size - 16));
    }

    /** 媒体控制帧：u16 cmd / u16 len / u32 token + payload，小端。 */
    public static byte[] auxiliary(int command, long token, byte[] payload) {
        if (payload.length > 8192) {
            throw new IllegalArgumentException("媒体控制帧过长");
        }
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) command);
        buffer.putShort((short) payload.length);
        buffer.putInt((int) token);
        buffer.put(payload);
        return buffer.array();
    }

    public static Auxiliary readAuxiliary(InputStream in) throws IOException {
        byte[] head = readFully(in, 8);
        ByteBuffer buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int command = Short.toUnsignedInt(buffer.getShort());
        int length = Short.toUnsignedInt(buffer.getShort());
        long token = Integer.toUnsignedLong(buffer.getInt());
        if (length > 8192) {
            throw new IOException("媒体控制帧过长: " + length);
        }
        return new Auxiliary(command, token, readFully(in, length));
    }

    /** 视频帧：首帧带 8 字节前缀，随后 u32 长度（小端）+ Annex-B H.264 AU。 */
    public static byte[] h264(byte[] accessUnit, boolean includePrefix) {
        if (accessUnit.length <= 4 || accessUnit.length > 8 * 1024 * 1024
                || accessUnit[0] != 0 || accessUnit[1] != 0
                || accessUnit[2] != 0 || accessUnit[3] != 1) {
            throw new IllegalArgumentException("H.264 Annex-B AU 无效");
        }
        ByteBuffer buffer = ByteBuffer.allocate((includePrefix ? 8 : 0) + 4 + accessUnit.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        if (includePrefix) {
            buffer.put(new byte[8]);
        }
        buffer.putInt(accessUnit.length);
        buffer.put(accessUnit);
        return buffer.array();
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(result, offset, length - offset);
            if (read < 0) {
                throw new EOFException("流提前结束");
            }
            offset += read;
        }
        return result;
    }

    /** 小工具：把小端 u16/u32 读成整数。 */
    public static int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    public static long u32(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }

    /** 拼接字节数组，避免为控制 JSON 引入额外依赖。 */
    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }
}
