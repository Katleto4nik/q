package com.gabdab.weboptimizer;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Silent rotation: the camera of the local player never changes.
 * Only the rotation that is sent to the server (= what other players see) is turned.
 */
public final class WebLogic {
    private static final double REACH = 4.5;        // max distance eye -> clicked point
    private static final float ARRIVE_DEG = 0.5f;   // "aimed" threshold for the server-side rotation
    private static final int PLACE_COOLDOWN = 2;    // ticks

    private static final Map<UUID, Vec3d> LAST_POS = new HashMap<>();
    private static final Map<UUID, Vec3d> VELOCITY = new HashMap<>();

    // rotation the server currently believes we have
    private static boolean rotating = false;
    private static float srvYaw;
    private static float srvPitch;

    private static int cooldown = 0;

    private WebLogic() {}

    private record Placement(BlockPos support, Vec3d hit) {}

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity me = mc.player;
        ClientWorld world = mc.world;

        if (me == null || world == null || mc.getNetworkHandler() == null) {
            LAST_POS.clear();
            VELOCITY.clear();
            rotating = false;
            return;
        }

        trackVelocities(world);
        if (cooldown > 0) cooldown--;

        WebConfig cfg = WebConfig.INSTANCE;
        Placement best = null;
        int webSlot = -1;

        boolean active = cfg.enabled && mc.currentScreen == null && mc.interactionManager != null
                && me.isAlive() && !me.isSpectator();
        if (active) {
            webSlot = findHotbar(me.getInventory(), Items.COBWEB);
            if (webSlot >= 0) best = findTarget(mc, me, world);
        }

        if (best == null) {
            releaseRotation(mc, me);
            return;
        }

        // aim point = top face of the block under the enemy
        Vec3d eye = new Vec3d(me.getX(), me.getEyeY(), me.getZ());
        double dx = best.hit().x - eye.x;
        double dy = best.hit().y - eye.y;
        double dz = best.hit().z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

        if (!rotating) {
            srvYaw = me.getYaw();
            srvPitch = me.getPitch();
            rotating = true;
        }

        stepToward(targetYaw, targetPitch, cfg.smoothness);
        sendLook(mc, me, srvYaw, srvPitch);

        // place in the SAME tick the server-side rotation arrives -> no waiting
        if (remaining(targetYaw, targetPitch) <= ARRIVE_DEG && cooldown == 0) {
            place(mc, me, best, webSlot, cfg);
            cooldown = PLACE_COOLDOWN;
        }
    }

    // ------------------------------------------------------------------ target selection

    private static Placement findTarget(MinecraftClient mc, ClientPlayerEntity me, ClientWorld world) {
        Vec3d eye = new Vec3d(me.getX(), me.getEyeY(), me.getZ());
        double lead = leadTicks(mc, me);

        Placement best = null;
        double bestDist = Double.MAX_VALUE;
        for (PlayerEntity other : world.getPlayers()) {
            if (other == me || !other.isAlive() || other.isSpectator() || me.isTeammate(other)) continue;
            if (me.squaredDistanceTo(other) > 49.0) continue;

            // enemy is already in / next to a web -> do nothing for this player
            if (webNear(world, other)) continue;

            Placement p = computePlacement(world, other, lead);
            if (p == null) continue;

            double d = eye.distanceTo(p.hit());
            if (d > REACH || d >= bestDist) continue;
            bestDist = d;
            best = p;
        }
        return best;
    }

    /** true if any cobweb touches the enemy's body or lies right around his feet */
    private static boolean webNear(ClientWorld world, PlayerEntity enemy) {
        Box box = enemy.getBoundingBox().expand(0.9, 0.3, 0.9);
        int x0 = MathHelper.floor(box.minX), x1 = MathHelper.floor(box.maxX);
        int y0 = MathHelper.floor(box.minY), y1 = MathHelper.floor(box.maxY);
        int z0 = MathHelper.floor(box.minZ), z1 = MathHelper.floor(box.maxZ);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    pos.set(x, y, z);
                    if (world.getBlockState(pos).isOf(Blocks.COBWEB)) return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ prediction

    private static void trackVelocities(ClientWorld world) {
        Set<UUID> seen = new HashSet<>();
        for (PlayerEntity p : world.getPlayers()) {
            UUID id = p.getUuid();
            seen.add(id);
            Vec3d now = new Vec3d(p.getX(), p.getY(), p.getZ());
            Vec3d last = LAST_POS.put(id, now);
            if (last == null) continue;

            Vec3d delta = now.subtract(last);
            if (delta.lengthSquared() > 9.0) delta = Vec3d.ZERO; // teleport
            Vec3d old = VELOCITY.getOrDefault(id, Vec3d.ZERO);
            VELOCITY.put(id, old.multiply(0.4).add(delta.multiply(0.6)));
        }
        LAST_POS.keySet().retainAll(seen);
        VELOCITY.keySet().retainAll(seen);
    }

    /** how many ticks into the future we predict (ping + interpolation) */
    private static double leadTicks(MinecraftClient mc, ClientPlayerEntity me) {
        double ping = 0;
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler != null) {
            PlayerListEntry entry = handler.getPlayerListEntry(me.getUuid());
            if (entry != null) ping = entry.getLatency() / 50.0;
        }
        return MathHelper.clamp(ping + 2.5, 2.0, 10.0);
    }

    private static Placement computePlacement(ClientWorld world, PlayerEntity enemy, double lead) {
        Vec3d vel = VELOCITY.getOrDefault(enemy.getUuid(), Vec3d.ZERO);
        double vx = MathHelper.clamp(vel.x, -0.8, 0.8);
        double vz = MathHelper.clamp(vel.z, -0.8, 0.8);

        double px = enemy.getX() + vx * lead;
        double pz = enemy.getZ() + vz * lead;
        BlockPos feet = BlockPos.ofFloored(px, enemy.getY() + 0.001, pz);

        BlockState feetState = world.getBlockState(feet);
        if (feetState.isOf(Blocks.COBWEB)) return null;
        if (!(feetState.isAir() || feetState.isReplaceable())) return null;

        // floor under the predicted position (also works for jumping / falling enemies)
        for (int i = 1; i <= 5; i++) {
            BlockPos support = feet.down(i);
            BlockState s = world.getBlockState(support);
            if (s.isSideSolidFullSquare(world, support, Direction.UP)) {
                BlockPos place = support.up();
                BlockState ps = world.getBlockState(place);
                if (ps.isOf(Blocks.COBWEB)) return null;
                if (!(ps.isAir() || ps.isReplaceable())) return null;
                return new Placement(support, new Vec3d(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5));
            }
            if (s.isOf(Blocks.COBWEB)) return null;
            if (!(s.isAir() || s.isReplaceable())) return null;
        }
        return null;
    }

    // ------------------------------------------------------------------ silent rotation

    /** max degrees per tick: 0 -> 180 (instant), 1000 -> 3 (very slow head turn) */
    private static double maxStepDeg(int smoothness) {
        return 180.0 * Math.pow(3.0 / 180.0, MathHelper.clamp(smoothness, 0, 1000) / 1000.0);
    }

    private static void stepToward(float targetYaw, float targetPitch, int smoothness) {
        float dy = MathHelper.wrapDegrees(targetYaw - srvYaw);
        float dp = targetPitch - srvPitch;
        double dist = Math.sqrt(dy * dy + dp * dp);
        double max = maxStepDeg(smoothness);
        if (dist <= max) {
            srvYaw = targetYaw;
            srvPitch = targetPitch;
        } else {
            double f = max / dist;
            srvYaw += (float) (dy * f);
            srvPitch += (float) (dp * f);
        }
    }

    private static double remaining(float targetYaw, float targetPitch) {
        float dy = MathHelper.wrapDegrees(targetYaw - srvYaw);
        float dp = targetPitch - srvPitch;
        return Math.sqrt(dy * dy + dp * dp);
    }

    /** nothing to do any more: turn the server-side rotation back to the real camera rotation */
    private static void releaseRotation(MinecraftClient mc, ClientPlayerEntity me) {
        if (!rotating) return;

        float realYaw = me.getYaw();
        float realPitch = me.getPitch();
        stepToward(realYaw, realPitch, WebConfig.INSTANCE.smoothness);

        if (remaining(realYaw, realPitch) <= ARRIVE_DEG) {
            sendLook(mc, me, realYaw, realPitch);
            rotating = false;
        } else {
            sendLook(mc, me, srvYaw, srvPitch);
        }
    }

    private static void sendLook(MinecraftClient mc, ClientPlayerEntity me, float yaw, float pitch) {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null) return;
        handler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, me.isOnGround(), me.horizontalCollision));
    }

    // ------------------------------------------------------------------ placing

    private static void place(MinecraftClient mc, ClientPlayerEntity me, Placement p, int webSlot, WebConfig cfg) {
        PlayerInventory inv = me.getInventory();
        int previous = inv.getSelectedSlot();

        inv.setSelectedSlot(webSlot);
        BlockHitResult hit = new BlockHitResult(p.hit(), Direction.UP, p.support(), false);
        mc.interactionManager.interactBlock(me, Hand.MAIN_HAND, hit);
        me.swingHand(Hand.MAIN_HAND);

        if (cfg.returnSword) {
            inv.setSelectedSlot(swordSlotToReturn(inv, previous));
        }
    }

    /** previous slot if it holds a sword, otherwise the first sword in the hotbar, otherwise the previous slot */
    private static int swordSlotToReturn(PlayerInventory inv, int previous) {
        if (inv.getStack(previous).isIn(ItemTags.SWORDS)) return previous;
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) return i;
        }
        return previous;
    }

    private static int findHotbar(PlayerInventory inv, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) return i;
        }
        return -1;
    }
}
