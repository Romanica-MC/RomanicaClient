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

import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.world.entity.projectile.FishingHook
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * Exposes the bobber's synced bite flag. The server sets `DATA_BITING` when a fish
 * bites and clears it when it escapes; the same update is what makes the vanilla client's
 * bobber dip (`FishingHook#onSyncedDataUpdated`). That synced flag is the only
 * bite signal the client ever sees — there is no dedicated packet or sound for it — so
 * auto-fish polls it from `AutoFish.tick()`.
 */
@Mixin(FishingHook::class)
interface FishingHookAccessor {

    companion object {
        @JvmStatic
        @Accessor("DATA_BITING")
        fun `axolotlclient$dataBiting`(): EntityDataAccessor<Boolean> = throw AssertionError()
    }
}
