package com.maohi.mixin;

import com.maohi.MaohiCommands;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * 指令注入器 (Mixin)
 * 负责在服务器初始化命令系统时，将 /maohi 管理指令注册到原版命令树中。
 */
@Mixin(CommandManager.class)
public class CommandManagerMixin {
    @Shadow @Final private CommandDispatcher<ServerCommandSource> dispatcher;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CommandManager.RegistrationEnvironment environment, CommandRegistryAccess commandRegistryAccess, CallbackInfo ci) {
        MaohiCommands.register(this.dispatcher, commandRegistryAccess, environment);
    }
}
