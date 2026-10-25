/*
 * Copyright © 2024 moehreag <moehreag@gmail.com> & Contributors
 *
 * This file is part of AxolotlClient.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 * For more information, see the LICENSE file.
 */

package io.github.axolotlclient.mixin

import com.mojang.authlib.minecraft.UserApiService
import com.mojang.authlib.services.ProfileResult
import net.minecraft.client.Minecraft
import net.minecraft.client.User
import net.minecraft.client.gui.screens.social.PlayerSocialManager
import net.minecraft.client.gui.screens.social.RemoteFriendListUpdateHandler
import net.minecraft.client.multiplayer.ProfileKeyPairManager
import net.minecraft.client.multiplayer.chat.report.ReportingContext
import net.minecraft.server.Services
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Mutable
import org.spongepowered.asm.mixin.gen.Accessor
import org.spongepowered.asm.mixin.gen.Invoker
import java.util.concurrent.CompletableFuture

@Mixin(Minecraft::class)
interface MinecraftClientAccessor {
    companion object {
        @JvmStatic
        @Accessor("fps")
        fun `axolotlclient$getCurrentFps`(): Int = 0
    }

    /**
     * The vanilla right-click path (item use): runs the same hand loop and sends the same
     * use packets a physical right-click would. Used by the server-gated auto-fish module
     * to reel and recast.
     */
    @Invoker("startUseItem")
    fun `axolotlclient$startUseItem`()

    /**
     * The vanilla left-click path (attack): miss-time, spectator and item guards, attack
     * packet plus hand swing. Used by the server-gated auto-click module.
     */
    @Invoker("startAttack")
    fun `axolotlclient$startAttack`(): Boolean

    @Accessor("user")
    @Mutable
    fun `axolotlclient$setSession`(session: User?)

    @Accessor("playerSocialManager")
    @Mutable
    fun `axolotlclient$setSocialInteractionsManager`(manager: PlayerSocialManager?)

    @Accessor("profileKeyPairManager")
    @Mutable
    fun `axolotlclient$setPlayerKeyPairManager`(manager: ProfileKeyPairManager?)

    @Accessor("reportingContext")
    @Mutable
    fun `axolotlclient$setChatReportingContext`(context: ReportingContext?)

    @Accessor("userApiService")
    @Mutable
    fun `axolotlclient$setUserApiService`(service: UserApiService?)

    @Accessor("profileFuture")
    @Mutable
    fun `axolotlclient$setProfileFuture`(future: CompletableFuture<ProfileResult>?)

    @Accessor("remoteFriendListUpdateHandler")
    @Mutable
    fun `axolotlclient$setRemoteFriendListUpdateHandler`(updateHandler: RemoteFriendListUpdateHandler?)

    @Accessor("services")
    @Mutable
    fun `axolotlclient$setServices`(services: Services?)

    @Accessor("offlineDeveloperMode")
    fun `axolotlclient$isOfflineDevMode`(): Boolean
}
