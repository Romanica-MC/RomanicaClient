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

package io.github.axolotlclient.bridge.mixin.entity.effect

import io.github.axolotlclient.bridge.entity.effect.AxoStatusEffect
import io.github.axolotlclient.bridge.entity.effect.AxoStatusEffects
import net.minecraft.world.effect.MobEffects
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Mutable
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(AxoStatusEffects::class, remap = false)
abstract class AxoStatusEffectsMixin {
    companion object {
        @Mutable
        @Shadow
        @Final
        @JvmField
        var JUMP_BOOST: AxoStatusEffect? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var SPEED: AxoStatusEffect? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var HASTE: AxoStatusEffect? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var REGEN: AxoStatusEffect? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var BLINDNESS: AxoStatusEffect? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var MINING_FATIGUE: AxoStatusEffect? = null

        @Mutable
        @Shadow
        @Final
        @JvmField
        var HEALTH_BOOST: AxoStatusEffect? = null

        @JvmStatic
        @Inject(method = ["<clinit>"], at = [At("HEAD")], cancellable = true)
        private fun setStaticValues(info: CallbackInfo) {
            REGEN = MobEffects.REGENERATION.value()
            JUMP_BOOST = MobEffects.JUMP_BOOST.value()
            SPEED = MobEffects.SPEED.value()
            HASTE = MobEffects.HASTE.value()
            BLINDNESS = MobEffects.BLINDNESS.value()
            MINING_FATIGUE = MobEffects.MINING_FATIGUE.value()
            HEALTH_BOOST = MobEffects.HEALTH_BOOST.value()

            info.cancel()
        }
    }
}
