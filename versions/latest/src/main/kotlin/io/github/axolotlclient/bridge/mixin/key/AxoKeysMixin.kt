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

package io.github.axolotlclient.bridge.mixin.key

import com.mojang.blaze3d.platform.InputConstants
import io.github.axolotlclient.bridge.key.AxoKey
import io.github.axolotlclient.bridge.key.AxoKeys
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Mutable
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(AxoKeys::class, remap = false)
abstract class AxoKeysMixin {
    companion object {
        @Mutable
        @Shadow
        @Final
        @JvmField
        var KEY_I: AxoKey? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var KEY_K: AxoKey? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var MOUSE_LEFT: AxoKey? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var MOUSE_RIGHT: AxoKey? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var KEY_RSHIFT: AxoKey? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var KEY_UNKNOWN: AxoKey? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var KEY_C: AxoKey? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var KEY_F7: AxoKey? = null

        @JvmStatic
        @Inject(method = ["<clinit>"], at = [At("HEAD")], cancellable = true)
        private fun setStaticValues(ci: CallbackInfo) {
            KEY_I = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_I)
            KEY_K = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_K)
            MOUSE_LEFT = InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_LEFT)
            MOUSE_RIGHT = InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_RIGHT)
            KEY_RSHIFT = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_RSHIFT)
            KEY_UNKNOWN = InputConstants.UNKNOWN
            KEY_C = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_C)
            KEY_F7 = InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_F7)
            ci.cancel()
        }
    }
}
