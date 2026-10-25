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

import com.llamalad7.mixinextras.sugar.Local
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.axolotlclient.AxolotlClient
import net.minecraft.client.renderer.ScreenEffectRenderer
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import org.joml.Matrix4f
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ScreenEffectRenderer::class)
abstract class InGameOverlayRendererMixin {

    companion object {
        @JvmStatic
        @Inject(
            method = ["lambda\$submitFire\$0"],
            at = [At(value = "INVOKE", target = "Lorg/joml/Matrix4f;translate(FFF)Lorg/joml/Matrix4f;")]
        )
        private fun `axolotlclient$lowFire`(
            sprite: TextureAtlasSprite,
            basePose: PoseStack.Pose,
            builder: VertexConsumer,
            ci: CallbackInfo,
            @Local(name = ["pose"]) pose: Matrix4f
        ) {
            if (AxolotlClient.config().lowFire.get()) {
                // fire_height (Alpine grab-bag, wave 3): the lowering distance is configurable;
                // 0.2 (the default) is the offset the old fixed version used.
                pose.translate(0f, -AxolotlClient.config().fireHeight.get(), 0f)
            }
        }
    }
}
