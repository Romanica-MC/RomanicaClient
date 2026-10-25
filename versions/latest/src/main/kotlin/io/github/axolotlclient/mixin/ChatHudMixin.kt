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

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import io.github.axolotlclient.AxolotlClient
import io.github.axolotlclient.bridge.events.Events
import io.github.axolotlclient.bridge.events.types.ReceiveChatMessageEvent
import io.github.axolotlclient.util.ChatPeek
import io.github.axolotlclient.util.chat.ChatScrollSmoothing
import io.github.axolotlclient.util.duck.ChatHudExtension
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.components.ChatComponent.ChatGraphicsAccess
import net.minecraft.client.gui.components.ChatComponent.DisplayMode
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.client.multiplayer.chat.GuiMessageSource
import net.minecraft.client.multiplayer.chat.GuiMessageTag
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MessageSignature
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.util.Util
import org.joml.Matrix3x2f
import org.joml.Vector2f
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.time.LocalTime
import java.util.Locale
import java.util.function.Consumer
import kotlin.math.min

@Mixin(ChatComponent::class)
abstract class ChatHudMixin : ChatHudExtension {

    @Shadow
    @Final
    private val allMessages: MutableList<GuiMessage>? = null

    @Shadow
    @Final
    private val trimmedMessages: MutableList<GuiMessage.Line>? = null

    @Shadow
    private var chatScrollbarPos: Int = 0

    @Shadow
    abstract fun getLinesPerPage(): Int

    @Shadow
    abstract fun getLineHeight(): Int

    @Shadow
    abstract fun getScale(): Double

    @Shadow
    abstract fun getWidth(): Int

    @Shadow
    abstract fun getHeight(): Int

    @Shadow
    abstract fun refreshTrimmedMessages()

    /**
     * Base (suffix-less) text of the message that currently ends the chat, while compact chat is
     * stacking consecutive duplicates of it. `null` when no streak is live.
     */
    @Unique
    private var `axolotlclient$compactStreakBase`: String? = null

    @Unique
    private var `axolotlclient$compactStreakCount` = 0

    /**
     * Wall clock of the last stacked instance; drives compact_time_based stacking
     * (Alpine profile M6). 0 = no live streak.
     */
    @Unique
    private var `axolotlclient$compactStreakAt` = 0L

    // ── Smooth chat (smooth_chat) state ─────────────────────────────────────────
    //
    // chatScrollbarPos stays vanilla-authoritative (the animation TARGET). The animated
    // fractional position below only drives rendering: line selection uses its floor and a
    // sub-line pose translation carries the remainder. Stepping happens once per rendered
    // frame in the public extractRenderState wrap (dt==0 later in the same frame is a no-op),
    // so the interpolation is frame-rate aware by construction.

    /** Animated fractional scroll position (lines; negative while a new message slides in). */
    @Unique
    private var `axolotlclient$smoothScrollCurrent` = 0.0f

    /** Wall-clock sample of the last animation step (0 = none yet). */
    @Unique
    private var `axolotlclient$smoothLastFrameMs` = 0L

    /** Sub-line y offset (local units) the current render pass translates the chat pose by. */
    @Unique
    private var `axolotlclient$smoothRenderOffset` = 0.0f

    /** Whether the current render pass draws one extra line above the window (slide in/out). */
    @Unique
    private var `axolotlclient$smoothExtraTopLine` = false

    @WrapMethod(method = ["addMessage"])
    private fun onChatMessage(
        contents: Component,
        signature: MessageSignature?,
        source: GuiMessageSource?,
        tag: GuiMessageTag?,
        original: Operation<Void>
    ) {
        var contents = contents
        val event = ReceiveChatMessageEvent(false, contents.getString(), contents)
        Events.RECEIVE_CHAT_MESSAGE.invoker().accept(event)
        if (event.isCancelled) {
            return
        } else if (event.newMessage != null) {
            contents = event.newMessage as Component
        }

        // Empty filter: blank/whitespace-only lines are pure formatting noise; drop them before
        // they ever reach the history.
        if (AxolotlClient.config().chatEmptyFilter.get() && contents.getString().isBlank()) {
            return
        }

        // Own-name highlight (and optional ping sound) runs before the timestamp prefix so the
        // gold tint is applied to the message text itself, not the timestamp.
        contents = `axolotlclient$highlightOwnName`(contents)

        if (AxolotlClient.config().chatTimestamps.get()) {
            val stamped = Component.empty()
            stamped.append(
                Component.literal("[" + LocalTime.now().format(AxolotlClient.config().chatTimestampFormat.get().formatter()) + "] ")
                    .withStyle(ChatFormatting.GRAY)
            )
            stamped.append(contents)
            contents = stamped
        }

        if (`axolotlclient$compactChatShouldStack`(contents)) {
            // Cancel-and-re-add: drop the previous entry of the streak, then let the vanilla add
            // path re-insert the last instance's components with the counter appended.
            if (!`axolotlclient$compactRemoveStackedInstance`()) {
                return // streak bookkeeping said stack, but the entry vanished underneath us
            }
            contents = contents.copy()
                .append(Component.literal(" x" + `axolotlclient$compactStreakCount`).withStyle(ChatFormatting.GRAY))
        }

        original.call(contents, signature, source, tag)
        `axolotlclient$smoothChatKickForNewMessage`()
    }

    /**
     * Smooth chat: when a message lands while the chat is pinned to the bottom
     * (`chatScrollbarPos == 0`), vanilla keeps the scroll target but the content visibly
     * jumps up by the new lines. Starting the animation below the target by the number of
     * lines just added makes the message slide in instead. (While scrolled up with the chat
     * screen focused vanilla itself bumps the scroll per line, which the animation follows
     * for free.)
     */
    @Unique
    private fun `axolotlclient$smoothChatKickForNewMessage`() {
        if (AxolotlClient.config() == null || !AxolotlClient.config().smoothChat.get() || chatScrollbarPos != 0) {
            return
        }
        if (allMessages!!.isEmpty()) {
            return
        }
        val newest = allMessages!!.first()
        var addedLines = 0
        while (addedLines < trimmedMessages!!.size && trimmedMessages!![addedLines].parent() == newest) {
            addedLines++
        }
        if (addedLines > 0) {
            `axolotlclient$smoothScrollCurrent` -= addedLines
        }
    }

    /**
     * Decides whether the incoming message continues the live duplicate streak; maintains the streak
     * state either way. Equality is on the plain visible text only — styling and click/hover events of
     * the last instance are what end up rendered.
     *
     * Alpine profile M6 sub-settings: `compact_time_based` also stacks a duplicate that
     * arrives within `compact_time_window` seconds even when other messages landed in between
     * (the earlier instance is then re-found mid-list instead of expected at the head);
     * `compact_ignore_line_breaks` normalizes embedded newlines before comparing;
     * `compact_ignore_blank` keeps whitespace-only lines from stacking.
     */
    @Unique
    private fun `axolotlclient$compactChatShouldStack`(incoming: Component): Boolean {
        if (AxolotlClient.config() == null || !AxolotlClient.config().compactChat.get()) {
            `axolotlclient$compactStreakBase` = null
            `axolotlclient$compactStreakCount` = 0
            `axolotlclient$compactStreakAt` = 0L
            return false
        }

        val text = `axolotlclient$compactNormalize`(incoming.getString())
        if (text.isBlank()) {
            // compact_ignore_blank (default): blank lines are ignored noise — they neither
            // stack nor break a live streak, so duplicates split only by a blank line still
            // compact. With it off, a blank line resets the streak like any other message.
            if (!AxolotlClient.config().compactIgnoreBlank.get()) {
                `axolotlclient$compactStreakBase` = null
                `axolotlclient$compactStreakCount` = 0
                `axolotlclient$compactStreakAt` = 0L
            }
            return false
        }

        if (text == `axolotlclient$compactStreakBase` && `axolotlclient$compactStreakEligible`()) {
            `axolotlclient$compactStreakCount`++
            `axolotlclient$compactStreakAt` = System.currentTimeMillis()
            return true
        }

        `axolotlclient$compactStreakBase` = text
        `axolotlclient$compactStreakCount` = 1
        `axolotlclient$compactStreakAt` = System.currentTimeMillis()
        return false
    }

    /** Normalized comparison form of a chat line (compact_ignore_line_breaks). */
    @Unique
    private fun `axolotlclient$compactNormalize`(text: String): String {
        return if (AxolotlClient.config().compactIgnoreLineBreaks.get()) {
            text.replace("\n", " ").replace("\r", " ").trim()
        } else {
            text
        }
    }

    /**
     * Whether the live streak may still be extended by a duplicate: consecutive mode
     * requires the compacted message to still be the newest one — anything that
     * reshuffles the chat behind our back (clear, server-side delete, state restore,
     * another mod) shows up as a mismatch and safely breaks the streak. Time-based
     * mode instead only requires arrival within the window; the removal pass then
     * re-finds the entry mid-list, breaking the streak itself if it is gone.
     */
    @Unique
    private fun `axolotlclient$compactStreakEligible`(): Boolean {
        if (AxolotlClient.config().compactTimeBased.get()) {
            return `axolotlclient$compactStreakAt` > 0 &&
                (System.currentTimeMillis() - `axolotlclient$compactStreakAt`
                    <= AxolotlClient.config().compactTimeWindow.get() * 1000L)
        }
        return `axolotlclient$compactStreakIsHeadOfChat`()
    }

    /**
     * Removes the previously stacked instance from the chat. Consecutive mode: it is the
     * head (as verified by [`axolotlclient$compactStreakIsHeadOfChat`]); time-based
     * mode: it is searched for anywhere in the history. Returns false when the entry is
     * gone (the caller then leaves the message unstacked).
     */
    @Unique
    private fun `axolotlclient$compactRemoveStackedInstance`(): Boolean {
        val expected = if (`axolotlclient$compactStreakCount` > 1) {
            "${`axolotlclient$compactStreakBase`} x${`axolotlclient$compactStreakCount`}"
        } else {
            `axolotlclient$compactStreakBase`
        }
        if (AxolotlClient.config().compactTimeBased.get()) {
            for (i in allMessages!!.indices) {
                val candidate = allMessages!![i]
                if (`axolotlclient$compactNormalize`(candidate.content().getString()) == expected) {
                    allMessages!!.removeAt(i)
                    trimmedMessages!!.removeIf { line -> line.parent() == candidate }
                    return true
                }
            }
            return false
        }
        if (allMessages!!.isEmpty()) {
            return false
        }
        val previous = allMessages!!.first()
        if (`axolotlclient$compactNormalize`(previous.content().getString()) != expected) {
            return false
        }
        allMessages!!.removeFirst()
        while (!trimmedMessages!!.isEmpty() && trimmedMessages!!.first().parent() == previous) {
            trimmedMessages!!.removeFirst()
        }
        return true
    }

    /**
     * The streak only survives while the message we last compacted is still the newest one — anything
     * that reshuffles the chat behind our back (clear, server-side delete, state restore, another
     * mod) shows up here as a mismatch and safely breaks the streak.
     */
    @Unique
    private fun `axolotlclient$compactStreakIsHeadOfChat`(): Boolean {
        if (`axolotlclient$compactStreakCount` < 1 || allMessages!!.isEmpty()) {
            return false
        }

        val expected = if (`axolotlclient$compactStreakCount` > 1) {
            "${`axolotlclient$compactStreakBase`} x${`axolotlclient$compactStreakCount`}"
        } else {
            `axolotlclient$compactStreakBase`
        }
        return `axolotlclient$compactNormalize`(allMessages!!.first().content().getString()) == expected
    }

    /**
     * Chat peek (Alpine parity): while the peek key is held in-game, the chat renders at focused
     * height instead of the collapsed unfocused height, so the full history becomes visible without
     * opening the chat screen. Screen-open states never peek (the chat screen already shows all).
     */
    @Inject(method = ["getHeight()I"], at = [At("HEAD")], cancellable = true)
    private fun `axolotlclient$peekFullHeight`(cir: CallbackInfoReturnable<Int>) {
        if (ChatPeek.isActive()) {
            cir.setReturnValue(ChatComponent.getHeight(Minecraft.getInstance().options.chatHeightFocused().get()))
        }
    }

    /**
     * Smooth chat, public render entry: advances the animation once per frame (later passes in
     * the same frame sample dt 0 and stay put) and — while an animation is actually in flight —
     * scissors the drawing to the chat rectangle so the fractional slide cannot bleed over the
     * rest of the HUD (one extra line is drawn above the window during a slide; without the
     * clip it would float over the game view).
     */
    @WrapMethod(method = ["extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent\$DisplayMode;Z)V"])
    private fun `axolotlclient$smoothChatStep`(
        extractor: GuiGraphicsExtractor,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        arg: Int,
        mode: DisplayMode,
        restricted: Boolean,
        original: Operation<Void>
    ) {
        val smooth = AxolotlClient.config() != null && AxolotlClient.config().smoothChat.get()
        if (smooth) {
            val now = Util.getMillis()
            val dt = if (`axolotlclient$smoothLastFrameMs` == 0L) 16L else now - `axolotlclient$smoothLastFrameMs`
            `axolotlclient$smoothLastFrameMs` = now
            `axolotlclient$smoothScrollCurrent` = ChatScrollSmoothing.step(
                `axolotlclient$smoothScrollCurrent`, chatScrollbarPos.toFloat(), dt,
                AxolotlClient.config().smoothChatDuration.get()
            )
        } else if (`axolotlclient$smoothScrollCurrent` != chatScrollbarPos.toFloat()) {
            `axolotlclient$smoothScrollCurrent` = chatScrollbarPos.toFloat()
        }

        if (!smooth || !ChatScrollSmoothing.animating(`axolotlclient$smoothScrollCurrent`, chatScrollbarPos.toFloat())) {
            `axolotlclient$smoothRenderOffset` = 0.0f
            `axolotlclient$smoothExtraTopLine` = false
            original.call(extractor, font, mouseX, mouseY, arg, mode, restricted)
            return
        }

        `axolotlclient$smoothRenderOffset` = ChatScrollSmoothing.pixelOffset(`axolotlclient$smoothScrollCurrent`, getLineHeight().toFloat())
        `axolotlclient$smoothExtraTopLine` = `axolotlclient$smoothRenderOffset` > ChatScrollSmoothing.SNAP_THRESHOLD

        val guiHeight = extractor.guiHeight()
        // All in GUI units (the scissor is transformed by the base pose): vanilla lays the
        // chat out with a 40 px bottom margin; one extra line keeps the queued-messages
        // notice below the chat visible while the slide runs.
        val lineHeightGui = Mth.ceil(getLineHeight() * getScale().toFloat())
        val bottom = guiHeight - 40 + lineHeightGui + 8
        val top = bottom - Mth.ceil(getHeight() * getScale().toFloat()) - 1
        val right = Mth.ceil((Mth.ceil(getWidth() / getScale().toFloat()) + 12) * getScale().toFloat())
        extractor.enableScissor(0, top, right, bottom)
        try {
            original.call(extractor, font, mouseX, mouseY, arg, mode, restricted)
        } finally {
            extractor.disableScissor()
        }
    }

    /**
     * Smooth chat, the pass both render modes and the click-capture path funnel through: line
     * selection runs at the animation's floor while the authoritative scrollbar value is
     * restored afterwards, so vanilla logic (clamping, delete handling, scrollbar state) is
     * untouched.
     */
    @WrapMethod(method = ["extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent\$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent\$DisplayMode;)V"])
    private fun `axolotlclient$smoothChatWindow`(
        graphics: ChatGraphicsAccess,
        guiHeight: Int,
        mouseY: Int,
        mode: DisplayMode,
        original: Operation<Void>
    ) {
        if (`axolotlclient$smoothRenderOffset` == 0.0f) {
            original.call(graphics, guiHeight, mouseY, mode)
            return
        }
        val realScrollbarPos = chatScrollbarPos
        chatScrollbarPos = ChatScrollSmoothing.windowBase(`axolotlclient$smoothScrollCurrent`)
        try {
            original.call(graphics, guiHeight, mouseY, mode)
        } finally {
            chatScrollbarPos = realScrollbarPos
        }
    }

    /**
     * Smooth chat: after vanilla's scale-and-shift pose setup, add the sub-line translation the
     * current frame is at. Applied inside every graphics access (background, focused and
     * click-capture), so hit testing stays in the same coordinate space as the pixels.
     */
    @WrapOperation(
        method = ["extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent\$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent\$DisplayMode;)V"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/ChatComponent\$ChatGraphicsAccess;updatePose(Ljava/util/function/Consumer;)V"
        )]
    )
    private fun `axolotlclient$smoothChatPose`(
        graphics: ChatGraphicsAccess,
        vanillaPose: Consumer<Matrix3x2f>,
        original: Operation<Void>
    ) {
        original.call(graphics, vanillaPose)
        val offset = `axolotlclient$smoothRenderOffset`
        if (offset != 0.0f) {
            graphics.updatePose { matrix: Matrix3x2f -> matrix.translate(0.0f, offset) }
        }
    }

    /**
     * Smooth chat: while a slide is in flight the window needs one extra line above the vanilla
     * range — the line sliding in from (or out through) the top edge. Reimplements vanilla's
     * tiny loop with `end` starting one higher; when the history does not overflow the
     * window the extra index points past the list and is skipped, which makes this identical
     * to vanilla.
     */
    @WrapMethod(method = ["forEachLine"])
    private fun `axolotlclient$smoothChatLines`(
        calculator: ChatComponent.AlphaCalculator,
        consumer: ChatComponent.LineConsumer,
        original: Operation<Int>
    ): Int {
        if (!`axolotlclient$smoothExtraTopLine`) {
            return original.call(calculator, consumer)
        }

        val linesPerPage = getLinesPerPage()
        val vanillaEnd = min(trimmedMessages!!.size - chatScrollbarPos, linesPerPage) - 1
        var count = 0
        var drewExtra = false
        var end = vanillaEnd + 1
        while (end >= 0) {
            val start = end + chatScrollbarPos
            if (start < trimmedMessages!!.size) {
                val line = trimmedMessages!![start]
                val alpha = calculator.calculate(line)
                if (alpha > 1.0E-5f) {
                    count++
                    if (end > vanillaEnd) {
                        drewExtra = true
                    }
                    consumer.accept(line, end, alpha)
                }
            }
            end--
        }
        // Keep the returned visible-line count vanilla-equivalent (the caller sizes the
        // scrollbar thumb from it).
        return count - (if (drewExtra) 1 else 0)
    }

    /** Snap the smooth animation whenever the history is replaced wholesale. */
    @Inject(method = ["clearMessages", "restoreState", "rescaleChat"], at = [At("HEAD")])
    private fun `axolotlclient$snapSmoothScroll`(ci: CallbackInfo) {
        `axolotlclient$smoothScrollCurrent` = chatScrollbarPos.toFloat()
    }

    override fun `axolotlclient$getMessages`(): List<GuiMessage> {
        return allMessages!!
    }

    override fun `axolotlclient$restoreMessages`(messages: List<GuiMessage>) {
        allMessages!!.addAll(messages)
        refreshTrimmedMessages()
        `axolotlclient$smoothScrollCurrent` = chatScrollbarPos.toFloat()
    }

    override fun `axolotlclient$getSmoothScrollCurrent`(): Float {
        return `axolotlclient$smoothScrollCurrent`
    }

    companion object {
        /**
         * Own-name highlight: case-insensitively finds occurrences of the local player's name inside
         * literal text segments of the component tree and tints them gold, preserving every other style
         * (colors, click/hover events) by rebuilding only the nodes on the path to a match. Returns the
         * original component untouched when the name does not occur.
         */
        @Unique
        @JvmStatic
        private fun `axolotlclient$highlightOwnName`(component: Component): Component {
            if (!AxolotlClient.config().chatNameHighlight.get()) {
                return component
            }

            val name = Minecraft.getInstance().getUser().getName()
            if (name == null || name.length < 2) {
                return component
            }

            val needle = name.lowercase(Locale.ROOT)
            val highlighted = `axolotlclient$highlightIn`(component, needle) ?: return component

            if (AxolotlClient.config().chatNamePing.get()) {
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f))
            }
            return highlighted
        }

        /**
         * Recursive worker for [`axolotlclient$highlightOwnName`]; returns `null`
         * when nothing in this subtree matched so untouched branches keep their exact component tree.
         */
        @Unique
        @JvmStatic
        private fun `axolotlclient$highlightIn`(component: Component, needle: String): MutableComponent? {
            val contents = component.getContents()
            if (contents is PlainTextContents && contents.text().lowercase(Locale.ROOT).contains(needle)) {
                val rebuilt = Component.empty().withStyle(component.getStyle())
                `axolotlclient$appendHighlighted`(rebuilt, contents.text(), component.getStyle(), needle)
                for (sibling in component.getSiblings()) {
                    rebuilt.append(sibling.copy())
                }
                return rebuilt
            }

            val siblings = component.getSiblings()
            for (i in siblings.indices) {
                val highlighted = `axolotlclient$highlightIn`(siblings[i], needle)
                if (highlighted != null) {
                    val rebuilt = MutableComponent.create(component.getContents()).withStyle(component.getStyle())
                    for (j in siblings.indices) {
                        rebuilt.append(if (j == i) highlighted else siblings[j].copy())
                    }
                    return rebuilt
                }
            }
            return null
        }

        /**
         * Splits one literal string around case-insensitive matches of the name, appending the untouched
         * segments with the original style (inherited from `parent`) and the matches in gold.
         */
        @Unique
        @JvmStatic
        private fun `axolotlclient$appendHighlighted`(parent: MutableComponent, text: String, style: Style, needle: String) {
            val haystack = text.lowercase(Locale.ROOT)
            var from = 0
            var match = haystack.indexOf(needle, from)
            while (match >= 0) {
                if (match > from) {
                    parent.append(Component.literal(text.substring(from, match)).withStyle(style))
                }
                parent.append(
                    Component.literal(text.substring(match, match + needle.length)).withStyle(style.withColor(ChatFormatting.GOLD))
                )
                from = match + needle.length
                match = haystack.indexOf(needle, from)
            }
            if (from < text.length) {
                parent.append(Component.literal(text.substring(from)).withStyle(style))
            }
        }

        /**
         * Hover highlight: the per-line backdrop fill lambda of the focused chat pass draws a subtle
         * colored overlay on the line currently under the mouse, before the text pass runs. The mouse
         * position is only tracked while the chat screen is open, so this is a no-op in HUD/background
         * mode (where the cursor is grabbed anyway).
         */
        @Inject(method = ["lambda\$extractRenderState\$1"], at = [At("TAIL")])
        @JvmStatic
        private fun `axolotlclient$hoverHighlight`(
            chatBottom: Int,
            entryHeight: Int,
            graphics: ChatGraphicsAccess,
            chatWidth: Int,
            backgroundOpacity: Float,
            line: GuiMessage.Line,
            lineIndex: Int,
            alpha: Float,
            ci: CallbackInfo
        ) {
            if (AxolotlClient.config() == null || !AxolotlClient.config().chatHoverHighlight.get() || graphics !is ChatDrawingFocusedAccessAccessor) {
                return
            }

            val focused = graphics as ChatDrawingFocusedAccessAccessor
            val mouse = focused.`axolotlclient$getLocalMousePos`()
            val top = chatBottom - lineIndex * entryHeight
            if (mouse.y >= (top - entryHeight).toFloat() && mouse.y <= top.toFloat() && mouse.x >= -4f && mouse.x <= (chatWidth + 8).toFloat()) {
                graphics.fill(-4, top - entryHeight, chatWidth + 8, top, AxolotlClient.config().chatHoverHighlightColor.get().toInt())
            }
        }
    }
}
