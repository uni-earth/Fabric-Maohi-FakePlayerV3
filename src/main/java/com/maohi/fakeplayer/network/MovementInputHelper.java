package com.maohi.fakeplayer.network;

import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.PlayerInput;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 移动输入发包工具 (V5.28 P1-B.0)
 *
 * <p>真人客户端把 W/A/S/D/jump/sneak/sprint 七个 boolean 打包进 {@link PlayerInput} 并通过
 * {@link PlayerInputC2SPacket} 上报。服务端 {@code ServerPlayNetworkHandler#onPlayerInput} 接到后调
 * {@code ServerPlayerEntity#setPlayerInput} 把布尔位映射到 {@code forwardSpeed/sidewaysSpeed/jumping}
 * 字段。我们这里反过来:不再直接写字段,改成构造同样的 {@link PlayerInput} 走 onPlayerInput,
 * vanilla 自动设字段——任何外部 PCAP/反作弊都看不出区别。</p>
 *
 * <h3>Sprint 单独走 ClientCommand</h3>
 * 1.21.11 的 {@code setPlayerInput} 不会根据 {@code sprint} 位翻转 {@code isSprinting()} 状态——
 * sprint 切换走 {@link ClientCommandC2SPacket} 的 {@code START_SPRINTING/STOP_SPRINTING} 模式
 * (这是真人双击 W 触发的 client command,与 PlayerInput 的 sprint flag 是两条独立通道)。
 * helper 内部统一处理:每次 send 检测当前 isSprinting 与目标 sprint 是否一致,不一致就发 client command。
 *
 * <h3>Jump 是一次性脉冲</h3>
 * 真人按一次空格 = 一个 tick 的 jump=true,vanilla LivingEntity.tickMovement 检测到
 * {@code jumping && isOnGround} 时触发跳跃冲量。所以 jump 必须与同 tick 内的最终 input 一起 send,
 * 不能先 send(jump=true) 后再 send(jump=false) — 后者会在 entity tick 前覆盖 jumping 字段导致跳不起来。
 * 调用方应直接把 jump=true 拼进同 tick 唯一的 send 调用。
 *
 * <h3>幂等</h3>
 * 缓存上次发出的 PlayerInput,新输入与旧值相等时跳过 onPlayerInput(避免每 tick 重复发同一个包,
 * 真人客户端也只在 input 变化时才发)。但 sprint 的 ClientCommand 仍按需补发——
 * 因为 isSprinting 可能被外部代码或 vanilla 内部改回。
 */
public final class MovementInputHelper {

    private MovementInputHelper() {}

    /** 上次为某假人发出的 PlayerInput,用于差量发包。 */
    private static final Map<UUID, PlayerInput> lastSent = new ConcurrentHashMap<>();

    /** 上次缓存的 input;无缓存返回 {@link PlayerInput#DEFAULT}(全 false)。 */
    public static PlayerInput current(ServerPlayerEntity p) {
        if (p == null) return PlayerInput.DEFAULT;
        return lastSent.getOrDefault(p.getUuid(), PlayerInput.DEFAULT);
    }

    /**
     * 发送完整 7 位输入。与上次相同则不重发 PlayerInputC2SPacket(节省指纹噪声),
     * 但 sprint 状态总会按需用 ClientCommand 补正。
     */
    public static void send(ServerPlayerEntity p,
                             boolean forward, boolean backward,
                             boolean left, boolean right,
                             boolean jump, boolean sneak, boolean sprint) {
        if (p == null || p.networkHandler == null) return;

        PlayerInput in = new PlayerInput(forward, backward, left, right, jump, sneak, sprint);
        PlayerInput prev = lastSent.put(p.getUuid(), in);
        if (prev == null || !prev.equals(in)) {
            p.networkHandler.onPlayerInput(new PlayerInputC2SPacket(in));
        }

        // Sprint 走独立 ClientCommand 通道:vanilla setPlayerInput 不翻 isSprinting()。
        boolean isSprinting = p.isSprinting();
        if (sprint && !isSprinting) {
            p.networkHandler.onClientCommand(new ClientCommandC2SPacket(p,
                ClientCommandC2SPacket.Mode.START_SPRINTING));
        } else if (!sprint && isSprinting) {
            p.networkHandler.onClientCommand(new ClientCommandC2SPacket(p,
                ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        }
    }

    /**
     * 浮点 forward/sideways 转 boolean flag 的便捷入口(过渡期适配旧 setMovement 风格代码)。
     * 真人键盘只能按或不按,小阈值 0.1 以下视作未按。sneak 沿用 last input。
     */
    public static void sendMovement(ServerPlayerEntity p, float forward, float sideways,
                                     boolean jump, boolean sprint) {
        boolean sneak = current(p).sneak();
        sendMovement(p, forward, sideways, jump, sneak, sprint);
    }

    /** 同上,但显式传 sneak。 */
    public static void sendMovement(ServerPlayerEntity p, float forward, float sideways,
                                     boolean jump, boolean sneak, boolean sprint) {
        send(p,
            forward > 0.1f, forward < -0.1f,
            sideways > 0.1f, sideways < -0.1f,
            jump, sneak, sprint);
    }

    /** 全方向松键 + 取消 sprint。sneak 沿用 last input(蹲下不会因为停步而起来)。 */
    public static void stop(ServerPlayerEntity p) {
        boolean sneak = current(p).sneak();
        send(p, false, false, false, false, false, sneak, false);
    }

    /** 只切 sneak 标志位,其它输入沿用 last。 */
    public static void setSneaking(ServerPlayerEntity p, boolean sneak) {
        PlayerInput cur = current(p);
        send(p, cur.forward(), cur.backward(), cur.left(), cur.right(),
            cur.jump(), sneak, cur.sprint());
    }

    /** 只切 sprint 标志位,其它输入沿用 last。 */
    public static void setSprinting(ServerPlayerEntity p, boolean sprint) {
        PlayerInput cur = current(p);
        send(p, cur.forward(), cur.backward(), cur.left(), cur.right(),
            cur.jump(), cur.sneak(), sprint);
    }

    /** 假人下线时清缓存,避免 leak。 */
    public static void clear(UUID uuid) {
        if (uuid != null) lastSent.remove(uuid);
    }
}
