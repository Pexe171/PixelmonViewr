package br.com.pixelmontracker.client;

import br.com.pixelmontracker.PixelmonTracker;
import com.pixelmonmod.pixelmon.client.storage.ClientStorageManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class HealerWorkflow {
    private static String goCommand = "warp healer";
    private static String backCommand = "back";
    private static int searchRadius = 16;
    private static Stage stage = Stage.IDLE;
    private static String status = "parado";
    private static Vec3 origin;
    private static BlockPos healer;
    private static int ticks;

    private HealerWorkflow() {}

    static String goCommand() { return goCommand; }
    static String backCommand() { return backCommand; }
    static String status() { return status; }
    static boolean running() { return stage != Stage.IDLE; }

    static void configure(String go, String back) {
        goCommand = normalizeCommand(go);
        backCommand = normalizeCommand(back);
    }

    static void start() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || goCommand.isBlank() || backCommand.isBlank()) {
            message("Configure os comandos de ida e volta");
            return;
        }
        if (AutoTrainer.isEnabled()) AutoTrainer.setEnabled(false);
        stopMovement(minecraft);
        origin = minecraft.player.position();
        healer = null;
        ticks = 0;
        stage = Stage.GOING;
        sendCommand(minecraft, goCommand);
        status = "indo ao healer";
        message("Workflow iniciado");
        PixelmonTracker.LOGGER.info("Healer workflow started: /{} -> /{}", goCommand, backCommand);
    }

    static void stop() {
        stopMovement(Minecraft.getInstance());
        stage = Stage.IDLE;
        healer = null;
        ticks = 0;
        status = "parado";
        message("Workflow interrompido");
    }

    static void tick(Minecraft minecraft) {
        if (stage == Stage.IDLE || minecraft.player == null || minecraft.level == null) return;
        ticks++;
        if (ticks > 20 * 45) {
            fail("tempo limite excedido");
            return;
        }

        switch (stage) {
            case GOING -> {
                stopMovement(minecraft);
                if (ticks >= 50) {
                    stage = Stage.FINDING;
                    ticks = 0;
                    status = "procurando healer";
                }
            }
            case FINDING -> findAndUseHealer(minecraft);
            case HEALING -> {
                stopMovement(minecraft);
                status = "aguardando cura da equipe";
                if (partyFullyHealed()) {
                    sendCommand(minecraft, backCommand);
                    stage = Stage.RETURNING;
                    ticks = 0;
                    status = "voltando ao local anterior";
                } else if (ticks % 80 == 0 && healer != null) {
                    interact(minecraft, healer);
                }
            }
            case RETURNING -> {
                stopMovement(minecraft);
                if (ticks >= 60 || (origin != null && minecraft.player.position().distanceTo(origin) < 8.0)) {
                    stage = Stage.IDLE;
                    status = "concluido";
                    message("Equipe curada; retorno concluido");
                }
            }
            case IDLE -> { }
        }
    }

    private static void findAndUseHealer(Minecraft minecraft) {
        if (healer == null || !isHealer(minecraft, healer)) {
            healer = findNearestHealer(minecraft);
        }
        if (healer == null) {
            stopMovement(minecraft);
            status = "healer nao encontrado (raio " + searchRadius + ")";
            return;
        }

        Vec3 target = Vec3.atCenterOf(healer);
        double distance = minecraft.player.position().distanceTo(target);
        lookAt(minecraft, target);
        if (distance > 3.6) {
            status = "indo ate o healer (" + Math.round(distance) + "m)";
            minecraft.options.keyUp.setDown(true);
            minecraft.options.keySprint.setDown(distance > 8.0);
            minecraft.options.keyJump.setDown(minecraft.player.horizontalCollision);
            return;
        }

        stopMovement(minecraft);
        interact(minecraft, healer);
        stage = Stage.HEALING;
        ticks = 0;
        status = "healer acionado";
    }

    private static BlockPos findNearestHealer(Minecraft minecraft) {
        BlockPos center = minecraft.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-searchRadius, -6, -searchRadius),
                center.offset(searchRadius, 6, searchRadius))) {
            if (!minecraft.level.hasChunkAt(pos) || !isHealer(minecraft, pos)) continue;
            double distance = pos.distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    private static boolean isHealer(Minecraft minecraft, BlockPos pos) {
        String id = BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(pos).getBlock()).getPath();
        return id.contains("healer") || id.contains("healing_machine");
    }

    private static void interact(Minecraft minecraft, BlockPos pos) {
        if (minecraft.gameMode == null) return;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND, hit);
        minecraft.player.swing(InteractionHand.MAIN_HAND);
        PixelmonTracker.LOGGER.info("Healer workflow interacted with block at {}", pos);
    }

    private static boolean partyFullyHealed() {
        if (ClientStorageManager.party() == null || ClientStorageManager.party().getTeam().isEmpty()) return false;
        return ClientStorageManager.party().getTeam().stream()
                .allMatch(pokemon -> pokemon.getHealth() >= pokemon.getMaxHealth());
    }

    private static void lookAt(Minecraft minecraft, Vec3 target) {
        Vec3 eye = minecraft.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        minecraft.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        minecraft.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, horizontal)));
    }

    private static void sendCommand(Minecraft minecraft, String command) {
        minecraft.player.connection.sendCommand(normalizeCommand(command));
    }

    private static String normalizeCommand(String command) {
        String value = command == null ? "" : command.trim();
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static void fail(String reason) {
        PixelmonTracker.LOGGER.warn("Healer workflow stopped: {}", reason);
        stopMovement(Minecraft.getInstance());
        stage = Stage.IDLE;
        status = "erro: " + reason;
        message("Workflow parou: " + reason);
    }

    private static void stopMovement(Minecraft minecraft) {
        if (minecraft == null) return;
        minecraft.options.keyUp.setDown(false);
        minecraft.options.keySprint.setDown(false);
        minecraft.options.keyJump.setDown(false);
    }

    private static void message(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("[Workflow] " + text), true);
        }
    }

    private enum Stage { IDLE, GOING, FINDING, HEALING, RETURNING }
}
