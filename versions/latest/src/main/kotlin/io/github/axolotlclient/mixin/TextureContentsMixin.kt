/*
 * Copyright © 2026 moehreag <moehreag@gmail.com> & Contributors
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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.sugar.Local
import io.github.axolotlclient.AxolotlClientCommon
import io.github.axolotlclient.util.AltIcons
import net.minecraft.client.renderer.texture.TextureContents
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.Resource
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import java.io.InputStream

@Mixin(TextureContents::class)
abstract class TextureContentsMixin {

    companion object {
        @JvmStatic
        @WrapOperation(
            method = ["load"],
            at = [At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/Resource;open()Ljava/io/InputStream;")]
        )
        private fun getAltIcon(instance: Resource, original: Operation<InputStream>, @Local(argsOnly = true) id: Identifier): InputStream {
            if (AxolotlClientCommon.BADGE_PATH == id && !AxolotlClientCommon.getInstance().getConfig().noAltIcons.get()) {
                return AltIcons.getAltIcon().orElseGet { original.call(instance) }
            }
            return original.call(instance)
        }
    }
}
