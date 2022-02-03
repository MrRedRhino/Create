package com.simibubi.create.content.logistics.trains.track;

import org.apache.commons.lang3.tuple.Pair;

import com.jozufozu.flywheel.util.Color;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.logistics.trains.BezierConnection;
import com.simibubi.create.content.logistics.trains.ITrackBlock;
import com.simibubi.create.foundation.utility.AngleHelper;
import com.simibubi.create.foundation.utility.Couple;
import com.simibubi.create.foundation.utility.Iterate;
import com.simibubi.create.foundation.utility.Lang;
import com.simibubi.create.foundation.utility.VecHelper;
import com.simibubi.create.foundation.utility.animation.LerpedFloat;
import com.simibubi.create.foundation.utility.animation.LerpedFloat.Chaser;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public class TrackPlacement {

	static class PlacementInfo {
		BezierConnection curve = null;
		boolean valid = false;
		int end1Extent = 0;
		int end2Extent = 0;
		String message = null;

		// for visualisation
		Vec3 end1;
		Vec3 end2;
		Vec3 normal1;
		Vec3 normal2;
		Vec3 axis1;
		Vec3 axis2;
		BlockPos pos1;
		BlockPos pos2;

		public PlacementInfo withMessage(String message) {
			this.message = "track." + message;
			return this;
		}

		public PlacementInfo tooJumbly() {
			curve = null;
			return this;
		}
	}

	static PlacementInfo cached;
	static BlockPos hoveringPos;
	static ItemStack lastItem;

	public static PlacementInfo tryConnect(Level level, BlockPos pos2, BlockState state2, Vec3 lookVec,
		ItemStack stack) {

		if (cached != null && pos2.equals(hoveringPos) && stack.equals(lastItem))
			return cached;

		PlacementInfo info = new PlacementInfo();
		hoveringPos = pos2;
		lastItem = stack;
		cached = info;

		ITrackBlock track = (ITrackBlock) state2.getBlock();
		Vec3 axis2 = track.getTrackAxis(level, pos2, state2);
		Vec3 normal2 = track.getUpNormal(level, pos2, state2)
			.normalize();
		boolean front2 = lookVec.dot(axis2.multiply(1, 0, 1)
			.normalize()) < 0;
		axis2 = axis2.scale(front2 ? -1 : 1);
		Vec3 normedAxis2 = axis2.normalize();
		Vec3 end2 = track.getCurveStart(level, pos2, state2, axis2);

		CompoundTag itemTag = stack.getTag();
		CompoundTag selectionTag = itemTag.getCompound("ConnectingFrom");
		BlockPos pos1 = NbtUtils.readBlockPos(selectionTag.getCompound("Pos"));
		Vec3 axis1 = VecHelper.readNBT(selectionTag.getList("Axis", Tag.TAG_DOUBLE));
		Vec3 normedAxis1 = axis1.normalize();
		Vec3 end1 = VecHelper.readNBT(selectionTag.getList("End", Tag.TAG_DOUBLE));
		Vec3 normal1 = VecHelper.readNBT(selectionTag.getList("Normal", Tag.TAG_DOUBLE));
		boolean front1 = selectionTag.getBoolean("Front");
		BlockState state1 = level.getBlockState(pos1);

		if (level.isClientSide) {
			info.end1 = end1;
			info.end2 = end2;
			info.normal1 = normal1;
			info.normal2 = normal2;
			info.axis1 = axis1;
			info.axis2 = axis2;
		}

		if (pos1.equals(pos2))
			return info.withMessage("second_point");
		if (pos1.distSqr(pos2) > 32 * 32)
			return info.withMessage("too_far")
				.tooJumbly();
		if (!state1.hasProperty(TrackBlock.HAS_TURN))
			return info.withMessage("original_missing");

		if (axis1.dot(end2.subtract(end1)) < 0) {
			axis1 = axis1.scale(-1);
			normedAxis1 = normedAxis1.scale(-1);
			front1 = !front1;
			end1 = track.getCurveStart(level, pos1, state1, axis1);
			if (level.isClientSide) {
				info.end1 = end1;
				info.axis1 = axis1;
			}
		}

		double[] intersect = VecHelper.intersect(end1, end2, normedAxis1, normedAxis2, Axis.Y);
		boolean parallel = intersect == null;
		boolean skipCurve = false;

		if ((parallel && normedAxis1.dot(normedAxis2) > 0) || (!parallel && (intersect[0] < 0 || intersect[1] < 0))) {
			axis2 = axis2.scale(-1);
			normedAxis2 = normedAxis2.scale(-1);
			front2 = !front2;
			end2 = track.getCurveStart(level, pos2, state2, axis2);
			if (level.isClientSide) {
				info.end2 = end2;
				info.axis2 = axis2;
			}
		}

		Vec3 cross2 = normedAxis2.cross(new Vec3(0, 1, 0));

		double a1 = Mth.atan2(normedAxis2.z, normedAxis2.x);
		double a2 = Mth.atan2(normedAxis1.z, normedAxis1.x);
		double angle = a1 - a2;
		double ascend = end2.subtract(end1).y;
		double absAscend = Math.abs(ascend);
		boolean slope = !normal1.equals(normal2);

		if (level.isClientSide) {
			Vec3 offset1 = axis1.scale(info.end1Extent);
			Vec3 offset2 = axis2.scale(info.end2Extent);
			BlockPos targetPos1 = pos1.offset(offset1.x, offset1.y, offset1.z);
			BlockPos targetPos2 = pos2.offset(offset2.x, offset2.y, offset2.z);
			info.curve = new BezierConnection(Couple.create(targetPos1, targetPos2),
				Couple.create(end1.add(offset1), end2.add(offset2)), Couple.create(normedAxis1, normedAxis2),
				Couple.create(normal1, normal2), Couple.create(front1, front2), true);
		}

		// S curve or Straight

		double dist = 0;

		if (parallel) {
			double[] sTest = VecHelper.intersect(end1, end2, normedAxis1, cross2, Axis.Y);
			double t = Math.abs(sTest[0]);
			double u = Math.abs(sTest[1]);

			skipCurve = Mth.equal(u, 0);

			if (!skipCurve && sTest[0] < 0)
				return info.withMessage("perpendicular")
					.tooJumbly();

			if (skipCurve) {
				dist = VecHelper.getCenterOf(pos1)
					.distanceTo(VecHelper.getCenterOf(pos2));
				info.end1Extent = (int) Math.round((dist + 1) / axis1.length());

			} else {
				if (!Mth.equal(ascend, 0))
					return info.withMessage("ascending_s_curve");

				double targetT = u <= 1 ? 3 : u * 2;

				if (t < targetT)
					return info.withMessage("too_sharp");

				// This is for standardising s curve sizes
				if (t > targetT) {
					int correction = (int) ((t - targetT) / axis1.length());
					info.end1Extent = correction / 2 + (correction % 2);
					info.end2Extent = correction / 2;
				}
			}
		}

		// Slope

		if (slope) {
			if (!skipCurve)
				return info.withMessage("slope_turn");
			if (Mth.equal(normal1.dot(normal2), 0))
				return info.withMessage("opposing_slopes");
			if ((axis1.y < 0 || axis2.y > 0) && ascend > 0)
				return info.withMessage("leave_slope_ascending");
			if ((axis1.y > 0 || axis2.y < 0) && ascend < 0)
				return info.withMessage("leave_slope_descending");

			skipCurve = false;
			info.end1Extent = 0;
			info.end2Extent = 0;

			Axis plane = Mth.equal(axis1.x, 0) ? Axis.X : Axis.Z;
			intersect = VecHelper.intersect(end1, end2, normedAxis1, normedAxis2, plane);
			double dist1 = Math.abs(intersect[0] / axis1.length());
			double dist2 = Math.abs(intersect[1] / axis2.length());

			if (dist1 > dist2)
				info.end1Extent = (int) Math.round(dist1 - dist2);
			if (dist2 > dist1)
				info.end2Extent = (int) Math.round(dist2 - dist1);

			double turnSize = Math.min(dist1, dist2);
			if (intersect[0] < 0)
				return info.withMessage("too_sharp")
					.tooJumbly();
			if (turnSize < 2)
				return info.withMessage("too_sharp");

			// This is for standardising curve sizes
			if (turnSize > 2) {
				info.end1Extent += turnSize - 2;
				info.end2Extent += turnSize - 2;
				turnSize = 2;
			}
		}

		// Straight ascend

		if (skipCurve && !Mth.equal(ascend, 0)) {
			int hDistance = info.end1Extent;
			if (axis1.y == 0 || !Mth.equal(absAscend + 1, dist / axis1.length())) {
				info.end1Extent = 0;
				if (hDistance < absAscend * 3)
					return info.withMessage("too_steep");
				if (hDistance > absAscend * 4) {
					int correction = (int) (hDistance - absAscend * 4);
					info.end1Extent = correction / 2 + (correction % 2);
					info.end2Extent = correction / 2;
				}

				skipCurve = false;
			}
		}

		// Turn

		if (!parallel) {
			float absAngle = Math.abs(AngleHelper.deg(angle));
			if (absAngle < 60 || absAngle > 300)
				return info.withMessage("turn_90")
					.tooJumbly();

			intersect = VecHelper.intersect(end1, end2, normedAxis1, normedAxis2, Axis.Y);
			double dist1 = Math.abs(intersect[0]);
			double dist2 = Math.abs(intersect[1]);
			float ex1 = 0;
			float ex2 = 0;

			if (dist1 > dist2)
				ex1 = (float) ((dist1 - dist2) / axis1.length());
			if (dist2 > dist1)
				ex2 = (float) ((dist2 - dist1) / axis2.length());

			double turnSize = Math.min(dist1, dist2);
			boolean ninety = (absAngle + .25f) % 90 < 1;

			if (intersect[0] < 0 || intersect[1] < 0)
				return info.withMessage("too_sharp")
					.tooJumbly();

			int minTurnSize = ninety ? 7 : 3;
			int maxAscend = ninety ? 3 : 2;

			if (turnSize < minTurnSize)
				return info.withMessage("too_sharp");
			if (absAscend > maxAscend)
				return info.withMessage("too_steep");

			// This is for standardising curve sizes
			ex1 += (turnSize - minTurnSize) / axis1.length();
			ex2 += (turnSize - minTurnSize) / axis2.length();
			info.end1Extent = Math.round(ex1);
			info.end2Extent = Math.round(ex2);
			turnSize = minTurnSize;
		}

		Vec3 offset1 = axis1.scale(info.end1Extent);
		Vec3 offset2 = axis2.scale(info.end2Extent);
		BlockPos targetPos1 = pos1.offset(offset1.x, offset1.y, offset1.z);
		BlockPos targetPos2 = pos2.offset(offset2.x, offset2.y, offset2.z);

		info.curve = skipCurve ? null
			: new BezierConnection(Couple.create(targetPos1, targetPos2),
				Couple.create(end1.add(offset1), end2.add(offset2)), Couple.create(normedAxis1, normedAxis2),
				Couple.create(normal1, normal2), Couple.create(front1, front2), true);

		info.valid = true;

		if (level.isClientSide())
			return info;

		for (boolean first : Iterate.trueAndFalse) {
			int extent = first ? info.end1Extent : info.end2Extent;
			Vec3 axis = first ? axis1 : axis2;
			BlockPos pos = first ? pos1 : pos2;
			BlockState state = first ? state1 : state2;

			for (int i = 0; i < extent; i++) {
				Vec3 offset = axis.scale(i);
				BlockPos offsetPos = pos.offset(offset.x, offset.y, offset.z);
				BlockState stateAtPos = level.getBlockState(offsetPos);
				if (stateAtPos.getBlock() != state.getBlock() && stateAtPos.getMaterial()
					.isReplaceable())
					level.setBlock(offsetPos, state, 3);
			}
		}

		info.pos1 = pos1;
		info.pos2 = pos2;
		info.axis1 = axis1;
		info.axis2 = axis2;

		if (info.curve == null)
			return info;

		level.setBlock(targetPos1, state1.setValue(TrackBlock.HAS_TURN, true), 3);
		level.setBlock(targetPos2, state2.setValue(TrackBlock.HAS_TURN, true), 3);
		BlockEntity te1 = level.getBlockEntity(targetPos1);
		BlockEntity te2 = level.getBlockEntity(targetPos2);

		if (!(te1 instanceof TrackTileEntity) || !(te2 instanceof TrackTileEntity))
			return info;

		TrackTileEntity tte1 = (TrackTileEntity) te1;
		TrackTileEntity tte2 = (TrackTileEntity) te2;
		tte1.addConnection(front1, info.curve);
		tte2.addConnection(front2, info.curve.secondary());
		return info;
	}

	static LerpedFloat animation = LerpedFloat.linear()
		.startWithValue(0);

	@OnlyIn(Dist.CLIENT)
	public static void clientTick() {
		LocalPlayer player = Minecraft.getInstance().player;
		ItemStack stack = player.getMainHandItem();
		HitResult hitResult = Minecraft.getInstance().hitResult;
		if (hitResult == null)
			return;
		if (hitResult.getType() != Type.BLOCK)
			return;

		InteractionHand hand = InteractionHand.MAIN_HAND;
		if (!AllBlocks.TRACK.isIn(stack)) {
			stack = player.getOffhandItem();
			hand = InteractionHand.OFF_HAND;
			if (!AllBlocks.TRACK.isIn(stack))
				return;
		}

		if (!stack.hasFoil())
			return;

		TrackBlockItem blockItem = (TrackBlockItem) stack.getItem();
		Level level = player.level;
		BlockHitResult bhr = (BlockHitResult) hitResult;
		BlockPos pos = bhr.getBlockPos();
		BlockState hitState = level.getBlockState(pos);
		if (!(hitState.getBlock() instanceof TrackBlock) && !hitState.getMaterial()
			.isReplaceable()) {
			pos = pos.relative(bhr.getDirection());
			hitState = blockItem.getPlacementState(new UseOnContext(player, hand, bhr));
			if (hitState == null)
				return;
		}

		if (!(hitState.getBlock() instanceof TrackBlock))
			return;

		PlacementInfo info = tryConnect(level, pos, hitState, player.getLookAngle(), stack);
		if (info.valid)
			player.displayClientMessage(Lang.translate("track.valid_connection")
				.withStyle(ChatFormatting.GREEN), true);
		else if (info.message != null)
			player.displayClientMessage(Lang.translate(info.message)
				.withStyle(ChatFormatting.RED), true);

		animation.chase(info.valid ? 1 : 0, 0.25, Chaser.EXP);
		animation.tickChaser();
		if (!info.valid) {
			info.end1Extent = 0;
			info.end2Extent = 0;
		}

		int color = Color.mixColors(0xEA5C2B, 0x95CD41, animation.getValue());
		Vec3 up = new Vec3(0, 4 / 16f, 0);

		{
			Vec3 v1 = info.end1;
			Vec3 a1 = info.axis1.normalize();
			Vec3 n1 = info.normal1.cross(a1)
				.scale(15 / 16f);
			Vec3 o1 = a1.scale(0.125f);
			Vec3 ex1 =
				a1.scale((info.end1Extent - (info.curve == null && info.end1Extent > 0 ? 2 : 0)) * info.axis1.length());
			line(1, v1.add(n1)
				.add(up), o1, ex1);
			line(2, v1.subtract(n1)
				.add(up), o1, ex1);

			Vec3 v2 = info.end2;
			Vec3 a2 = info.axis2.normalize();
			Vec3 n2 = info.normal2.cross(a2)
				.scale(15 / 16f);
			Vec3 o2 = a2.scale(0.125f);
			Vec3 ex2 = a2.scale(info.end2Extent * info.axis2.length());
			line(3, v2.add(n2)
				.add(up), o2, ex2);
			line(4, v2.subtract(n2)
				.add(up), o2, ex2);
		}

		BezierConnection bc = info.curve;
		if (bc == null)
			return;

		Vec3 previous1 = null;
		Vec3 previous2 = null;
		int railcolor = color;
		int segCount = bc.getSegmentCount();

		float s = animation.getValue() * 7 / 8f + 1 / 8f;
		float lw = animation.getValue() * 1 / 16f + 1 / 16f;
		Vec3 end1 = bc.starts.getFirst();
		Vec3 end2 = bc.starts.getSecond();
		Vec3 finish1 = end1.add(bc.axes.getFirst()
			.scale(bc.getHandleLength()));
		Vec3 finish2 = end2.add(bc.axes.getSecond()
			.scale(bc.getHandleLength()));
		String key = "curve";

		for (int i = 0; i <= segCount; i++) {
			float t = i / (float) segCount;
			Vec3 result = VecHelper.bezier(end1, end2, finish1, finish2, t);
			Vec3 derivative = VecHelper.bezierDerivative(end1, end2, finish1, finish2, t)
				.normalize();
			Vec3 normal = bc.getNormal(t)
				.cross(derivative)
				.scale(15 / 16f);
			Vec3 rail1 = result.add(normal)
				.add(up);
			Vec3 rail2 = result.subtract(normal)
				.add(up);

			if (previous1 != null) {
				Vec3 middle1 = rail1.add(previous1)
					.scale(0.5f);
				Vec3 middle2 = rail2.add(previous2)
					.scale(0.5f);
				CreateClient.OUTLINER
					.showLine(Pair.of(key, i * 2), VecHelper.lerp(s, middle1, previous1),
						VecHelper.lerp(s, middle1, rail1))
					.colored(railcolor)
					.disableNormals()
					.lineWidth(lw);
				CreateClient.OUTLINER
					.showLine(Pair.of(key, i * 2 + 1), VecHelper.lerp(s, middle2, previous2),
						VecHelper.lerp(s, middle2, rail2))
					.colored(railcolor)
					.disableNormals()
					.lineWidth(lw);
			}

			previous1 = rail1;
			previous2 = rail2;
		}

	}

	@OnlyIn(Dist.CLIENT)
	private static void line(int id, Vec3 v1, Vec3 o1, Vec3 ex) {
		int color = Color.mixColors(0xEA5C2B, 0x95CD41, animation.getValue());
		CreateClient.OUTLINER.showLine(Pair.of("start", id), v1.subtract(o1), v1.add(ex))
			.lineWidth(1 / 8f)
			.disableNormals()
			.colored(color);
	}

}