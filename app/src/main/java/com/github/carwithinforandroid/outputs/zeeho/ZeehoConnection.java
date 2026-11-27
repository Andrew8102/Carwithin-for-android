package com.github.carwithinforandroid.outputs.zeeho;

import android.util.Base64;

import com.github.carwithinforandroid.core.VideoFrame;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 极核 AE4 MAX2+ 6.2 寸四端口会话骨架。
 * 帧格式与主要命令已实现；媒体协商中的若干分支仍需真机校正，见 docs/ZEEHO-PROTOCOL.md。
 */
public final class ZeehoConnection {

    public static final int CONTROL_PORT = 10922;
    public static final int AUX_PORT = 10711;
    public static final int VIDEO_PORT = 10710;
    public static final int DISCOVERY_PORT = 10930;

    private static final int CMD_ANNOUNCE = 0x70000010;
    private static final int CMD_ANNOUNCE_ACK = 0x70000011;
    private static final int CMD_DEVICE_INFO = 0x10010;
    private static final int CMD_TIME = 0x10450;
    private static final int CMD_SESSION_START = 0x20000;
    private static final int CMD_MIRROR_START = 0x20590;
    private static final int CMD_KEEPALIVE = 0x70000000;

    private static final int AUX_CONFIG = 0x10;
    private static final int AUX_CONFIG_ACK = 0x11;
    private static final int AUX_MEDIA_START = 0x70;
    private static final int AUX_MEDIA_START_ACK = 0x71;
    private static final int AUX_PULL_FRAME = 0x72;
    private static final int AUX_PORT_INFO = 0x30;

    private final String localIp;
    private String targetIp;
    private final ZeehoIdentity identity;
    private final String packageName;
    private final int versionCode;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final List<ServerSocket> servers = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Boolean> frameRequests = new LinkedBlockingQueue<>();

    private volatile boolean closed;
    private volatile String huid;
    private volatile boolean mediaReady;
    private volatile boolean sentPrefix;
    private volatile Socket videoSocket;
    private volatile String state = "IDLE";

    public ZeehoConnection(String localIp, String targetIp, ZeehoIdentity identity,
                           String packageName, int versionCode) {
        this.localIp = localIp;
        this.targetIp = targetIp;
        this.identity = identity;
        this.packageName = packageName;
        this.versionCode = versionCode;
    }

    public void start() throws IOException, GeneralSecurityException {
        if (targetIp == null || targetIp.isEmpty()) {
            List<String> found = ZeehoDiscovery.discover(localIp, DISCOVERY_PORT);
            if (found.isEmpty()) throw new IOException("未发现仪表，请确认已连仪表 Wi-Fi 且已开启投屏");
            targetIp = found.get(0);
        }
        announce();
        listen(CONTROL_PORT, this::handleControl);
        listen(AUX_PORT, this::handleAuxiliary);
        listen(VIDEO_PORT, this::handleVideo);
        state = "WAITING_DASHBOARD";
    }

    private void announce() throws IOException, GeneralSecurityException {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(localIp, 0));
            socket.connect(new InetSocketAddress(targetIp, DISCOVERY_PORT), 2000);
            JSONObject body = new JSONObject();
            try {
                body.put("phoneType", "Android");
                body.put("packageName", packageName);
            } catch (JSONException e) {
                throw new IOException("构造投屏请求失败", e);
            }
            OutputStream out = socket.getOutputStream();
            out.write(ZeehoProtocol.control(CMD_ANNOUNCE, 0, body.toString().getBytes(StandardCharsets.UTF_8)));
            out.flush();
            ZeehoProtocol.Control reply = ZeehoProtocol.readControl(socket.getInputStream());
            if (reply.command != CMD_ANNOUNCE_ACK) {
                throw new IOException(String.format("投屏请求被拒 cmd=0x%x", reply.command));
            }
            JSONObject object;
            try {
                object = new JSONObject(new String(reply.payload, StandardCharsets.UTF_8));
            } catch (JSONException e) {
                throw new IOException("解析投屏应答失败", e);
            }
            if (!object.optBoolean("status")) {
                throw new IOException("仪表未接受投屏请求");
            }
        }
    }

    private interface SocketHandler {
        void handle(Socket socket);
    }

    private void listen(int port, SocketHandler handler) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(localIp, port));
        servers.add(server);
        pool.execute(() -> {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    pool.execute(() -> handler.handle(socket));
                } catch (IOException e) {
                    if (!closed) state = "ACCEPT_ERROR: " + e.getMessage();
                }
            }
        });
    }

    private void handleControl(Socket socket) {
        try (socket) {
            while (!closed) {
                ZeehoProtocol.Control request = ZeehoProtocol.readControl(socket.getInputStream());
                byte[] payload = new byte[0];
                if (request.command == CMD_DEVICE_INFO) {
                    JSONObject object = new JSONObject(new String(request.payload, StandardCharsets.UTF_8));
                    String receivedHuid = object.optString("HUID", "");
                    if (receivedHuid.isEmpty()) throw new IOException("仪表身份为空");
                    huid = receivedHuid;
                    byte[] signature = identity.sign(receivedHuid);
                    JSONObject reply = new JSONObject();
                    reply.put("pxcVersion", "1.0.2");
                    reply.put("phoneUUID", identity.phoneId());
                    reply.put("phoneBrand", "Android");
                    reply.put("phoneModel", "Android");
                    reply.put("phoneOsVersion", "14");
                    reply.put("phoneOs", "Android");
                    reply.put("package", packageName);
                    reply.put("versionCode", versionCode);
                    reply.put("token", 0);
                    reply.put("pubkey", Base64.encodeToString(identity.publicKeyDer(), Base64.DEFAULT));
                    reply.put("encryptedHUID", Base64.encodeToString(signature, Base64.DEFAULT));
                    reply.put("supportH264IFrame", true);
                    reply.put("supportFunction", 128);
                    reply.put("mirrorRequestReconnect", true);
                    payload = reply.toString().getBytes(StandardCharsets.UTF_8);
                } else if (request.command == CMD_TIME) {
                    long now = System.currentTimeMillis();
                    JSONObject reply = new JSONObject();
                    reply.put("time", now);
                    reply.put("currentTime", now);
                    reply.put("currentTimeZone", java.util.TimeZone.getDefault().getID());
                    payload = reply.toString().getBytes(StandardCharsets.UTF_8);
                } else if (request.command == CMD_SESSION_START) {
                    sendControl(socket, 0x204c0, 1, "{\"state\":0,\"msg\":\"\"}");
                    sendControl(socket, 0x20020, 2, "{\"mode\":2,\"width\":480,\"height\":1024,\"enableAccessibility\":false,\"enableAOAHid\":false}");
                    sendControl(socket, 0x20030, 3, "{\"mode\":1,\"width\":1024,\"height\":480,\"enableAccessibility\":false,\"enableAOAHid\":false}");
                    write(socket, ZeehoProtocol.control(0x20050, 4, new byte[0]));
                    sendControl(socket, 0x205a0, 5, "{\"isMaskVisible\":false}");
                    sendControl(socket, CMD_MIRROR_START, 6, null);
                    payload = new byte[0];
                }
                write(socket, ZeehoProtocol.control(request.command + 1, request.identifier, payload));
            }
        } catch (Exception e) {
            if (!closed) state = "CONTROL_ERROR: " + e.getMessage();
        }
    }

    private void sendControl(Socket socket, long command, int identifier, String json) throws IOException {
        byte[] payload = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        write(socket, ZeehoProtocol.control(command, identifier, payload));
    }

    private void handleAuxiliary(Socket socket) {
        try (socket) {
            while (!closed) {
                ZeehoProtocol.Auxiliary request = ZeehoProtocol.readAuxiliary(socket.getInputStream());
                byte[] payload = new byte[0];
                int response;
                switch (request.command) {
                    case AUX_CONFIG: {
                        int width = ZeehoProtocol.u16(request.payload, 0);
                        int height = ZeehoProtocol.u16(request.payload, 2);
                        if (width != 1024 || (height != 480 && height != 488)) {
                            throw new IOException(String.format("仪表媒体尺寸异常 %dx%d", width, height));
                        }
                        byte[] echoed = java.util.Arrays.copyOfRange(request.payload, 32, request.payload.length);
                        if (!identity.verify(echoed, currentHuid())) {
                            throw new IOException("媒体身份回显不匹配");
                        }
                        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        buffer.putInt(2);       // 2 = H.264
                        buffer.putShort((short) 1024);
                        buffer.putShort((short) 480);
                        payload = buffer.array();
                        response = AUX_CONFIG_ACK;
                        break;
                    }
                    case AUX_MEDIA_START:
                        response = AUX_MEDIA_START_ACK;
                        mediaReady = true;
                        state = "STREAMING";
                        break;
                    case AUX_PORT_INFO:
                        java.nio.ByteBuffer portBuffer = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        portBuffer.putInt(5002);
                        portBuffer.putInt(1);
                        payload = portBuffer.array();
                        response = 0x31;
                        break;
                    case 0x40:
                        response = 0x41;
                        break;
                    default:
                        response = 0x7f02;
                        break;
                }
                write(socket, ZeehoProtocol.auxiliary(response, request.token, payload));
            }
        } catch (Exception e) {
            if (!closed) state = "AUX_ERROR: " + e.getMessage();
        }
    }

    /** 控制通道保存的仪表 HUID，媒体通道用它校验签名回显。 */
    private String currentHuid() {
        return huid;
    }

    private void handleVideo(Socket socket) {
        videoSocket = socket;
        try (socket) {
            while (!closed) {
                ZeehoProtocol.Auxiliary request = ZeehoProtocol.readAuxiliary(socket.getInputStream());
                if (request.command == AUX_PULL_FRAME) {
                    frameRequests.offer(Boolean.TRUE);
                }
            }
        } catch (Exception e) {
            if (!closed) state = "VIDEO_ERROR: " + e.getMessage();
        } finally {
            videoSocket = null;
        }
    }

    /** 仪表逐帧拉取；没有拉帧请求时丢弃当前帧。 */
    public void sendFrame(VideoFrame frame) throws IOException {
        if (!mediaReady || videoSocket == null) return;
        boolean requested;
        try {
            requested = frameRequests.poll(100, TimeUnit.MILLISECONDS) != null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待仪表拉帧被中断", e);
        }
        if (!requested) return;
        byte[] packet = ZeehoProtocol.h264(frame.accessUnit, !sentPrefix);
        write(videoSocket, packet);
        sentPrefix = true;
    }

    private static void write(Socket socket, byte[] data) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(data);
        out.flush();
    }

    public String status() {
        return state;
    }

    public String targetIp() {
        return targetIp;
    }

    public void close() {
        closed = true;
        for (ServerSocket server : servers) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
        Socket video = videoSocket;
        if (video != null) {
            try {
                video.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
        state = "CLOSED";
    }
}
