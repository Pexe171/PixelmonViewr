package br.com.pixelmontracker.client;

import br.com.pixelmontracker.PixelmonTracker;
import com.pixelmonmod.pixelmon.api.battles.AttackCategory;
import com.pixelmonmod.pixelmon.api.battles.BattleMode;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.api.pokemon.stats.Moveset;
import com.pixelmonmod.pixelmon.battles.attacks.Attack;
import com.pixelmonmod.pixelmon.client.ClientProxy;
import com.pixelmonmod.pixelmon.client.gui.battles.ClientBattleManager;
import com.pixelmonmod.pixelmon.client.gui.battles.PixelmonClientData;
import com.pixelmonmod.pixelmon.client.keybindings.SendPokemonKey;
import com.pixelmonmod.pixelmon.client.storage.ClientStorageManager;
import com.pixelmonmod.pixelmon.entities.pixelmon.PixelmonEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

final class AutoTrainer {
    private static final double SEARCH_RANGE = 64.0;
    private static final double ENGAGE_RANGE = 3.5;
    private static final int MAX_LEVEL_ADVANTAGE = 5;
    private static final KeyMapping TOGGLE = new KeyMapping(
            "key.pixelmontracker.autotrainer",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            "key.categories.pixelmontracker"
    );

    private static boolean enabled;
    private static boolean protectRare = true;
    private static UUID targetId;
    private static String targetName = "";
    private static String state = "desligado";
    private static int actionCooldown;
    private static int stuckTicks;
    private static int targetTicks;
    private static double lastDistance = Double.MAX_VALUE;
    private static int lastBattleTurn = Integer.MIN_VALUE;
    private static int lastStrategicSwitchTurn = -1000;
    private static int activeBattleController = Integer.MIN_VALUE;
    private static UUID trainingPokemonId;
    private static boolean trainingSwitchDone;
    private static List<BlockPos> route = List.of();
    private static int routeIndex;
    private static int routeRefreshTicks;
    private static UUID routeTargetId;

    private AutoTrainer() {
    }

    static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
    }

    static boolean isEnabled() {
        return enabled;
    }

    static boolean isProtectingRare() {
        return protectRare;
    }

    static List<Pokemon> partySnapshot() {
        if (ClientStorageManager.party() == null) {
            return List.of();
        }
        return new ArrayList<>(ClientStorageManager.party().getTeam());
    }

    static UUID trainingPokemonId() {
        return trainingPokemonId;
    }

    static void selectTrainingPokemon(UUID pokemonId) {
        trainingPokemonId = pokemonId;
        Pokemon pokemon = selectedTrainingPokemon();
        if (pokemon == null) {
            PixelmonTracker.LOGGER.info("Auto Trainer switch training disabled");
            message("Treino por troca desativado");
            return;
        }
        PixelmonTracker.LOGGER.info("Auto Trainer trainee selected: {} ({}) level {}",
                pokemon.getDisplayName().getString(), pokemon.getUUID(), pokemon.getPokemonLevel());
        message("Pokemon em treinamento: " + pokemon.getDisplayName().getString()
                + " Lv." + pokemon.getPokemonLevel());
    }

    private static Pokemon selectedTrainingPokemon() {
        if (trainingPokemonId == null || ClientStorageManager.party() == null) {
            return null;
        }
        return ClientStorageManager.party().get(trainingPokemonId);
    }

    static void toggleProtectRare() {
        protectRare = !protectRare;
        PixelmonTracker.LOGGER.info("Auto Trainer protectRare={}", protectRare);
        message("Proteger raros: " + (protectRare ? "ativado" : "desativado"));
    }

    static String hudLine() {
        return enabled ? "AUTO TRAINER: " + state : "";
    }

    static void toggle() {
        setEnabled(!enabled);
    }

    static void setEnabled(boolean value) {
        enabled = value;
        targetId = null;
        targetName = "";
        state = enabled ? "procurando alvo seguro" : "desligado";
        actionCooldown = 0;
        stuckTicks = 0;
        targetTicks = 0;
        lastDistance = Double.MAX_VALUE;
        lastBattleTurn = Integer.MIN_VALUE;
        lastStrategicSwitchTurn = -1000;
        activeBattleController = Integer.MIN_VALUE;
        trainingSwitchDone = false;
        clearRoute();
        releaseMovement(Minecraft.getInstance());
        PixelmonTracker.LOGGER.info("Auto Trainer {} (protectRare={})", enabled ? "enabled" : "disabled", protectRare);
        message(enabled ? "Auto Trainer ligado (J para desligar)" : "Auto Trainer desligado");
    }

    static void tick(Minecraft minecraft) {
        try {
            tickInternal(minecraft);
        } catch (RuntimeException error) {
            enabled = false;
            releaseMovement(minecraft);
            PixelmonTracker.LOGGER.error("Auto Trainer stopped after an unexpected error", error);
            message("Erro detectado; Auto Trainer desligado. Veja latest.log");
        }
    }

    private static void tickInternal(Minecraft minecraft) {
        while (TOGGLE.consumeClick()) {
            toggle();
        }
        if (!enabled) {
            return;
        }
        if (minecraft.player == null || minecraft.level == null) {
            releaseMovement(minecraft);
            return;
        }
        if (actionCooldown > 0) {
            actionCooldown--;
        }

        ClientBattleManager battle = ClientProxy.battleManager;
        if (handleLevelUpScreen(minecraft, battle)) {
            return;
        }
        if (battle != null && battle.isBattling() && !battle.battleEnded) {
            releaseMovement(minecraft);
            handleBattle(battle);
            return;
        }

        Screen screen = minecraft.screen;
        if (screen != null) {
            releaseMovement(minecraft);
            state = "pausado: feche o menu";
            return;
        }

        if (ClientStorageManager.party() == null || ClientStorageManager.party().countAblePokemon() <= 0) {
            releaseMovement(minecraft);
            state = "parado: equipe sem Pokemon apto";
            return;
        }

        PixelmonEntity target = findTarget(minecraft);
        if (target == null) {
            releaseMovement(minecraft);
            targetId = null;
            targetName = "";
            state = "procurando Pokemon selvagem seguro";
            return;
        }

        targetId = target.getUUID();
        targetName = target.getLocalizedName();
        targetTicks++;
        double distance = minecraft.player.distanceTo(target);
        boolean clearShot = minecraft.player.hasLineOfSight(target);
        if (distance > ENGAGE_RANGE || !clearShot) {
            moveToward(minecraft, target, distance);
            state = clearShot
                    ? "seguindo rota ate " + targetName + " (" + Math.round(distance) + "m)"
                    : "contornando obstaculo ate " + targetName;
            return;
        }

        clearRoute();
        lookAt(minecraft, target.position().add(0.0, target.getBbHeight() * 0.55, 0.0));
        releaseMovement(minecraft);
        state = "iniciando batalha com " + targetName;
        if (actionCooldown <= 0) {
            Pokemon trainee = selectedTrainingPokemon();
            if (trainingPokemonId != null && (trainee == null || !trainee.canBattle())) {
                state = "treinando indisponivel/desmaiado";
                PixelmonTracker.LOGGER.warn("Auto Trainer cannot start switch training: selected trainee is unavailable");
                actionCooldown = 40;
                return;
            }
            if (trainee != null) {
                int slot = ClientStorageManager.party().getSlot(trainee.getUUID());
                // Complex runs a transformed Pixelmon build where the convenience
                // overload tries to call a stripped sendClientUpdateSelectedPacket
                // method. The throw packet already contains the selected slot, so
                // keep this update local and avoid that incompatible call.
                ClientStorageManager.party().setSelectedSlot(slot, false);
                trainingSwitchDone = false;
                activeBattleController = Integer.MIN_VALUE;
                PixelmonTracker.LOGGER.info("Auto Trainer sending trainee {} ({}) from party slot {}",
                        trainee.getDisplayName().getString(), trainee.getUUID(), slot + 1);
            }
            PixelmonTracker.LOGGER.info("Auto Trainer engaging {} ({}) at {} blocks",
                    targetName, target.getUUID(), Math.round(distance * 10.0) / 10.0);
            SendPokemonKey.sendPokemon();
            actionCooldown = 60;
        }
    }

    private static boolean handleLevelUpScreen(Minecraft minecraft, ClientBattleManager battle) {
        Screen screen = minecraft.screen;
        if (!(screen instanceof com.pixelmonmod.pixelmon.client.gui.battles.BattleScreen)
                || battle == null
                || battle.getMode() != BattleMode.LEVEL_UP) {
            return false;
        }

        releaseMovement(minecraft);
        state = "confirmando subida de nivel";
        if (actionCooldown <= 0) {
            screen.mouseClicked(screen.width / 2.0, screen.height / 2.0, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            actionCooldown = 6;
            PixelmonTracker.LOGGER.info("Auto Trainer clicked the Pixelmon level-up screen");
        }
        return true;
    }

    private static PixelmonEntity findTarget(Minecraft minecraft) {
        PixelmonEntity current = null;
        PixelmonEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof PixelmonEntity pixelmon)) {
                continue;
            }
            if (targetId != null && targetId.equals(pixelmon.getUUID())) {
                current = pixelmon;
            }
            if (isSafeTarget(minecraft, pixelmon)) {
                double distance = minecraft.player.distanceToSqr(pixelmon);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = pixelmon;
                }
            }
        }
        if (isSafeTarget(minecraft, current)) {
            return current;
        }

        stuckTicks = 0;
        targetTicks = 0;
        lastDistance = Double.MAX_VALUE;
        clearRoute();
        if (nearest != null) {
            PixelmonTracker.LOGGER.info("Auto Trainer selected target {} ({}) level {} at {} blocks",
                    nearest.getLocalizedName(), nearest.getUUID(), nearest.getPokemon().getPokemonLevel(),
                    Math.round(minecraft.player.distanceTo(nearest)));
        }
        return nearest;
    }

    private static boolean isSafeTarget(Minecraft minecraft, PixelmonEntity target) {
        if (target == null || !target.isAlive() || target.getOwner() != null || target.isUnbattleable()) {
            return false;
        }
        if (minecraft.player.distanceToSqr(target) > SEARCH_RANGE * SEARCH_RANGE) {
            return false;
        }
        Pokemon pokemon = target.getPokemon();
        if (protectRare && (target.isBossPokemon() || target.isLegendary()
                || pokemon.isMega() || pokemon.getPalette().is("shiny"))) {
            return false;
        }
        int highestLevel = ClientStorageManager.party().getHighestLevel();
        return pokemon.getPokemonLevel() <= highestLevel + MAX_LEVEL_ADVANTAGE;
    }

    private static void moveToward(Minecraft minecraft, PixelmonEntity target, double distance) {
        routeRefreshTicks--;
        boolean targetChanged = routeTargetId == null || !routeTargetId.equals(target.getUUID());
        if (targetChanged || route.isEmpty() || routeIndex >= route.size()
                || routeRefreshTicks <= 0 || stuckTicks > 12
                || (routeIndex < route.size() && !isNavigable(minecraft, route.get(routeIndex)))) {
            route = findRoute(minecraft, target);
            routeIndex = route.size() > 1 ? 1 : 0;
            routeRefreshTicks = 30;
            routeTargetId = target.getUUID();
            if (stuckTicks > 12) {
                PixelmonTracker.LOGGER.info("Auto Trainer detected blocked route; recalculating detour to {}", target.getLocalizedName());
            }
            PixelmonTracker.LOGGER.info("Auto Trainer route to {}: {} nodes", target.getLocalizedName(), route.size());
            stuckTicks = 0;
        }

        Vec3 destination = target.position().add(0.0, target.getBbHeight() * 0.45, 0.0);
        while (routeIndex < route.size()) {
            BlockPos node = route.get(routeIndex);
            Vec3 candidate = new Vec3(node.getX() + 0.5, node.getY() + 0.15, node.getZ() + 0.5);
            double horizontal = Math.hypot(candidate.x - minecraft.player.getX(), candidate.z - minecraft.player.getZ());
            if (horizontal > 0.65 || Math.abs(candidate.y - minecraft.player.getY()) > 1.15) {
                destination = candidate;
                break;
            }
            routeIndex++;
        }
        lookAtNavigation(minecraft, destination);
        minecraft.options.keyUp.setDown(true);
        minecraft.options.keySprint.setDown(distance > 10.0);

        if (distance + 0.15 < lastDistance) {
            stuckTicks = 0;
            lastDistance = distance;
        } else {
            stuckTicks++;
        }

        boolean routeGoesUp = destination.y > minecraft.player.getY() + 0.3;
        boolean needsJump = minecraft.player.horizontalCollision
                || routeGoesUp
                || (minecraft.player.isInWater() && target.getY() > minecraft.player.getY() + 0.5)
                || stuckTicks > 25;
        minecraft.options.keyJump.setDown(needsJump);

        boolean detour = stuckTicks > 45;
        minecraft.options.keyLeft.setDown(detour && (targetTicks / 30) % 2 == 0);
        minecraft.options.keyRight.setDown(detour && (targetTicks / 30) % 2 != 0);
        if (stuckTicks > 160) {
            targetId = null;
            stuckTicks = 0;
            targetTicks = 0;
            lastDistance = Double.MAX_VALUE;
            clearRoute();
        }
    }

    private static List<BlockPos> findRoute(Minecraft minecraft, PixelmonEntity target) {
        BlockPos start = BlockPos.containing(minecraft.player.position());
        BlockPos goal = BlockPos.containing(target.position());
        PriorityQueue<RouteNode> open = new PriorityQueue<>(Comparator.comparingDouble(RouteNode::score));
        Map<BlockPos, RouteNode> best = new HashMap<>();
        RouteNode first = new RouteNode(start, 0.0, routeHeuristic(start, goal), null);
        open.add(first);
        best.put(start, first);
        RouteNode closest = first;
        int visited = 0;

        while (!open.isEmpty() && visited++ < 6000) {
            RouteNode current = open.poll();
            if (best.get(current.position()) != current) {
                continue;
            }
            if (routeHeuristic(current.position(), goal) < routeHeuristic(closest.position(), goal)) {
                closest = current;
            }
            if (isRouteGoal(current.position(), goal)) {
                return buildRoute(current);
            }
            for (BlockPos next : routeNeighbours(minecraft, current.position())) {
                if (Math.abs(next.getX() - start.getX()) > SEARCH_RANGE
                        || Math.abs(next.getZ() - start.getZ()) > SEARCH_RANGE
                        || Math.abs(next.getY() - start.getY()) > 24) {
                    continue;
                }
                double stepCost = next.getY() == current.position().getY() ? 1.0 : 1.35;
                if (minecraft.level.getFluidState(next).is(FluidTags.WATER)) {
                    stepCost += 0.15;
                }
                double cost = current.cost() + stepCost;
                RouteNode known = best.get(next);
                if (known == null || cost < known.cost()) {
                    RouteNode candidate = new RouteNode(next, cost, cost + routeHeuristic(next, goal), current);
                    best.put(next, candidate);
                    open.add(candidate);
                }
            }
        }
        List<BlockPos> partial = buildRoute(closest);
        PixelmonTracker.LOGGER.warn("Auto Trainer could not complete route to {}; using best partial route of {} nodes",
                target.getLocalizedName(), partial.size());
        return partial;
    }

    private static List<BlockPos> routeNeighbours(Minecraft minecraft, BlockPos position) {
        List<BlockPos> neighbours = new ArrayList<>(8);
        boolean swimming = minecraft.level.getFluidState(position).is(FluidTags.WATER);
        int[][] horizontal = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] direction : horizontal) {
            BlockPos same = position.offset(direction[0], 0, direction[1]);
            if (isNavigable(minecraft, same)) {
                neighbours.add(same);
                continue;
            }
            BlockPos up = same.above();
            if (isNavigable(minecraft, up)) {
                neighbours.add(up);
                continue;
            }
            for (int drop = 1; drop <= 3; drop++) {
                BlockPos down = same.below(drop);
                if (isNavigable(minecraft, down)) {
                    neighbours.add(down);
                    break;
                }
            }
        }
        if (swimming) {
            if (isNavigable(minecraft, position.above())) neighbours.add(position.above());
            if (isNavigable(minecraft, position.below())) neighbours.add(position.below());
        }
        return neighbours;
    }

    private static boolean isNavigable(Minecraft minecraft, BlockPos position) {
        if (position.getY() < minecraft.level.getMinBuildHeight()
                || position.getY() + 1 >= minecraft.level.getMaxBuildHeight()
                || !minecraft.level.hasChunkAt(position)) {
            return false;
        }
        boolean feetClear = minecraft.level.getBlockState(position).getCollisionShape(minecraft.level, position).isEmpty();
        boolean headClear = minecraft.level.getBlockState(position.above()).getCollisionShape(minecraft.level, position.above()).isEmpty();
        if (!feetClear || !headClear) {
            return false;
        }
        if (!minecraft.level.getFluidState(position).isEmpty()
                && !minecraft.level.getFluidState(position).is(FluidTags.WATER)) {
            return false;
        }
        boolean inWater = minecraft.level.getFluidState(position).is(FluidTags.WATER)
                || minecraft.level.getFluidState(position.above()).is(FluidTags.WATER);
        boolean supported = !minecraft.level.getBlockState(position.below())
                .getCollisionShape(minecraft.level, position.below()).isEmpty();
        return inWater || supported;
    }

    private static boolean isRouteGoal(BlockPos position, BlockPos goal) {
        int dx = position.getX() - goal.getX();
        int dz = position.getZ() - goal.getZ();
        return dx == 0 && dz == 0 && Math.abs(position.getY() - goal.getY()) <= 1;
    }

    private static double routeHeuristic(BlockPos position, BlockPos goal) {
        return Math.abs(position.getX() - goal.getX())
                + Math.abs(position.getZ() - goal.getZ())
                + Math.abs(position.getY() - goal.getY()) * 1.25;
    }

    private static List<BlockPos> buildRoute(RouteNode end) {
        List<BlockPos> result = new ArrayList<>();
        for (RouteNode node = end; node != null; node = node.parent()) {
            result.add(node.position());
        }
        java.util.Collections.reverse(result);
        return result;
    }

    private static void clearRoute() {
        route = List.of();
        routeIndex = 0;
        routeRefreshTicks = 0;
        routeTargetId = null;
    }

    private record RouteNode(BlockPos position, double cost, double score, RouteNode parent) {
    }

    private static void lookAt(Minecraft minecraft, Vec3 position) {
        Vec3 eyes = minecraft.player.getEyePosition();
        double dx = position.x - eyes.x;
        double dy = position.y - eyes.y;
        double dz = position.z - eyes.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        minecraft.player.setYRot(Mth.wrapDegrees(yaw));
        minecraft.player.setXRot(Mth.clamp(pitch, -89.0f, 89.0f));
        minecraft.player.yHeadRot = minecraft.player.getYRot();
    }

    private static void lookAtNavigation(Minecraft minecraft, Vec3 waypoint) {
        double aimY = minecraft.player.getEyeY();
        if (minecraft.player.isInWater()) {
            double verticalRoute = waypoint.y - minecraft.player.getY();
            aimY += Mth.clamp(verticalRoute, -1.0, 1.0);
        }
        lookAt(minecraft, new Vec3(waypoint.x, aimY, waypoint.z));
    }

    private static void releaseMovement(Minecraft minecraft) {
        if (minecraft == null || minecraft.options == null) {
            return;
        }
        minecraft.options.keyUp.setDown(false);
        minecraft.options.keySprint.setDown(false);
        minecraft.options.keyJump.setDown(false);
        minecraft.options.keyLeft.setDown(false);
        minecraft.options.keyRight.setDown(false);
    }

    private static void handleBattle(ClientBattleManager battle) {
        if (activeBattleController != battle.battleControllerIndex) {
            activeBattleController = battle.battleControllerIndex;
            trainingSwitchDone = false;
            lastBattleTurn = Integer.MIN_VALUE;
            actionCooldown = 0;
            PixelmonTracker.LOGGER.info("Auto Trainer entered battle {} (trainee={})",
                    activeBattleController, trainingPokemonId);
        }
        BattleMode mode = battle.getMode();
        state = "batalhando" + (targetName.isBlank() ? "" : " com " + targetName);
        if (actionCooldown > 0) {
            return;
        }

        if (mode == BattleMode.ENFORCED_SWITCH) {
            selectReplacement(battle);
            return;
        }
        if (mode != BattleMode.CHOOSE_ATTACK && mode != BattleMode.MAIN_MENU) {
            if (mode == BattleMode.REPLACE_ATTACK || mode == BattleMode.YES_NO_REPLACE_MOVE
                    || mode == BattleMode.LEVEL_UP) {
                state = "aguardando escolha de novo golpe";
            }
            return;
        }
        if (battle.battleTurn == lastBattleTurn || battle.getCurrentPokemon() == null) {
            return;
        }

        PixelmonClientData ours = battle.getCurrentPokemon();
        PixelmonClientData enemy = firstLiving(battle.displayedEnemyPokemon);
        if (battle.canSwitch && shouldSwitchFromTrainee(battle, ours, enemy)) {
            return;
        }
        if (battle.canSwitch && shouldSwitchStrategically(battle, ours, enemy)) {
            return;
        }
        int move = chooseBestMove(ours, enemy);
        if (move < 0) {
            return;
        }

        boolean mega = battle.canMegaEvolve(ours);
        boolean dynamax = !mega && battle.canDynamax(ours);
        boolean[][] targets = battle.targetted == null ? new boolean[0][0] : battle.targetted;
        if (!BattlePacketCompat.attack(
                ours.pokemonUUID,
                targets,
                move,
                battle.battleControllerIndex,
                mega,
                dynamax
        )) {
            state = "erro de compatibilidade da batalha";
            setEnabled(false);
            return;
        }
        battle.setMode(BattleMode.WAITING);
        lastBattleTurn = battle.battleTurn;
        actionCooldown = 20;
        Attack selected = ours.moveset.get(move);
        state = "usando " + selected.getMove().getAttackName();
        PixelmonTracker.LOGGER.info(
                "Auto Trainer turn {}: {} uses {} against {} (score={})",
                battle.battleTurn,
                ours.getDisplayName().getString(),
                selected.getMove().getAttackName(),
                enemy == null ? "unknown" : enemy.getDisplayName().getString(),
                Math.round(scoreMove(selected, ours, enemy) * 100.0) / 100.0
        );
    }

    private static int chooseBestMove(PixelmonClientData ours, PixelmonClientData enemy) {
        Moveset moves = ours.moveset;
        if (moves == null || moves.isEmpty()) {
            return -1;
        }
        int bestIndex = -1;
        double bestScore = -1.0;
        for (int index = 0; index < moves.size(); index++) {
            Attack attack = moves.get(index);
            if (attack == null || attack.getPP() <= 0 || attack.getDisabled() || !attack.canUseMove()) {
                continue;
            }
            double score = scoreMove(attack, ours, enemy);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static double scoreMove(Attack attack, PixelmonClientData ours, PixelmonClientData enemy) {
        if (attack.getAttackCategory() == AttackCategory.STATUS || attack.getMove().getBasePower() <= 0) {
            return 8.0 + Math.max(0, attack.getMove().getAccuracy()) / 100.0;
        }
        double effectiveness = enemy == null ? 1.0 : typeEffectiveness(attack, enemy);
        double accuracy = attack.getMove().getAccuracy() <= 0 ? 1.0
                : attack.getMove().getAccuracy() / 100.0;
        boolean stab = ours.getBaseStats().getTypes().contains(attack.getType());
        return attack.getMove().getBasePower() * effectiveness * accuracy * (stab ? 1.5 : 1.0);
    }

    private static double typeEffectiveness(Attack attack, PixelmonClientData enemy) {
        try {
            Object typeReference = attack.getType();
            Object type = typeReference;
            try {
                type = typeReference.getClass().getMethod("value").invoke(typeReference);
            } catch (NoSuchMethodException ignored) {
                // Pixelmon 9.3.0 exposes the enum directly instead of a Holder.
            }
            List<?> enemyTypes = enemy.getBaseStats().getTypes();
            try {
                Object result = type.getClass().getMethod("getTotalEffectiveness", List.class)
                        .invoke(type, enemyTypes);
                return ((Number) result).doubleValue();
            } catch (NoSuchMethodException newerMethodMissing) {
                for (var method : type.getClass().getMethods()) {
                    if (method.getName().equals("getTotalEffectiveness") && method.getParameterCount() == 2) {
                        Object result = method.invoke(null, enemyTypes, type);
                        return ((Number) result).doubleValue();
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            PixelmonTracker.LOGGER.debug("Could not calculate cross-version type effectiveness", error);
        }
        return 1.0;
    }

    private static void selectReplacement(ClientBattleManager battle) {
        PixelmonClientData current = battle.getCurrentPokemon();
        PixelmonClientData enemy = firstLiving(battle.displayedEnemyPokemon);
        PixelmonClientData replacement = bestReplacement(battle, enemy);
        if (replacement == null) {
            state = "sem Pokemon disponivel para troca";
            return;
        }
        if (!BattlePacketCompat.switchPokemon(
                replacement.pokemonUUID,
                battle.battleControllerIndex,
                currentPokemonId(battle, current),
                true
        )) return;
        battle.setMode(BattleMode.WAITING);
        actionCooldown = 20;
        lastBattleTurn = Integer.MIN_VALUE;
        state = "trocando para " + replacement.getDisplayName().getString();
        PixelmonTracker.LOGGER.info("Auto Trainer forced switch: {} -> {}",
                current == null ? "fainted" : current.getDisplayName().getString(),
                replacement.getDisplayName().getString());
    }

    private static boolean shouldSwitchFromTrainee(ClientBattleManager battle, PixelmonClientData current,
                                                    PixelmonClientData enemy) {
        if (trainingSwitchDone || trainingPokemonId == null || current == null
                || !trainingPokemonId.equals(current.pokemonUUID)) {
            return false;
        }
        PixelmonClientData replacement = bestReplacement(battle, enemy, true);
        if (replacement == null) {
            trainingSwitchDone = true;
            state = "sem reforco apto; treinando luta";
            PixelmonTracker.LOGGER.warn("Auto Trainer trainee {} has no healthy helper available",
                    current.getDisplayName().getString());
            return false;
        }
        if (!BattlePacketCompat.switchPokemon(
                replacement.pokemonUUID,
                battle.battleControllerIndex,
                current.pokemonUUID,
                false
        )) return false;
        battle.setMode(BattleMode.WAITING);
        trainingSwitchDone = true;
        lastBattleTurn = Integer.MIN_VALUE;
        lastStrategicSwitchTurn = battle.battleTurn;
        actionCooldown = 20;
        state = "treino: trocando para " + replacement.getDisplayName().getString();
        PixelmonTracker.LOGGER.info(
                "Auto Trainer switch training turn {}: trainee {} Lv.{} -> helper {} Lv.{}",
                battle.battleTurn,
                current.getDisplayName().getString(), current.level,
                replacement.getDisplayName().getString(), replacement.level
        );
        return true;
    }

    private static boolean shouldSwitchStrategically(ClientBattleManager battle, PixelmonClientData current,
                                                      PixelmonClientData enemy) {
        if (current == null || enemy == null || battle.battleTurn - lastStrategicSwitchTurn < 2) {
            return false;
        }
        PixelmonClientData replacement = bestReplacement(battle, enemy);
        if (replacement == null) {
            return false;
        }

        double currentHealth = current.health.get() / Math.max(1.0, current.maxHealth);
        double replacementHealth = replacement.health.get() / Math.max(1.0, replacement.maxHealth);
        double currentScore = bestMoveScore(current, enemy);
        double replacementScore = bestMoveScore(replacement, enemy);
        boolean lowHealth = currentHealth <= 0.22 && replacementHealth >= 0.45;
        boolean muchBetterMatchup = replacementScore >= Math.max(20.0, currentScore * 1.65)
                && replacementHealth >= 0.35;
        if (!lowHealth && !muchBetterMatchup) {
            return false;
        }

        if (!BattlePacketCompat.switchPokemon(
                replacement.pokemonUUID,
                battle.battleControllerIndex,
                current.pokemonUUID,
                false
        )) return false;
        battle.setMode(BattleMode.WAITING);
        // Pixelmon can keep the same battleTurn while the switch animation is
        // completing. Do not mark it as consumed or the next attack menu is
        // ignored and the battle appears frozen on screen.
        lastBattleTurn = Integer.MIN_VALUE;
        lastStrategicSwitchTurn = battle.battleTurn;
        actionCooldown = 20;
        state = "troca estrategica para " + replacement.getDisplayName().getString();
        PixelmonTracker.LOGGER.info(
                "Auto Trainer strategic switch turn {}: {} -> {} (hp={}%, matchup {} -> {}, reason={})",
                battle.battleTurn,
                current.getDisplayName().getString(),
                replacement.getDisplayName().getString(),
                Math.round(currentHealth * 100.0),
                Math.round(currentScore * 100.0) / 100.0,
                Math.round(replacementScore * 100.0) / 100.0,
                lowHealth ? "low health" : "type advantage"
        );
        return true;
    }

    private static PixelmonClientData bestReplacement(ClientBattleManager battle, PixelmonClientData enemy) {
        PixelmonClientData preferred = bestReplacement(battle, enemy, true);
        return preferred != null ? preferred : bestReplacement(battle, enemy, false);
    }

    private static PixelmonClientData bestReplacement(ClientBattleManager battle, PixelmonClientData enemy,
                                                       boolean excludeTrainee) {
        if (battle.fullOurPokemon == null) {
            return null;
        }
        return battle.fullOurPokemon.stream()
                .filter(data -> data != null && data.health.get() > 0.0)
                .filter(data -> !excludeTrainee || trainingPokemonId == null
                        || !trainingPokemonId.equals(data.pokemonUUID))
                .filter(data -> battle.teamPokemon == null || Arrays.stream(battle.teamPokemon)
                        .noneMatch(uuid -> uuid != null && uuid.equals(data.pokemonUUID)))
                .max(Comparator.comparingDouble(data -> replacementScore(data, enemy)))
                .orElse(null);
    }

    private static UUID currentPokemonId(ClientBattleManager battle, PixelmonClientData current) {
        if (current != null) {
            return current.pokemonUUID;
        }
        if (battle.teamPokemon != null && battle.currentPokemon >= 0
                && battle.currentPokemon < battle.teamPokemon.length) {
            return battle.teamPokemon[battle.currentPokemon];
        }
        return null;
    }

    private static double replacementScore(PixelmonClientData candidate, PixelmonClientData enemy) {
        double health = candidate.health.get() / Math.max(1.0, candidate.maxHealth);
        return (bestMoveScore(candidate, enemy) + candidate.level * 2.0) * (0.55 + health * 0.45);
    }

    private static double bestMoveScore(PixelmonClientData pokemon, PixelmonClientData enemy) {
        if (pokemon == null || pokemon.moveset == null) {
            return 0.0;
        }
        double best = 0.0;
        for (Attack attack : pokemon.moveset) {
            if (attack != null && attack.getPP() > 0 && !attack.getDisabled() && attack.canUseMove()) {
                best = Math.max(best, scoreMove(attack, pokemon, enemy));
            }
        }
        return best;
    }

    private static PixelmonClientData firstLiving(PixelmonClientData[] pokemon) {
        if (pokemon == null) {
            return null;
        }
        return Arrays.stream(pokemon)
                .filter(data -> data != null && data.health.get() > 0.0)
                .findFirst()
                .orElse(null);
    }

    private static void message(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.literal("[Auto Trainer] ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(text).withStyle(ChatFormatting.WHITE)),
                    true
            );
        }
    }
}
