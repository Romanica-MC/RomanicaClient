/*
 * Copyright © 2025 moehreag <moehreag@gmail.com> & Contributors
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

package io.github.axolotlclient.bridge.mixin.render

import io.github.axolotlclient.AxolotlClientCommon
import io.github.axolotlclient.bridge.impl.AxoSpriteImpl
import io.github.axolotlclient.bridge.render.AxoSprite
import io.github.axolotlclient.bridge.render.AxoSprites
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Mutable
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(AxoSprites::class, remap = false)
abstract class AxoSpritesMixin {
    companion object {
        @Mutable
        @Shadow
        @Final
        @JvmField
        var BADGE: AxoSprite? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var BARRIER_ITEM_ICON: AxoSprite? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var FURNACE_OFF: AxoSprite? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var FURNACE_ON: AxoSprite? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var MAGNET_ICON: AxoSprite? = null

        @JvmStatic
        @Inject(method = ["<clinit>"], at = [At("HEAD")], cancellable = true)
        private fun setStaticValues(info: CallbackInfo) {
            val badgeId = AxolotlClientCommon.BADGE_PATH as Identifier
            val barrierId = Identifier.withDefaultNamespace("textures/item/barrier.png")
            val furnaceOffId = Identifier.withDefaultNamespace("textures/block/furnace_front.png")
            val furnaceOnId = Identifier.withDefaultNamespace("textures/block/furnace_front_on.png")
            val magnetId = Identifier.fromNamespaceAndPath(AxolotlClientCommon.MODID, "magnet")
            BADGE = AxoSpriteImpl { client, stack, sX, sY, sW, sH, color ->
                stack.blit(RenderPipelines.GUI_TEXTURED, badgeId, sX, sY, 0f, 0f, sW, sH, 16, 16, color)
            }
            BARRIER_ITEM_ICON = AxoSpriteImpl { client, stack, sX, sY, sW, sH, color ->
                stack.blit(RenderPipelines.GUI_TEXTURED, barrierId, sX, sY, 0f, 0f, sW, sH, 16, 16, color)
            }
            FURNACE_OFF = AxoSpriteImpl { client, stack, sX, sY, sW, sH, color ->
                stack.blit(RenderPipelines.GUI_TEXTURED, furnaceOffId, sX, sY, 0f, 0f, sW, sH, 16, 16, color)
            }
            FURNACE_ON = AxoSpriteImpl { client, stack, sX, sY, sW, sH, color ->
                stack.blit(RenderPipelines.GUI_TEXTURED, furnaceOnId, sX, sY, 0f, 0f, sW, sH, 16, 16, color)
            }
            MAGNET_ICON = AxoSpriteImpl { client, stack, sX, sY, sW, sH, color ->
                stack.blitSprite(RenderPipelines.GUI_TEXTURED, magnetId, sX, sY, sW, sH, color)
            }
            info.cancel()
        }
    }
}
