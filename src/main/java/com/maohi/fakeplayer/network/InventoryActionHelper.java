package com.maohi.fakeplayer.network;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 容器交互发包工具类(V5.28 P1-A.0,V5.28.2 扩展,V5.28.6 yarn build.4 ItemStackHash 适配)
 *
 * <h3>1.21.11 build.4 协议变化</h3>
 * <p>{@code ClickSlotC2SPacket} 的 {@code modifiedStacks} 元素类型与 {@code cursor} 字段类型
 * 全部从 {@code ItemStack} 改成新引入的 {@link ItemStackHash}(component-hash 同步)。
 * vanilla 客户端构造预测时调 {@code ItemStackHash.fromItemStack(stack, hasher)},
 * 但 server-side 没有公共 {@code ComponentHasher} 访问入口(只有 ClientPlayNetworkHandler 暴露)。
 * 简化方案:本 helper 全发 {@link ItemStackHash#EMPTY},等同于"无预测"——
 *   server 的 {@code onClickSlot} 内部 desync 校验 = 比较 hashes,EMPTY 永远不等于实际,
 *   只会触发额外修正包(对假人无害)。原 V5.28.1 的"预测以避指纹"加固在此版本回退,
 *   待后续接 {@code ItemStackHash.fromItemStack} + 自维护 hasher 时恢复。</p>
 *
 * <h3>API 表面保持不变</h3>
 * 公共方法签名(参数 / 返回)没动,所有 caller 不需要改。预测逻辑被简化,
 * 但实际 server-side 行为(onSlotClick 的 inventory 改动)与原版一致。
 */
public class InventoryActionHelper {

    /** 空 modifiedStacks 共享实例(per-call 也行,这里为减少对象分配)。 */
    private static Int2ObjectMap<ItemStackHash> emptyMap() {
        return new Int2ObjectOpenHashMap<>();
    }

    /** SWAP: 数字键 1-9 把 hovered slot 与 hotbar key 对应槽交换 */
    public static void clickSlot(ServerPlayerEntity player, int slot, int button, SlotActionType actionType) {
        if (player == null || player.currentScreenHandler == null) return;
        sendClickSlot(player, player.currentScreenHandler, slot, button, actionType,
            emptyMap(), ItemStackHash.EMPTY);
    }

    /**
     * PICKUP 左键(button=0): 拿起整堆 / 放下整堆 / 同物品合并 / 异物品交换。
     * server 自行结算并 sync 客户端,helper 只送一个无预测的 PICKUP 包。
     */
    public static void pickupAll(ServerPlayerEntity player, int slot) {
        if (player == null || player.currentScreenHandler == null) return;
        sendClickSlot(player, player.currentScreenHandler, slot, 0, SlotActionType.PICKUP,
            emptyMap(), ItemStackHash.EMPTY);
    }

    /**
     * PICKUP 右键(button=1): 半堆/放 1/+1/交换,server 自决。
     */
    public static void pickupOne(ServerPlayerEntity player, int slot) {
        if (player == null || player.currentScreenHandler == null) return;
        sendClickSlot(player, player.currentScreenHandler, slot, 1, SlotActionType.PICKUP,
            emptyMap(), ItemStackHash.EMPTY);
    }

    /**
     * QUICK_MOVE shift-click: 目标槽由 handler.quickMove 决定。
     */
    public static void quickMove(ServerPlayerEntity player, int slot) {
        if (player == null || player.currentScreenHandler == null) return;
        sendClickSlot(player, player.currentScreenHandler, slot, 0, SlotActionType.QUICK_MOVE,
            emptyMap(), ItemStackHash.EMPTY);
    }

    /**
     * 界面按钮点击(独立 ButtonClickC2SPacket,不是 ClickSlot):
     *   附魔台的 3 档附魔按钮 = button id 0/1/2
     *   交易台、信标确认等也用此包
     */
    public static void clickButton(ServerPlayerEntity player, int buttonId) {
        if (player == null || player.currentScreenHandler == null) return;
        int syncId = player.currentScreenHandler.syncId;
        ButtonClickC2SPacket packet = new ButtonClickC2SPacket(syncId, buttonId);
        player.networkHandler.onButtonClick(packet);
    }

    /**
     * 把背包某槽 1 件物品搬到 handler 目标槽 (3-packet "拿起 → 右键放 1 → 余数放回" 序列)。
     *
     * 流程:
     *   1. PICKUP all srcScreenSlot — 整堆入光标(srcSlot 变空)
     *   2. PICKUP one dstHandlerSlot(右键)— handler 槽放 1, 光标 -1
     *   3. PICKUP all srcScreenSlot — 光标余数放回原槽(若 N=1 第 3 步是 no-op)
     *
     * 备注:第 3 步 pickupAll 在"光标非空 + 槽空"时走"放下整堆"分支,把全部余数装回。
     *      若 dst 槽 max=1(如附魔台输入槽)且 src 是 64 堆叠,这是真人客户端典型操作序列。
     */
    public static void moveOneToHandlerSlot(ServerPlayerEntity player, int srcScreenSlot, int dstHandlerSlot) {
        pickupAll(player, srcScreenSlot);
        pickupOne(player, dstHandlerSlot);
        pickupAll(player, srcScreenSlot);
    }

    /** 关闭当前界面 */
    public static void closeScreen(ServerPlayerEntity player) {
        if (player == null || player.currentScreenHandler == null) return;
        int syncId = player.currentScreenHandler.syncId;
        player.networkHandler.onCloseHandledScreen(new CloseHandledScreenC2SPacket(syncId));
    }

    /**
     * PlayerInventory 索引 → 当前 ScreenHandler 内的槽位 ID。
     *
     * vanilla 约定:容器界面(箱子/熔炉/工作台/附魔台等)总是把玩家背包附加到 handler 的最末 36 槽,
     *   顺序: backpack 27(inv 9..35) + hotbar 9(inv 0..8)。
     *   因此 firstPlayerInvSlot = handler.slots.size() - 36
     *     - inv 9..35 → firstPlayerInvSlot + (i - 9)
     *     - inv 0..8  → firstPlayerInvSlot + 27 + i
     *
     * 特例 PlayerScreenHandler(无容器打开时): armor/crafting/offhand 打散布局
     *     - inv 0..8  → 36..44
     *     - inv 9..35 → 9..35
     *     - inv 40 (offhand) → 45
     *
     * @return 对应的 screen slot id, 不在合法 inv 索引范围返回 -1
     */
    public static int playerInvSlotToScreenSlot(ScreenHandler handler, int playerInvIndex) {
        if (handler == null) return -1;
        if (handler instanceof PlayerScreenHandler) {
            if (playerInvIndex >= 0 && playerInvIndex <= 8) return 36 + playerInvIndex;
            if (playerInvIndex >= 9 && playerInvIndex <= 35) return playerInvIndex;
            if (playerInvIndex == 40) return 45;  // offhand
            return -1;
        }
        int firstPlayerInvSlot = handler.slots.size() - 36;
        if (firstPlayerInvSlot < 0) return -1;
        if (playerInvIndex >= 0 && playerInvIndex <= 8) return firstPlayerInvSlot + 27 + playerInvIndex;
        if (playerInvIndex >= 9 && playerInvIndex <= 35) return firstPlayerInvSlot + (playerInvIndex - 9);
        return -1;
    }

    // ---- internal ----

    private static void sendClickSlot(ServerPlayerEntity player, ScreenHandler handler, int slot, int button,
                                      SlotActionType actionType,
                                      Int2ObjectMap<ItemStackHash> modifiedStacks, ItemStackHash cursor) {
        // 1.21.11 build.4 ClickSlotC2SPacket 构造器签名:
        //   (int syncId, int revision, short slot, byte button, SlotActionType actionType,
        //    Int2ObjectMap<ItemStackHash> modifiedStacks, ItemStackHash cursor)
        ClickSlotC2SPacket packet = new ClickSlotC2SPacket(
                handler.syncId,
                handler.getRevision(),
                (short) slot,
                (byte) button,
                actionType,
                modifiedStacks,
                cursor
        );
        player.networkHandler.onClickSlot(packet);
    }
}
