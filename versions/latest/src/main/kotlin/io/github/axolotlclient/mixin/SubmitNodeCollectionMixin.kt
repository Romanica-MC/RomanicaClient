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

package io.github.axolotlclient.mixin

import com.mojang.blaze3d.vertex.PoseStack
import io.github.axolotlclient.AxolotlClient
import io.github.axolotlclient.AxolotlClientConfigCommon.NametagBackgroundMode
import io.github.axolotlclient.modules.hypixel.LevelHead
import io.github.axolotlclient.util.BadgeFeatureRenderer
import io.github.axolotlclient.util.duck.SubmitNodeCollectorExtension
import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollection
import net.minecraft.client.renderer.feature.TextFeatureRenderer
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.ModifyArg
import org.spongepowered.asm.mixin.injection.ModifyArgs
import org.spongepowered.asm.mixin.injection.invoke.arg.Args

@Mixin(SubmitNodeCollection::class)
abstract class SubmitNodeCollectionMixin : SubmitNodeCollectorExtension {

    @Shadow
    @Final
    var nameTags: SimpleFeatureRenderPhase? = null

    @Shadow
    @Final
    var seeThrough: TranslucentFeatureRenderPhase? = null

    @Shadow
    protected abstract fun submitNameTagPart(par1: TextFeatureRenderer.Submit)

    @ModifyArg(
        method = ["submitNameTag"],
        at = [At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;nameTag(Lorg/joml/Matrix4f;FFLnet/minecraft/util/FormattedCharSequence;IIILnet/minecraft/client/gui/Font\$DisplayMode;)Lnet/minecraft/client/renderer/feature/TextFeatureRenderer\$Submit;"
        )],
        index = 6
    )
    private fun bgColor(color: Int): Int {
        return when (AxolotlClient.config().nametagBackground.get()) {
            NametagBackgroundMode.NONE -> 0
            NametagBackgroundMode.TRANSLUCENT -> color
            NametagBackgroundMode.OPAQUE -> ARGB.opaque(color)
        }
    }

    @ModifyArgs(method = ["submitNameTag"], at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V")])
    private fun `axolotlclient$scaleNametag`(args: Args) {
        val scale = AxolotlClient.config().nametagScale.get().toFloat()
        if (scale != 1.0f) {
            args.set(0, (args.get(0) as Float) * scale)
            args.set(1, (args.get(1) as Float) * scale)
            args.set(2, (args.get(2) as Float) * scale)
        }
    }

    override fun `axolotlclient$submitLevelHead`(
        poseStack: PoseStack,
        nameTagAttachment: Vec3?,
        offset: Int,
        name: Component,
        seeThrough: Boolean,
        lightCoords: Int,
        camera: CameraRenderState
    ) {
        if (nameTagAttachment != null) {
            val minecraft = Minecraft.getInstance()
            val scale = 0.025f * `axolotlclient$nametagScale`()
            poseStack.pushPose()
            poseStack.translate(nameTagAttachment.x, nameTagAttachment.y + 0.5, nameTagAttachment.z)
            poseStack.rotate(camera.orientation)
            poseStack.scale(scale, -scale, scale)
            val pose = Matrix4f(poseStack.last().pose())
            val x = -minecraft.font.width(name) / 2.0f
            val backgroundColor = if (LevelHead.getInstance().background.get()) ARGB.color(
                minecraft.gameRenderer.gameRenderState().optionsRenderState.getBackgroundOpacity(0.25f), -16777216
            ) else 0
            val nameSequence = name.getVisualOrderText()
            if (seeThrough) {
                submitNameTagPart(
                    nameTag(
                        pose, x, offset.toFloat(), nameSequence,
                        LightCoordsUtil.lightCoordsWithEmission(lightCoords, 2), -1, 0, Font.DisplayMode.NORMAL
                    )
                )
                this.seeThrough!!.submit(
                    nameTag(
                        pose, x, offset.toFloat(), nameSequence,
                        lightCoords, -2130706433, backgroundColor, Font.DisplayMode.SEE_THROUGH
                    )
                )
            } else {
                submitNameTagPart(
                    nameTag(
                        pose, x, offset.toFloat(), nameSequence,
                        lightCoords, -2130706433, backgroundColor, Font.DisplayMode.NORMAL
                    )
                )
            }
            poseStack.popPose()
        }
    }

    override fun `axolotlclient$submitBadge`(
        poseStack: PoseStack,
        nameTagAttachment: Vec3?,
        offset: Int,
        name: Component,
        seeThrough: Boolean,
        lightCoords: Int,
        camera: CameraRenderState
    ) {
        if (nameTagAttachment != null && seeThrough) {
            val mc = Minecraft.getInstance()
            val scale = 0.025f * `axolotlclient$nametagScale`()
            poseStack.pushPose()
            poseStack.translate(nameTagAttachment.x, nameTagAttachment.y + 0.5, nameTagAttachment.z)
            poseStack.rotate(camera.orientation)
            poseStack.scale(scale, -scale, scale)
            val pose = Matrix4f(poseStack.last().pose())
            val nameStartX = -mc.font.width(name) / 2.0f
            if (AxolotlClient.config().customBadge.get()) {
                val badgeText = Component.literal(AxolotlClient.config().badgeText.get())
                val x = nameStartX - (mc.font.width(badgeText) + 4)
                submitNameTagPart(
                    nameTag(
                        pose, x, offset.toFloat(), badgeText.getVisualOrderText(),
                        LightCoordsUtil.lightCoordsWithEmission(lightCoords, 2), -1, 0, Font.DisplayMode.NORMAL
                    )
                )
            } else {
                val x = nameStartX - 10
                (this as FabricOrderedSubmitNodeCollector).submitCustom(
                    SubmitRenderPhases.NAME_TAGS, BadgeFeatureRenderer.Submit(pose, x, offset)
                )
            }
            poseStack.popPose()
        }
    }

    companion object {
        @Shadow
        private fun nameTag(
            par1: Matrix4f,
            par2: Float,
            par3: Float,
            par4: FormattedCharSequence,
            par5: Int,
            par6: Int,
            par7: Int,
            par8: Font.DisplayMode
        ): TextFeatureRenderer.Submit = throw UnsupportedOperationException("Implemented via mixin")

        @JvmStatic
        @ModifyArg(
            method = ["nameTag"],
            at = [At(
                value = "INVOKE",
                target = "Lnet/minecraft/client/renderer/feature/TextFeatureRenderer\$Content\$Text;<init>(FFLnet/minecraft/util/FormattedCharSequence;ZIII)V"
            )],
            index = 3
        )
        private fun enableShadows(shadow: Boolean): Boolean {
            return AxolotlClient.config().useShadows.get() || shadow
        }

        @JvmStatic
        private fun `axolotlclient$nametagScale`(): Float {
            return AxolotlClient.config().nametagScale.get().toFloat()
        }
    }
}
