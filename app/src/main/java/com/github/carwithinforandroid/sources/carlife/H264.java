package com.github.carwithinforandroid.sources.carlife;

/** H.264 字节流小工具：Annex-B / AVCC 判定、起始码扫描、关键帧识别。 */
public final class H264 {

    public static final int NAL_SLICE = 1;
    public static final int NAL_IDR = 5;
    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;

    private H264() {
    }

    public static boolean isAnnexB(byte[] b) {
        if (b.length < 4) {
            return false;
        }
        return (b[0] == 0 && b[1] == 0 && b[2] == 1)
                || (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 1);
    }

    /** AVCC（4 字节长度前缀）转 Annex-B；已是 Annex-B 时原样返回。 */
    public static byte[] toAnnexB(byte[] b) {
        if (isAnnexB(b)) {
            return b;
        }
        if (b.length < 5) {
            return b;
        }
        int len0 = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        if (len0 <= 0 || len0 > b.length - 4) {
            return b;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(b.length + 16);
        int p = 0;
        while (p + 4 <= b.length) {
            int n = ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            if (n <= 0 || p + 4 + n > b.length) {
                return b;
            }
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(b, p + 4, n);
            p += 4 + n;
        }
        return out.toByteArray();
    }

    /** 帧里是否含 IDR（关键帧）。含 SPS+PPS 也视作关键帧。 */
    public static boolean hasIdr(byte[] b) {
        boolean sps = false;
        boolean pps = false;
        int p = 0;
        while (true) {
            int sc = findStartCode(b, p);
            if (sc < 0) {
                break;
            }
            int nalStart = sc + (b[sc + 2] == 1 ? 3 : 4);
            if (nalStart >= b.length) {
                break;
            }
            int type = b[nalStart] & 0x1F;
            if (type == NAL_IDR) {
                return true;
            }
            if (type == NAL_SPS) {
                sps = true;
            } else if (type == NAL_PPS) {
                pps = true;
            }
            p = nalStart + 1;
        }
        return sps && pps;
    }

    public static String describe(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int p = 0;
        int count = 0;
        while (count < 8) {
            int sc = findStartCode(b, p);
            if (sc < 0) {
                break;
            }
            int nalStart = sc + (b[sc + 2] == 1 ? 3 : 4);
            if (nalStart >= b.length) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(b[nalStart] & 0x1F);
            count++;
            p = nalStart + 1;
        }
        return sb.toString();
    }

    private static int findStartCode(byte[] b, int from) {
        for (int i = Math.max(0, from); i + 2 < b.length; i++) {
            if (b[i] == 0 && b[i + 1] == 0) {
                if (b[i + 2] == 1) {
                    return i;
                }
                if (i + 3 < b.length && b[i + 2] == 0 && b[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }
}
