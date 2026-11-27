package com.github.carwithinforandroid.outputs.zeeho;

import android.util.Base64;

import com.github.carwithinforandroid.core.LogBus;
import com.github.carwithinforandroid.core.VideoFrame;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 极核 AE4 MAX2+ 6.2 寸 PXC/TCP 会话。
 * 逐行对照 CarWithin App/Zeeho62Legacy.swift 的 TailingConnection（zeeho62 profile）移植。
 */
public final class ZeehoConnection {

    public static final int CONTROL_PORT = 10922;
    public static final int AUX_PORT = 10711;
    public static final int VIDEO_PORT = 10710;
    public static final int DEFAULT_DISCOVERY_PORT = 10930;

    private static final int CMD_ANNOUNCE = 0x70000010;
    private static final int CMD_ANNOUNCE_ACK = 0x70000011;
    private static final int CMD_DEVICE_INFO = 0x10010;
    private static final int CMD_TIME = 0x10450;
    private static final int CMD_SESSION_START = 0x20000;
    private static final int CMD_MIRROR = 0x20590;
    private static final int CMD_KEEPALIVE = 0x70000000;

    private static final int AUX_CONFIG = 0x10;
    private static final int AUX_CONFIG_ACK = 0x11;
    private static final int AUX_MEDIA_START = 0x70;
    private static final int AUX_MEDIA_START_ACK = 0x71;
    private static final int AUX_PULL_FRAME = 0x72;

    private final int width;
    private final int height;
    private final int discoveryPort;

    private final String localIp;
    private String targetIp;
    /** 该在哪个网络上说话。null 表示用默认路由（单网卡时没问题，多网卡必翻车）。 */
    private final android.net.Network network;
    private final ZeehoIdentity identity;
    private final String packageName;
    private final String phoneModel;
    private final String osVersion;
    private final int versionCode;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private final List<ServerSocket> servers = new CopyOnWriteArrayList<>();
    private final List<Socket> peers = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Boolean> frameRequests = new LinkedBlockingQueue<>();

    private volatile boolean closed;
    private volatile String huid;
    private volatile byte[] signedHuid;
    private volatile boolean configured;
    private volatile boolean mediaReady;
    private volatile boolean sentPrefix;
    private volatile Socket videoSocket;
    private volatile Socket reverseControl;
    private volatile String state = "IDLE";
    private volatile int keepaliveId = 7;

    public ZeehoConnection(String localIp, String targetIp, ZeehoIdentity identity,
                           String packageName, int versionCode, String phoneModel, String osVersion) {
        this(localIp, targetIp, identity, packageName, versionCode, phoneModel, osVersion,
                1024, 480, DEFAULT_DISCOVERY_PORT, null);
    }

    public ZeehoConnection(String localIp, String targetIp, ZeehoIdentity identity,
                           String packageName, int versionCode, String phoneModel, String osVersion,
                           int width, int height, int discoveryPort) {
        this(localIp, targetIp, identity, packageName, versionCode, phoneModel, osVersion,
                width, height, discoveryPort, null);
    }

    public ZeehoConnection(String localIp, String targetIp, ZeehoIdentity identity,
                           String packageName, int versionCode, String phoneModel, String osVersion,
                           int width, int height, int discoveryPort, android.net.Network network) {
        this.localIp = localIp;
        this.targetIp = targetIp;
        this.identity = identity;
        this.packageName = packageName;
        this.versionCode = versionCode;
        this.phoneModel = phoneModel;
        this.osVersion = osVersion;
        this.width = width;
        this.height = height;
        this.discoveryPort = discoveryPort > 0 ? discoveryPort : DEFAULT_DISCOVERY_PORT;
        this.network = network;
    }

    /**
     * 把 socket 钉到选定网络上。
     *
     * <p>不做这一步，即使按 p2p0 的地址 bind 了源地址，出口仍按系统默认路由走，
     * 包会从 wlan0 或移动数据发出去 —— 仪表那边什么也收不到（参考实现的 UdpLink 同样这么做）。
     */
    private void pin(java.io.Closeable socket) {
        android.net.Network net = network;
        if (net == null || socket == null) return;
        try {
            if (socket instanceof Socket) {
                net.bindSocket((Socket) socket);
            } else if (socket instanceof java.net.DatagramSocket) {
                net.bindSocket((java.net.DatagramSocket) socket);
            }
        } catch (Exception e) {
            LogBus.log("绑定网络失败（继续用默认路由）：" + e.getMessage());
        }
    }

    public void start() throws IOException, GeneralSecurityException {
        if (targetIp == null || targetIp.isEmpty()) {
            List<String> found = ZeehoDiscovery.discover(localIp, discoveryPort, network);
            if (found.isEmpty()) {
                throw new IOException("未发现仪表：请连接仪表 Wi-Fi 并在仪表上开启投屏");
            }
            targetIp = found.get(0);
            LogBus.log("发现仪表 " + targetIp);
        }

        // 先监听三个反向回连端口，再请求仪表发起连接。
        listen(CONTROL_PORT, this::handleControl);
        listen(AUX_PORT, this::handleAuxiliary);
        listen(VIDEO_PORT, this::handleVideo);
        LogBus.log("已监听 " + localIp + ":" + CONTROL_PORT + "/" + AUX_PORT + "/" + VIDEO_PORT);

        announce();

        long deadline = System.currentTimeMillis() + 25_000;
        while (!closed && System.currentTimeMillis() < deadline) {
            if (signedHuid != null && videoSocket != null && mediaReady && reverseControl != null) {
                state = "STREAMING";
                LogBus.log("媒体协商完成，等待仪表逐帧请求");
                return;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待媒体协商被中断", e);
            }
        }
        throw new IOException("媒体协商超时：请退出官方投屏后重新在仪表开启投屏");
    }

    /** 向仪表 10930 端口宣告本机投屏请求。 */
    private void announce() throws IOException {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(localIp, 0));
            // 关键：宣告包必须从选中那块网卡出去
            pin(socket);
            socket.connect(new InetSocketAddress(targetIp, discoveryPort), 3000);
            JSONObject body = new JSONObject();
            try {
                body.put("phoneType", "Android");
                body.put("packageName", packageName);
            } catch (JSONException e) {
                throw new IOException("构造投屏请求失败", e);
            }
            write(socket, ZeehoProtocol.control(CMD_ANNOUNCE, 0,
                    body.toString().getBytes(StandardCharsets.UTF_8)));
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
                throw new IOException("仪表未接受投屏请求，请确认仪表处于投屏模式");
            }
            LogBus.log("仪表已接受投屏请求");
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
                    pin(socket);
                    peers.add(socket);
                    LogBus.log("仪表回连 TCP" + port);
                    pool.execute(() -> handler.handle(socket));
                } catch (IOException e) {
                    if (!closed) state = "ACCEPT_ERROR: " + e.getMessage();
                }
            }
        });
    }

    /* ---------------- 会话控制通道 10922 ---------------- */

    private void handleControl(Socket socket) {
        try (socket) {
            while (!closed) {
                ZeehoProtocol.Control request = ZeehoProtocol.readControl(socket.getInputStream());
                byte[] payload = new byte[0];
                boolean reply = true;
                switch ((int) request.command) {
                    case 0x10000:
                    case 0x10740:
                    case 0x10040:
                    case CMD_KEEPALIVE:
                    case 0x10690:
                    case 0x10780:
                    case 0x103e0:
                    case 0x80000010:
                    case 0x20021:
                    case 0x20031:
                    case 0x204c1:
                    case 0x20491:
                    case 0x20051:
                    case 0x20591:
                    case 0x205a1:
                    case 0x70000001:
                        reply = request.command == 0x10000 || request.command == 0x10740
                                || request.command == 0x10040;
                        break;
                    case CMD_DEVICE_INFO:
                        payload = deviceInfoReply(request);
                        break;
                    case CMD_TIME:
                        payload = timeReply();
                        break;
                    case CMD_SESSION_START:
                        LogBus.log("收到开始同屏协商");
                        break;
                    default:
                        LogBus.log(String.format("未处理的控制命令 0x%08x", request.command));
                        reply = false;
                        break;
                }
                if (reply) {
                    write(socket, ZeehoProtocol.control(request.command + 1,
                            request.identifier, payload));
                }
                if (request.command == CMD_SESSION_START && reverseControl == null) {
                    reverseControl = socket;
                    startMirror(socket);
                    startHeartbeat();
                }
            }
        } catch (Exception e) {
            if (!closed) {
                state = "CONTROL_ERROR: " + e.getMessage();
                LogBus.log("控制通道异常：" + e.getMessage());
            }
        }
    }

    private byte[] deviceInfoReply(ZeehoProtocol.Control request) throws IOException {
        JSONObject object;
        try {
            object = new JSONObject(new String(request.payload, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("仪表设备资料无效", e);
        }
        String name = object.optString("HUName", "");
        String receivedHuid = object.optString("HUID", "");
        if (name.isEmpty() || receivedHuid.isEmpty()) {
            throw new IOException("仪表设备资料不完整");
        }
        huid = receivedHuid;
        byte[] signature;
        try {
            signature = identity.sign(receivedHuid);
        } catch (GeneralSecurityException e) {
            throw new IOException("仪表身份签名失败", e);
        }
        signedHuid = signature;
        JSONObject reply = new JSONObject();
        try {
            reply.put("pxcVersion", "1.0.2");
            reply.put("phoneUUID", identity.phoneId());
            reply.put("phoneBrand", "Android");
            reply.put("phoneModel", phoneModel);
            reply.put("phoneOsVersion", osVersion);
            reply.put("phoneOs", "Android");
            reply.put("package", packageName);
            reply.put("versionCode", versionCode);
            reply.put("token", 0);
            reply.put("pubkey", Base64.encodeToString(identity.publicKeyDer(), Base64.NO_WRAP));
            reply.put("encryptedHUID", Base64.encodeToString(signature, Base64.NO_WRAP));
            reply.put("bluetoothName", "");
            reply.put("supportH264IFrame", true);
            reply.put("supportFunction", 128);
            reply.put("mirrorRequestReconnect", true);
        } catch (JSONException e) {
            throw new IOException("构造身份应答失败", e);
        }
        LogBus.log("已回应 RSA2048 身份 HUName=" + name);
        return reply.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] timeReply() throws IOException {
        long now = System.currentTimeMillis();
        JSONObject reply = new JSONObject();
        try {
            reply.put("time", now);
            reply.put("currentTime", now + java.util.TimeZone.getDefault().getOffset(now));
            reply.put("currentTimeZone", java.util.TimeZone.getDefault().getID());
        } catch (JSONException e) {
            throw new IOException("构造时间应答失败", e);
        }
        return reply.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 收到 0x20000 后按抓包顺序下发同屏参数，最后 0x20590 通知开始。 */
    private void startMirror(Socket socket) throws IOException {
        sendJson(socket, 0x204c0, 1, "{\"state\":0,\"msg\":\"\"}");
        sendJson(socket, 0x20020, 2, display(2));
        sendJson(socket, 0x20030, 3, display(1));
        write(socket, ZeehoProtocol.control(0x20050, 4, new byte[0]));
        sendJson(socket, 0x205a0, 5, "{\"isMaskVisible\":false}");
        sendJson(socket, CMD_MIRROR, 6, "{\"control\":1}");
        LogBus.log("已发送开始投屏通知");
    }

    private String display(int mode) throws IOException {
        JSONObject object = new JSONObject();
        try {
            object.put("displayRotation", 0);
            object.put("width", mode == 2 ? height : width);
            object.put("height", mode == 2 ? width : height);
            object.put("enableAccessibility", false);
            object.put("enableAOAHid", false);
            object.put("mode", mode);
        } catch (JSONException e) {
            throw new IOException("构造显示参数失败", e);
        }
        return object.toString();
    }

    private void sendJson(Socket socket, int command, int identifier, String json) throws IOException {
        write(socket, ZeehoProtocol.control(command, identifier,
                json.getBytes(StandardCharsets.UTF_8)));
    }

    private void startHeartbeat() {
        heartbeat.scheduleAtFixedRate(() -> {
            Socket socket = reverseControl;
            if (socket == null || closed) return;
            try {
                write(socket, ZeehoProtocol.control(CMD_KEEPALIVE, keepaliveId++, new byte[0]));
            } catch (IOException e) {
                if (!closed) LogBus.log("保活发送失败：" + e.getMessage());
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    /* ---------------- 媒体控制通道 10711 ---------------- */

    private void handleAuxiliary(Socket socket) {
        try (socket) {
            while (!closed) {
                ZeehoProtocol.Auxiliary request = ZeehoProtocol.readAuxiliary(socket.getInputStream());
                byte[] payload = new byte[0];
                int response;
                boolean reply = true;
                switch (request.command) {
                    case AUX_CONFIG:
                        payload = mediaConfigReply(request);
                        response = AUX_CONFIG_ACK;
                        break;
                    case AUX_MEDIA_START:
                        if (!configured) throw new IOException("仪表在配置完成前请求媒体启动");
                        write(socket, ZeehoProtocol.auxiliary(AUX_MEDIA_START_ACK, request.token, new byte[0]));
                        mediaReady = true;
                        state = "STREAMING";
                        LogBus.log("媒体通道就绪");
                        reply = false;
                        continue;
                    case 0x40:
                        response = 0x41;
                        break;
                    case 0x30:
                        payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(5002).putInt(1).array();
                        response = 0x31;
                        break;
                    case 0x60:
                        payload = "{\"supportFunction\":0}".getBytes(StandardCharsets.UTF_8);
                        response = 0x61;
                        break;
                    case 0x20:
                        reply = false; // 不执行仪表反控触摸
                        continue;
                    default:
                        response = 0x7f02;
                        LogBus.log(String.format("媒体控制暂不支持 0x%04x", request.command));
                        break;
                }
                if (reply) {
                    write(socket, ZeehoProtocol.auxiliary(response, request.token, payload));
                }
            }
        } catch (Exception e) {
            if (!closed) {
                state = "AUX_ERROR: " + e.getMessage();
                LogBus.log("媒体通道异常：" + e.getMessage());
            }
        }
    }

    private byte[] mediaConfigReply(ZeehoProtocol.Auxiliary request) throws IOException {
        if (request.payload.length < 32) throw new IOException("媒体配置载荷不足 32 字节");
        int reportedWidth = ZeehoProtocol.u16(request.payload, 0);
        int reportedHeight = ZeehoProtocol.u16(request.payload, 2);
        if (reportedWidth != this.width || reportedHeight != this.height) {
            LogBus.log(String.format("警告：仪表报告 %dx%d 与配置 %dx%d 不一致，按配置继续",
                    reportedWidth, reportedHeight, this.width, this.height));
        }
        byte[] echoed = Base64.decode(request.payload, 32, request.payload.length - 32, Base64.DEFAULT);
        if (signedHuid == null || !Arrays.equals(echoed, signedHuid)) {
            throw new IOException("媒体身份回显不匹配");
        }
        try {
            if (!identity.verify(echoed, huid)) {
                throw new IOException("媒体身份签名校验失败");
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("媒体身份签名校验失败", e);
        }
        configured = true;
        LogBus.log("媒体身份回显校验通过，仪表报告 " + reportedWidth + "×" + reportedHeight);
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(2).putShort((short) this.width).putShort((short) this.height).array();
    }

    /* ---------------- 视频通道 10710 ---------------- */

    private void handleVideo(Socket socket) {
        videoSocket = socket;
        try (socket) {
            while (!closed) {
                ZeehoProtocol.Auxiliary request = ZeehoProtocol.readAuxiliary(socket.getInputStream());
                if (request.command == AUX_PULL_FRAME && request.payload.length == 0) {
                    frameRequests.offer(Boolean.TRUE);
                } else {
                    LogBus.log(String.format("视频通道收到异常拉帧 0x%04x/%d 字节",
                            request.command, request.payload.length));
                }
            }
        } catch (Exception e) {
            if (!closed) LogBus.log("视频通道异常：" + e.getMessage());
        } finally {
            videoSocket = null;
        }
    }

    /** 仪表逐帧拉取；等待不到请求时丢弃当前帧，避免积压。 */
    public void sendFrame(VideoFrame frame) throws IOException {
        Socket socket = videoSocket;
        if (!mediaReady || socket == null) return;
        // 仪表刚起解码器时只能吃 IDR：第一帧若不是关键帧就丢掉，等下一个 IDR 再发。
        if (!sentPrefix && !frame.keyframe) return;
        boolean requested;
        try {
            requested = frameRequests.poll(2, TimeUnit.SECONDS) != null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待仪表拉帧被中断", e);
        }
        if (!requested || closed) return;
        byte[] packet = ZeehoProtocol.h264(frame.accessUnit, !sentPrefix);
        write(socket, packet);
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
        if (closed) return;
        Socket reverse = reverseControl;
        if (reverse != null) {
            try {
                write(reverse, ZeehoProtocol.control(CMD_MIRROR, 0xffffffff,
                        "{\"control\":0}".getBytes(StandardCharsets.UTF_8)));
            } catch (Exception ignored) {
                // 停止通知失败不影响本地清理
            }
        }
        closed = true;
        heartbeat.shutdownNow();
        for (ServerSocket server : servers) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
        for (Socket peer : peers) {
            try {
                peer.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
        state = "CLOSED";
        LogBus.log("极核会话已关闭");
    }
}
