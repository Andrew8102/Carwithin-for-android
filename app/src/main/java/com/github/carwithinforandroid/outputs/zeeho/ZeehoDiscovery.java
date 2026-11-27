package com.github.carwithinforandroid.outputs.zeeho;

import android.net.Network;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 在手机所在 /24 网段扫描仪表发现端口。移植自 CarWithin App/TailingDiscovery.swift 的简化版。 */
public final class ZeehoDiscovery {

    public static final int DISCOVERY_PORT = 10930;

    private ZeehoDiscovery() {
    }

    public static List<String> discover(String localIp, int port) {
        return discover(localIp, port, null);
    }

    /**
     * 单点探测：这个地址的端口能不能连上。
     *
     * <p>比全段扫描快得多，用来验证「上次成功的地址」是否仍然有效。
     */
    public static boolean probe(String ip, int port, Network network, int timeoutMs) {
        if (ip == null || ip.isEmpty()) return false;
        try (Socket socket = new Socket()) {
            if (network != null) {
                try {
                    network.bindSocket(socket);
                } catch (Exception ignored) {
                    // 绑不上就试默认路由
                }
            }
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param network 要钉住的网络；null 走默认路由。多网卡（p2p0 + wlan0）时必须给，
     *                否则探测包会从 wlan0 发出，扫不到 P2P 网段里的仪表。
     */
    public static List<String> discover(String localIp, int port, Network network) {
        String[] parts = localIp.split("\\.");
        if (parts.length != 4) return Collections.emptyList();
        String prefix = parts[0] + "." + parts[1] + "." + parts[2] + ".";
        int self = Integer.parseInt(parts[3]);

        List<String> candidates = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            if (i != self) candidates.add(prefix + i);
        }

        // 仪表在 P2P 组里是 GO，地址几乎总是网段第一个；CarWithin 的 Swift 版也是先试 .1。
        // 先单独敲一次 .1，命中就不用再扫 253 个地址（省 8 秒）。
        if (self != 1) {
            try (Socket socket = new Socket()) {
                if (network != null) {
                    try {
                        network.bindSocket(socket);
                    } catch (Exception ignored) {
                        // 绑不上就走默认路由
                    }
                }
                socket.connect(new InetSocketAddress(prefix + "1", port), 600);
                return Collections.singletonList(prefix + "1");
            } catch (Exception ignored) {
                // .1 不是仪表，退回全段扫描
            }
        }

        CopyOnWriteArrayList<String> found = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (String ip : candidates) {
                pool.execute(() -> {
                    try (Socket socket = new Socket()) {
                        if (network != null) {
                            try {
                                network.bindSocket(socket);
                            } catch (Exception ignored) {
                                // 绑不上就试默认路由，至少不会整条扫描都失败
                            }
                        }
                        socket.connect(new InetSocketAddress(ip, port), 400);
                        found.add(ip);
                    } catch (Exception ignored) {
                        // 扫描是 best-effort，不是每个地址都会响应。
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return found;
    }
}
