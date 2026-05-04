package com.maohi.fakeplayer.network;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
import io.netty.channel.ChannelFutureListener;
import org.jetbrains.annotations.Nullable;

/**
 * 假客户端连接 (V3)
 */
public class FakeClientConnection extends ClientConnection {

	// 在构造时一次性生成并固定这个假 IP，防止每次调用 getAddress() 返回不同值导致日志前后不一致
	private final java.net.InetSocketAddress fakeAddress;
	// V3.2 提升为类字段：控制 EmbeddedChannel 的 isActive/isOpen 状态，closeChannel() 可访问
	final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
	// V3.2 防重入标记：handleDisconnection 只执行一次
	private final java.util.concurrent.atomic.AtomicBoolean disconnected = new java.util.concurrent.atomic.AtomicBoolean(false);
	
	// TCP Cubic 仿真参数
	private double cwnd = 10.0;
	private double ssthresh = 64.0;
	private long lastPacketTime = 0;

	public FakeClientConnection() {
	super(NetworkSide.SERVERBOUND);

	// 生成一个看起来真实的公网 IP（避开保留网段 10.x, 127.x, 192.168.x 等）
	java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
	int ip1 = random.nextInt(200) + 20;
	int ip2 = random.nextInt(255);
	int ip3 = random.nextInt(255);
	int ip4 = random.nextInt(254) + 1;
	int port = random.nextInt(40000) + 10000;
	this.fakeAddress = new java.net.InetSocketAddress(ip1 + "." + ip2 + "." + ip3 + "." + ip4, port);

	// 使用自定义的 EmbeddedChannel 子类，覆盖 remoteAddress() 返回伪造 IP
	io.netty.channel.embedded.EmbeddedChannel embeddedChannel =
	new io.netty.channel.embedded.EmbeddedChannel() {
	@Override public java.net.SocketAddress remoteAddress() { return fakeAddress; }
	@Override public java.net.SocketAddress localAddress() { return fakeAddress; }
	@Override public boolean isActive() { return !closed.get(); }
	@Override public boolean isOpen() { return !closed.get(); }
	@Override public io.netty.channel.ChannelFuture write(Object msg) {
	io.netty.util.ReferenceCountUtil.release(msg);
	return newSucceededFuture();
	}
	@Override public io.netty.channel.ChannelFuture writeAndFlush(Object msg) {
	io.netty.util.ReferenceCountUtil.release(msg);
	return newSucceededFuture();
	}
	};


        // 使用 Access Widener 赋予的权限直接注入，彻底告别反射，保证跨版本稳定性
        this.channel = embeddedChannel;
        this.address = fakeAddress;
    }

    public void disableAutoRead() {
    }

    @Override
    public void disconnect(net.minecraft.text.Text disconnectReason) {
        // 2.82 极致静默：彻底拦截断开信号，防止触发 Netty 的底层异步冲突
    }

	@Override
	public void handleDisconnection() {
	// V3.2 修复 handleDisconnection called twice：
	// 防重入标记，确保只处理一次
	if (disconnected.getAndSet(true)) return;
	// 拦截清理信号，让连接回收变得无声无息
	}

	/**
	 * V3.2 安全关闭 EmbeddedChannel
	 * 标记 closed=true 让 channel.isActive()/isOpen() 返回 false，
	 * 防止 Minecraft 的 tickConnections() 再触发 handleDisconnection
	 */
	public void closeChannel() {
	closed.set(true);
	}

    @Override
    public boolean isOpen() {
        return channel != null && channel.isOpen();
    }

    public void send(Packet<?> packet) {
        // TCP Cubic 拥塞控制仿真逻辑
        simulateTcpFlow();
        
        // 核心：协议层抗检测 - 自动响应服务器心跳 (KeepAlive)
        if (packet instanceof net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket keepAliveS2C) {
            if (this.packetListener instanceof net.minecraft.server.network.ServerPlayNetworkHandler handler) {
                PingPongHandler.respondToKeepAlive(keepAliveS2C.getId(), handler, KEEP_ALIVE_POOL);
            }
        }
    }

    private void simulateTcpFlow() {
        long now = System.currentTimeMillis();
        // 模拟 TCP RTT 窗口更新
        if (now - lastPacketTime > 50) { // 假设 50ms RTT
            if (cwnd < ssthresh) {
                cwnd += 1.0; // 慢启动阶段
            } else {
                cwnd += 1.0 / cwnd; // 拥塞避免阶段
            }
            
            // 模拟随机丢包引发的窗口缩减
            if (java.util.concurrent.ThreadLocalRandom.current().nextInt(2000) == 0) {
                ssthresh = cwnd / 2.0;
                cwnd = 1.0;
            }
            lastPacketTime = now;
        }
    }

  public void send(Packet<?> packet, @Nullable io.netty.channel.ChannelFutureListener listener) {
  send(packet);
  }

  public void send(Packet<?> packet, @Nullable io.netty.channel.ChannelFutureListener listener, boolean flush) {
  send(packet, listener);
  }

	/**
	 * 共享线程池：避免每次心跳都 new Thread() 制造线程垃圾 (2.70 升级为公共池)
	 * NIT: 添加 shutdown hook 确保服务器停止时优雅关闭
	 */
	public static final java.util.concurrent.ScheduledExecutorService KEEP_ALIVE_POOL =
	java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
		Thread t = new Thread(r, "HeartbeatThread");
		t.setDaemon(true);
		return t;
	});

	static {
		java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> KEEP_ALIVE_POOL.shutdownNow()));
	}

    @Override
    public void tick() {
        // 切断 ServerNetworkIo 的 tick 循环推送
    }

    public void flush() {
    }

    public boolean hasChannel() {
        return true;
    }

    public boolean isChannelOpen() {
        return channel != null && channel.isOpen();
    }

    // 伪造逼真的玩家加入公网 IP，彻底消灭控制台里一眼假的 [local]
    @Override
    public java.net.SocketAddress getAddress() {
        return fakeAddress;
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    // 适配 1.20+ 的新版 API
    public String getAddressAsString(boolean logIps) {
        return fakeAddress.toString();
    }
}
