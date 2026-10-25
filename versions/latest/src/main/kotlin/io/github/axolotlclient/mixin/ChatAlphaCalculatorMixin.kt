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

import io.github.axolotlclient.util.ChatPeek
import net.minecraft.client.multiplayer.chat.GuiMessage
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Chat peek (Alpine parity), fade half: the background chat pass computes each line's fade alpha
 * from its age here. While the peek key is held, every line is treated as fully visible so the
 * whole history shows — including messages that already faded out — without opening the chat
 * screen. The height half of peek lives in [ChatHudMixin] (`getHeight`).
 */
// abstract class instead of Java's interface: interface companion objects cannot host the
// private @JvmStatic static handler this mixin needs.
@Mixin(targets = ["net.minecraft.client.gui.components.ChatComponent\$AlphaCalculator"])
abstract class ChatAlphaCalculatorMixin {

    companion object {
        @JvmStatic
        @Inject(method = ["lambda\$timeBased\$0"], at = [At("HEAD")], cancellable = true)
        private fun `axolotlclient$peekNoFade`(tick: Int, line: GuiMessage.Line, cir: CallbackInfoReturnable<Float>) {
            if (ChatPeek.isActive()) {
                cir.setReturnValue(1.0f)
            }
        }
    }
}
