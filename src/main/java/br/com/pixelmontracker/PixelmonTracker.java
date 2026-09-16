package br.com.pixelmontracker;

import br.com.pixelmontracker.client.TrackerClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(PixelmonTracker.MOD_ID)
public final class PixelmonTracker {
    public static final String MOD_ID = "pixelmontracker";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public PixelmonTracker(IEventBus modBus) {
        modBus.addListener(TrackerClient::registerKeys);
        modBus.addListener(TrackerClient::registerGuiLayers);
        NeoForge.EVENT_BUS.register(TrackerClient.class);
        LOGGER.info("Pixelmon Tracker client events registered");
    }
}
