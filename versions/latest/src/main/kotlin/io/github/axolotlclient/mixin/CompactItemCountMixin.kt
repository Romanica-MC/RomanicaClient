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
import io.github.axolotlclient.modules.inventoryTweaks.InventoryTweaks
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

/**
 * compact_item_counts (inventory_tweaks): routes the stack-count string every GUI item
 * decoration draws through the compact formatter. `GuiGraphicsExtractor#itemCount` is the
 * single funnel — container slots, the hotbar and the carried item all end here — and it only
 * stringifies the count itself when the caller did not pass an override text (vanilla's
 * quick-craft "over max stack" hint keeps its own yellow text).
 *
 * @WrapOperation, not @Redirect: ViaFabricPlus redirects the same String.valueOf call, and two
 * redirects on one call site are a hard Mixin failure (crashed every launch, 2026-10-25). Counts
 * we don't compact fall through to `original`, so the other mod's handler still runs.
 */
@Mixin(GuiGraphicsExtractor::class)
abstract class CompactItemCountMixin {

    @WrapOperation(
        method = ["itemCount"],
        at = [At(value = "INVOKE", target = "Ljava/lang/String;valueOf(I)Ljava/lang/String;")]
    )
    private fun `axolotlclient$compactCount`(count: Int, original: Operation<String>): String {
        val compact = InventoryTweaks.getInstance().formatCount(count)
        return if (compact != count.toString()) compact else original.call(count)
    }
}
