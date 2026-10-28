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

package io.github.axolotlclient;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.axolotlclient.modules.wecui.WESelectionHolder;
import io.github.axolotlclient.modules.wecui.WESelectionRenderer;
import io.github.axolotlclient.modules.wecui.WorldEditCui;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Wire-contract test for the WorldEdit CUI module, headless like ImperiumProtocolGameTest.
 * The messages pinned here are the exact lines WorldEdit's server side emits
 * (com.sk89q.worldedit.internal.cui: SelectionShapeEvent/SelectionPointEvent/
 * SelectionEllipsoidPointEvent) plus the legacy one-shot cuboid line. Guards:
 * the bare-UTF-8 codec (same class of bug as the ImperiumMC handshake — NBT-framed
 * codecs disconnect on plugin-channel bytes), the event parser, and the grid geometry.
 */
public class WorldEditCuiGameTest implements FabricClientGameTest {

	private static final WESelectionRenderer.Palette PALETTE =
		new WESelectionRenderer.Palette(0xFFCC4C4C, 0xFF33CC33, 0xFF3333CC);

	@Override
	public void runTest(ClientGameTestContext context) {
		if (ImperiumGametestSubsets.ci()) return; // headless "ci" subset: this suite is render-heavy, see ImperiumGametestSubsets
		GametestBookkeeping.executed(getClass().getSimpleName());
		codecDecodesBareUtf8AndConsumesEverything();
		codecEncodesWithoutFraming();
		handshakeEncodesToVersionLine();
		modernCuboidEventsBuildSelection();
		legacyOneShotCuboidLineBuildsSelection();
		ellipsoidEventsBuildSelection();
		unknownShapeClearsAndGarbageIsIgnored();
		cuboidGeometryMatchesClassicGrid();
		incompleteSelectionRendersMarkerOnly();
	}

	private void codecDecodesBareUtf8AndConsumesEverything() {
		ByteBuf buf = Unpooled.wrappedBuffer("s|cuboid".getBytes(StandardCharsets.UTF_8));
		WorldEditCui.ClientboundCuiPayload payload = WorldEditCui.ClientboundCuiPayload.CODEC.decode(buf);
		check(buf.readableBytes() == 0, "decode must consume the whole buffer (plugin messages carry no framing)");
		check("s|cuboid".equals(payload.message()), "bare UTF-8 text must survive the decode untouched");
	}

	private void codecEncodesWithoutFraming() {
		ByteBuf buf = Unpooled.buffer();
		WorldEditCui.ServerboundCuiPayload.CODEC.encode(buf, new WorldEditCui.ServerboundCuiPayload("v|4"));
		byte[] out = new byte[buf.readableBytes()];
		buf.readBytes(out);
		check(new String(out, StandardCharsets.UTF_8).equals("v|4"), "encode must be raw bytes, no NBT/length prefix");
	}

	private void handshakeEncodesToVersionLine() {
		ByteBuf buf = Unpooled.buffer();
		WorldEditCui.ServerboundCuiPayload.CODEC.encode(buf, new WorldEditCui.ServerboundCuiPayload("v|4"));
		// WorldEdit's LocalSession.handleCUIInitializationMessage only accepts "v|<number>".
		check(buf.getByte(0) == 'v' && buf.getByte(1) == '|', "handshake must start with 'v|' for WorldEdit to accept it");
	}

	// WorldEdit 7.x sends s|cuboid, then p|0|min|area and p|1|max|area.
	private void modernCuboidEventsBuildSelection() {
		WESelectionHolder selection = fresh();
		feed(selection, "s|cuboid");
		check(!selection.isComplete(), "fresh selection must be incomplete before any point");
		feed(selection, "p|0|10|64|-5|150");
		check(!selection.isComplete() && selection.getPoint0() != null, "one point keeps the selection incomplete");
		feed(selection, "p|1|20|70|7|1500");
		check(selection.isComplete(), "both points complete the cuboid");
		check(selection.getShape() == WESelectionHolder.Shape.CUBOID, "shape must be cuboid");
		check(selection.getPoint0()[0] == 10 && selection.getPoint0()[2] == -5, "point 0 must keep server coordinates");
		check(selection.getPoint1()[1] == 70, "point 1 must keep server coordinates");
	}

	// Legacy one-shot line, still sent by older/plugin-based implementations:
	// p|world|minX|minY|minZ|maxX|maxY|maxZ|cuboid
	private void legacyOneShotCuboidLineBuildsSelection() {
		WESelectionHolder selection = fresh();
		feed(selection, "p|world|0|60|0|4|64|4|cuboid");
		check(selection.isComplete(), "legacy line alone must complete a cuboid");
		check("world".equals(selection.getWorldId()), "legacy world id must be stored");
		check(selection.getPoint0()[0] == 0 && selection.getPoint1()[0] == 4, "legacy corners must parse");
		// Legacy second-point update without id.
		feed(selection, "p2|8|80|8");
		check(selection.getPoint1()[0] == 8 && selection.getPoint1()[2] == 8, "legacy p2 must move the second corner");
	}

	// Ellipsoid: s|ellipsoid, e|0|center, e|1|radii (p|0|center|area also sets the center).
	private void ellipsoidEventsBuildSelection() {
		WESelectionHolder selection = fresh();
		feed(selection, "s|ellipsoid");
		feed(selection, "p|0|100|64|100|5");
		check(!selection.isComplete(), "center alone must not complete the ellipsoid");
		feed(selection, "e|1|3.0|5.0|2.5");
		check(selection.isComplete(), "radii complete the ellipsoid");
		check(selection.getPoint1()[0] == 3.0 && selection.getPoint1()[2] == 2.5, "radii must keep fractional values");
	}

	private void unknownShapeClearsAndGarbageIsIgnored() {
		WESelectionHolder selection = fresh();
		feed(selection, "s|cuboid");
		feed(selection, "p|0|0|0|0|1");
		check(selection.hasSelection(), "cuboid must be selected");
		feed(selection, "s|someNewShape");
		check(!selection.hasSelection(), "unknown shape id must clear the selection");
		// Malformed input must never throw: wrong arity, junk numbers, unknown event ids, "+" prefix.
		feed(selection, "p|notanumber|1|2|3|4");
		feed(selection, "p|0");
		feed(selection, "nonsense");
		feed(selection, "");
		feed(selection, "+s|cuboid|1b16a85a-d538-4a21-8b4e-c502a9a8b7df");
		check(selection.hasSelection(), "multi-selection event must still register the shape");
		check(selection.isComplete() == false, "multi s event carries no points");
	}

	// The classic look: 12 wireframe edges + grid lines on the three min faces.
	private void cuboidGeometryMatchesClassicGrid() {
		WESelectionHolder selection = fresh();
		feed(selection, "s|cuboid");
		feed(selection, "p|0|0|64|0|0");
		feed(selection, "p|1|9|73|9|0");
		List<WESelectionRenderer.Segment> segments =
			WESelectionRenderer.buildSegments(selection, PALETTE, true);
		// 12 edges per box: selection wireframe + 2 corner markers.
		long boxes = segments.stream().filter(s -> s.color() == PALETTE.grid()).count();
		long minMarker = segments.stream().filter(s -> s.color() == PALETTE.minCorner()).count();
		long maxMarker = segments.stream().filter(s -> s.color() == PALETTE.maxCorner()).count();
		check(minMarker == 12 && maxMarker == 12, "each corner marker is exactly one wireframe box");
		// 10x10x10 selection: 9 interior lines per axis, 2 faces each -> 9*2*3 = 54 grid lines
		// + 12 wireframe edges.
		check(boxes == 12 + 54, "grid must draw " + (12 + 54) + " grid-coloured segments, got " + boxes);
		// A grid line must actually sit on a min face: (1, 64, 0) -> (1, 64, 10) on the y-min face.
		check(segments.stream().anyMatch(s -> s.x1() == 1 && s.y1() == 64 && s.z1() == 0
			&& s.x2() == 1 && s.y2() == 64 && s.z2() == 10), "interior z-line on the minY face must exist");
		// Without the grid only the wireframe + markers remain.
		List<WESelectionRenderer.Segment> wireframeOnly =
			WESelectionRenderer.buildSegments(selection, PALETTE, false);
		check(wireframeOnly.stream().filter(s -> s.color() == PALETTE.grid()).count() == 12,
			"show_grid off must leave the 12 wireframe edges only");
	}

	private void incompleteSelectionRendersMarkerOnly() {
		WESelectionHolder selection = fresh();
		feed(selection, "s|cuboid");
		feed(selection, "p|1|7|70|7|1");
		List<WESelectionRenderer.Segment> segments =
			WESelectionRenderer.buildSegments(selection, PALETTE, true);
		check(segments.size() == 12, "incomplete selection renders the single corner marker only");
		check(segments.getFirst().color() == PALETTE.maxCorner(), "marker must use the corner's colour");
	}

	private static WESelectionHolder fresh() {
		WESelectionHolder selection = WESelectionHolder.getInstance();
		selection.clear();
		return selection;
	}

	/** Applies a raw wire line exactly as the network receiver would after decoding. */
	private static void feed(WESelectionHolder selection, String message) {
		boolean multi = message.startsWith("+");
		String body = multi ? message.substring(1) : message;
		String[] split = body.split("\\|", -1);
		List<String> args = split.length > 1
			? List.of(java.util.Arrays.copyOfRange(split, 1, split.length))
			: List.of();
		selection.handle(multi, split[0], args);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
