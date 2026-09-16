package br.com.pixelmontracker.client;

import br.com.pixelmontracker.PixelmonTracker;
import com.pixelmonmod.pixelmon.api.util.helpers.NetworkHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.lang.reflect.Constructor;
import java.util.UUID;

/** Bridges the 9.3 packet API and the action-based 9.4 packet API. */
final class BattlePacketCompat {
    private BattlePacketCompat() {}

    static boolean attack(UUID pokemon, boolean[][] targets, int move, int battleIndex,
                          boolean mega, boolean dynamax) {
        try {
            Object packet;
            try {
                Class<?> type = Class.forName(
                        "com.pixelmonmod.pixelmon.comm.packetHandlers.battles.ChooseAttackPacket");
                packet = type.getConstructor(UUID.class, boolean[][].class, int.class, int.class,
                                boolean.class, boolean.class)
                        .newInstance(pokemon, targets, move, battleIndex, mega, dynamax);
            } catch (ClassNotFoundException oldApiMissing) {
                Class<?> actionType = Class.forName(
                        "com.pixelmonmod.pixelmon.api.battles.action.type.AttackBattleAction");
                Object action = actionType.getConstructor(UUID.class, boolean[][].class, int.class,
                                boolean.class, boolean.class, boolean.class)
                        .newInstance(pokemon, targets, move, mega, dynamax, false);
                packet = wrapAction(battleIndex, action);
            }
            NetworkHelper.sendToServer((CustomPacketPayload) packet);
            return true;
        } catch (ReflectiveOperationException | ClassCastException error) {
            PixelmonTracker.LOGGER.error("Could not create a Pixelmon battle attack packet", error);
            return false;
        }
    }

    static boolean switchPokemon(UUID replacement, int battleIndex, UUID current, boolean instant) {
        try {
            Object packet;
            try {
                Class<?> type = Class.forName(
                        "com.pixelmonmod.pixelmon.comm.packetHandlers.battles.SwitchPokemonPacket");
                packet = type.getConstructor(UUID.class, int.class, UUID.class, boolean.class)
                        .newInstance(replacement, battleIndex, current, instant);
            } catch (ClassNotFoundException oldApiMissing) {
                Class<?> actionType = Class.forName(
                        "com.pixelmonmod.pixelmon.api.battles.action.type.SwitchBattleAction");
                Object action = actionType.getConstructor(UUID.class, UUID.class, boolean.class)
                        .newInstance(replacement, current, instant);
                packet = wrapAction(battleIndex, action);
            }
            NetworkHelper.sendToServer((CustomPacketPayload) packet);
            return true;
        } catch (ReflectiveOperationException | ClassCastException error) {
            PixelmonTracker.LOGGER.error("Could not create a Pixelmon battle switch packet", error);
            return false;
        }
    }

    private static Object wrapAction(int battleIndex, Object action) throws ReflectiveOperationException {
        Class<?> actionInterface = Class.forName("com.pixelmonmod.pixelmon.api.battles.action.BattleAction");
        Class<?> packetType = Class.forName(
                "com.pixelmonmod.pixelmon.comm.packetHandlers.battles.BattleActionPacket");
        Constructor<?> constructor = packetType.getConstructor(int.class, actionInterface);
        return constructor.newInstance(battleIndex, action);
    }
}
