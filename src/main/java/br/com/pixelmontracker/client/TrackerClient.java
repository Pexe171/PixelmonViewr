package br.com.pixelmontracker.client;

import br.com.pixelmontracker.PixelmonTracker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.blaze3d.platform.InputConstants;
import com.pixelmonmod.pixelmon.blocks.tileentity.PokeChestTileEntity;
import com.pixelmonmod.pixelmon.blocks.enums.EnumPokeChestType;
import com.pixelmonmod.pixelmon.entities.pixelmon.PixelmonEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

public final class TrackerClient {
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int MAX_HUD_TARGETS = 7;
    private static final int MAX_WORLD_MARKERS = 40;
    private static final int MAX_CHUNK_RADIUS = 16;
    private static final int RADAR_RADIUS = 42;
    private static final double RADAR_RANGE = 128.0;
    private static final int HUD_ROW_HEIGHT = 20;
    private static final int HUD_ICON_SIZE = 12;
    private static final int WORLD_ICON_SIZE = 12;
    private static final int PINNED_COLOR = 0xFFFF3BFF;
    private static final ResourceLocation FALLBACK_SPRITE = ResourceLocation.fromNamespaceAndPath(
            "pixelmon", "textures/pokemon/000_missingno/all/base/none/sprite.png");
    private static final DustParticleOptions POKEMON_TRAIL_PARTICLE = new DustParticleOptions(new Vector3f(0.2f, 0.95f, 1.0f), 1.25f);
    private static final DustParticleOptions LOOT_TRAIL_PARTICLE = new DustParticleOptions(new Vector3f(1.0f, 0.55f, 0.05f), 1.25f);
    private static final DustParticleOptions PINNED_TRAIL_PARTICLE = new DustParticleOptions(new Vector3f(1.0f, 0.12f, 1.0f), 1.5f);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final KeyMapping TOGGLE = new KeyMapping(
            "key.pixelmontracker.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.pixelmontracker"
    );
    private static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.pixelmontracker.menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F4,
            "key.categories.pixelmontracker"
    );
    private static final KeyMapping TOGGLE_POKEMON = new KeyMapping(
            "key.pixelmontracker.pokemon",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            "key.categories.pixelmontracker"
    );
    private static final KeyMapping TOGGLE_LOOT = new KeyMapping(
            "key.pixelmontracker.loot",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F10,
            "key.categories.pixelmontracker"
    );
    private static final KeyMapping TOGGLE_MARKERS = new KeyMapping(
            "key.pixelmontracker.markers",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F7,
            "key.categories.pixelmontracker"
    );
    private static final KeyMapping TOGGLE_RADAR = new KeyMapping(
            "key.pixelmontracker.radar",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F6,
            "key.categories.pixelmontracker"
    );
    private static final KeyMapping TOGGLE_TRAILS = new KeyMapping(
            "key.pixelmontracker.trails",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.pixelmontracker"
    );

    private static final List<TrackedTarget> TARGETS = new ArrayList<>();
    private static final Set<String> POKEMON_FILTERS = new HashSet<>();
    private static final Set<UUID> ALERTED_MATCHES = new HashSet<>();
    private static final Set<UUID> ALERTED_SHINIES = new HashSet<>();
    private static final Set<String> CLAIMED_LOOT = new HashSet<>();
    private static PokemonKindFilter pokemonKindFilter = PokemonKindFilter.ALL;
    private static BossTierFilter bossTierFilter = BossTierFilter.ALL;
    private static LootTypeFilter lootTypeFilter = LootTypeFilter.ALL;
    private static boolean shinyOnly;
    private static int minimumLevel = 1;
    private static int maximumLevel = Integer.MAX_VALUE;
    private static TrackedTarget pinnedTarget;
    private static boolean enabled = true;
    private static boolean showPokemon = true;
    private static boolean showLoot = true;
    private static boolean showWorldMarkers = true;
    private static boolean showRadar = true;
    private static boolean showTrails = true;
    private static int ticksUntilScan;
    private static boolean loggedFirstTick;
    private static boolean loggedFirstHudRender;
    private static int lastLoggedTargetCount = -1;
    private static boolean historyLoaded;
    private static boolean wasInWorld;

    private TrackerClient() {
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        AutoTrainer.registerKey(event);
        event.register(OPEN_MENU);
        event.register(TOGGLE);
        event.register(TOGGLE_POKEMON);
        event.register(TOGGLE_LOOT);
        event.register(TOGGLE_MARKERS);
        event.register(TOGGLE_RADAR);
        event.register(TOGGLE_TRAILS);
    }

    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(PixelmonTracker.MOD_ID, "tracker_hud"),
                TrackerClient::renderHudLayer
        );
        PixelmonTracker.LOGGER.info("Pixelmon Tracker HUD registered above all GUI layers");
    }

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("ptracker")
                        .then(Commands.literal("filtro")
                                .then(Commands.argument("nomes", StringArgumentType.greedyString())
                                        .executes(context -> setFilters(StringArgumentType.getString(context, "nomes")))))
                        .then(Commands.literal("somente-loot").executes(context -> {
                            onlyLoot();
                            return 1;
                        }))
                        .then(Commands.literal("limpar-pegos").executes(context -> {
                            clearLootHistory();
                            return 1;
                        }))
                        .then(Commands.literal("resetar-categorias").executes(context -> {
                            resetCategoryFilters();
                            return 1;
                        }))
                        .then(Commands.literal("limpar").executes(context -> clearFilters()))
                        .then(Commands.literal("lista").executes(context -> showFilters()))
        );
    }

    static int setFilters(String input) {
        POKEMON_FILTERS.clear();
        for (String value : input.split(",")) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                POKEMON_FILTERS.add(normalized);
            }
        }
        ALERTED_MATCHES.clear();
        ticksUntilScan = 0;
        status(Minecraft.getInstance(), POKEMON_FILTERS.isEmpty()
                ? "Filtro vazio: mostrando todos os Pokemon"
                : "Procurando: " + String.join(", ", POKEMON_FILTERS));
        return POKEMON_FILTERS.size();
    }

    static int clearFilters() {
        POKEMON_FILTERS.clear();
        ALERTED_MATCHES.clear();
        ticksUntilScan = 0;
        status(Minecraft.getInstance(), "Filtro removido: mostrando todos os Pokemon");
        return 1;
    }

    static void onlyLoot() {
        showPokemon = false;
        showLoot = true;
        POKEMON_FILTERS.clear();
        ALERTED_MATCHES.clear();
        ticksUntilScan = 0;
        status(Minecraft.getInstance(), "Modo somente PokeLoot ativado");
    }

    static String pokemonKindLabel() { return pokemonKindFilter.label; }
    static String bossTierLabel() { return bossTierFilter.label; }
    static String lootTypeLabel() { return lootTypeFilter.label; }
    static boolean isShinyOnly() { return shinyOnly; }
    static String minimumLevelText() { return minimumLevel == 1 ? "" : Integer.toString(minimumLevel); }
    static String maximumLevelText() { return maximumLevel == Integer.MAX_VALUE ? "" : Integer.toString(maximumLevel); }
    static List<TrackedTarget> targetSnapshot() { return List.copyOf(TARGETS); }
    static String pinnedTargetId() { return pinnedTarget == null ? "" : pinnedTarget.id(); }

    static void cyclePokemonKindFilter() {
        PokemonKindFilter[] values = PokemonKindFilter.values();
        pokemonKindFilter = values[(pokemonKindFilter.ordinal() + 1) % values.length];
        showPokemon = true;
        filterChanged("Categoria Pokemon: " + pokemonKindFilter.label);
    }

    static void cycleBossTierFilter() {
        BossTierFilter[] values = BossTierFilter.values();
        bossTierFilter = values[(bossTierFilter.ordinal() + 1) % values.length];
        showPokemon = true;
        filterChanged("Tier de boss: " + bossTierFilter.label);
    }

    static void cycleLootTypeFilter() {
        LootTypeFilter[] values = LootTypeFilter.values();
        lootTypeFilter = values[(lootTypeFilter.ordinal() + 1) % values.length];
        showLoot = true;
        filterChanged("Tipo de PokeLoot: " + lootTypeFilter.label);
    }

    static void resetCategoryFilters() {
        pokemonKindFilter = PokemonKindFilter.ALL;
        bossTierFilter = BossTierFilter.ALL;
        lootTypeFilter = LootTypeFilter.ALL;
        shinyOnly = false;
        minimumLevel = 1;
        maximumLevel = Integer.MAX_VALUE;
        filterChanged("Filtros de categoria resetados");
    }

    static void toggleShinyOnly() {
        shinyOnly = !shinyOnly;
        showPokemon = true;
        filterChanged("Somente Shiny: " + (shinyOnly ? "ativado" : "desativado"));
    }

    static boolean setLevelFilter(String minimum, String maximum) {
        try {
            int parsedMinimum = minimum.isBlank() ? 1 : Integer.parseInt(minimum.trim());
            int parsedMaximum = maximum.isBlank() ? Integer.MAX_VALUE : Integer.parseInt(maximum.trim());
            if (parsedMinimum < 1 || parsedMinimum > 100
                    || (!maximum.isBlank() && (parsedMaximum < 1 || parsedMaximum > 100))
                    || parsedMinimum > parsedMaximum) {
                status(Minecraft.getInstance(), "Nivel invalido: use 1 a 100 e minimo <= maximo");
                return false;
            }
            minimumLevel = parsedMinimum;
            maximumLevel = parsedMaximum;
            showPokemon = true;
            filterChanged("Nivel: " + minimumLevel + " a "
                    + (maximumLevel == Integer.MAX_VALUE ? "sem limite" : maximumLevel));
            return true;
        } catch (NumberFormatException error) {
            status(Minecraft.getInstance(), "Nivel invalido: digite somente numeros");
            return false;
        }
    }

    static void togglePinnedTarget(String id) {
        if (pinnedTarget != null && pinnedTarget.id().equals(id)) {
            clearPinnedTarget();
            return;
        }
        TARGETS.stream().filter(target -> target.id().equals(id)).findFirst().ifPresent(target -> {
            pinnedTarget = target.withStale(false);
            status(Minecraft.getInstance(), "Alvo fixado: " + target.label());
        });
    }

    static void clearPinnedTarget() {
        if (pinnedTarget != null) {
            pinnedTarget = null;
            ticksUntilScan = 0;
            status(Minecraft.getInstance(), "Alvo desafixado");
        }
    }

    private static void filterChanged(String message) {
        ALERTED_MATCHES.clear();
        ticksUntilScan = 0;
        status(Minecraft.getInstance(), message);
    }

    static int claimedLootCount() {
        return CLAIMED_LOOT.size();
    }

    static void clearLootHistory() {
        CLAIMED_LOOT.clear();
        saveLootHistory();
        ticksUntilScan = 0;
        status(Minecraft.getInstance(), "Historico de PokeLoot limpo");
    }

    private static int showFilters() {
        status(Minecraft.getInstance(), POKEMON_FILTERS.isEmpty()
                ? "Nenhum filtro ativo"
                : "Filtro ativo: " + String.join(", ", POKEMON_FILTERS));
        return POKEMON_FILTERS.size();
    }

    static boolean isEnabled() { return enabled; }
    static boolean isShowingPokemon() { return showPokemon; }
    static boolean isShowingLoot() { return showLoot; }
    static boolean isShowingMarkers() { return showWorldMarkers; }
    static boolean isShowingRadar() { return showRadar; }
    static boolean isShowingTrails() { return showTrails; }
    static boolean isAutoTrainerEnabled() { return AutoTrainer.isEnabled(); }
    static boolean isAutoTrainerProtectingRare() { return AutoTrainer.isProtectingRare(); }
    static List<com.pixelmonmod.pixelmon.api.pokemon.Pokemon> trainerParty() { return AutoTrainer.partySnapshot(); }
    static UUID trainingPokemonId() { return AutoTrainer.trainingPokemonId(); }
    static String filterText() { return String.join(",", POKEMON_FILTERS); }

    static void toggleEnabled() {
        enabled = !enabled;
        status(Minecraft.getInstance(), enabled ? "Rastreador ligado" : "Rastreador desligado");
    }

    static void togglePokemon() {
        showPokemon = !showPokemon;
        status(Minecraft.getInstance(), "Pokemon: " + (showPokemon ? "visiveis" : "ocultos"));
        ticksUntilScan = 0;
    }

    static void toggleLoot() {
        showLoot = !showLoot;
        status(Minecraft.getInstance(), "PokeLoot: " + (showLoot ? "visivel" : "oculto"));
        ticksUntilScan = 0;
    }

    static void toggleMarkers() {
        showWorldMarkers = !showWorldMarkers;
        status(Minecraft.getInstance(), "Marcadores 3D: " + (showWorldMarkers ? "visiveis" : "ocultos"));
    }

    static void toggleRadar() {
        showRadar = !showRadar;
        status(Minecraft.getInstance(), "Radar: " + (showRadar ? "visivel" : "oculto"));
    }

    static void toggleTrails() {
        showTrails = !showTrails;
        status(Minecraft.getInstance(), "Rastros no chao: " + (showTrails ? "visiveis" : "ocultos"));
    }

    static void toggleAutoTrainer() {
        AutoTrainer.toggle();
    }

    static void toggleAutoTrainerProtectRare() {
        AutoTrainer.toggleProtectRare();
    }

    static void selectTrainingPokemon(UUID pokemonId) {
        AutoTrainer.selectTrainingPokemon(pokemonId);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace("é", "e");
    }

    private static boolean hasPokemonCategoryFilter() {
        return pokemonKindFilter != PokemonKindFilter.ALL || bossTierFilter != BossTierFilter.ALL
                || shinyOnly || minimumLevel != 1 || maximumLevel != Integer.MAX_VALUE;
    }

    private static String bossTierId(PixelmonEntity pixelmon) {
        String id = normalize(pixelmon.getBossTier().getID());
        int namespace = id.lastIndexOf(':');
        return namespace >= 0 ? id.substring(namespace + 1) : id;
    }

    private static boolean isMega(PixelmonEntity pixelmon) {
        return pixelmon.getPokemon().isMega() || pixelmon.getBossTier().isMega();
    }

    private static boolean matchesPokemonCategory(PixelmonEntity pixelmon) {
        boolean boss = pixelmon.isBossPokemon();
        boolean mega = isMega(pixelmon);
        boolean legendary = pixelmon.isLegendary();
        boolean kindMatches = switch (pokemonKindFilter) {
            case ALL -> true;
            case NORMAL -> !boss && !mega && !legendary;
            case BOSS -> boss;
            case MEGA -> mega;
            case LEGENDARY -> legendary;
            case SPECIAL -> boss || mega || legendary;
        };
        int level = pixelmon.getPokemon().getPokemonLevel();
        boolean shiny = pixelmon.getPokemon().getPalette().isShiny();
        return kindMatches
                && (!shinyOnly || shiny)
                && level >= minimumLevel && level <= maximumLevel
                && (bossTierFilter == BossTierFilter.ALL
                || boss && bossTierFilter.id.equals(bossTierId(pixelmon)));
    }

    private static boolean matchesLootType(PokeChestTileEntity chest) {
        if (lootTypeFilter == LootTypeFilter.ALL) {
            return true;
        }
        if (lootTypeFilter == LootTypeFilter.GROTTO) {
            return chest.isGrotto();
        }
        return !chest.isGrotto() && chest.getChestType() == lootTypeFilter.type;
    }

    private static String lootKey(String dimension, BlockPos pos) {
        return worldScope() + "|" + dimension + "|" + pos.asLong();
    }

    private static String worldScope() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getCurrentServer() != null) {
            return "server:" + minecraft.getCurrentServer().ip;
        }
        if (minecraft.getSingleplayerServer() != null) {
            return "singleplayer:" + minecraft.getSingleplayerServer().getWorldData().getLevelName();
        }
        return "unknown";
    }

    private static Path lootHistoryPath() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve("pixelmontracker-claimed-loot.json");
    }

    private static void loadLootHistory() {
        Path path = lootHistoryPath();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String[] entries = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), String[].class);
            if (entries != null) {
                CLAIMED_LOOT.addAll(Arrays.asList(entries));
            }
            PixelmonTracker.LOGGER.info("Loaded {} claimed PokeLoot entries", CLAIMED_LOOT.size());
        } catch (IOException | RuntimeException error) {
            PixelmonTracker.LOGGER.warn("Could not load claimed PokeLoot history", error);
        }
    }

    private static void saveLootHistory() {
        Path path = lootHistoryPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(CLAIMED_LOOT), StandardCharsets.UTF_8);
        } catch (IOException error) {
            PixelmonTracker.LOGGER.warn("Could not save claimed PokeLoot history", error);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            AutoTrainer.tick(minecraft);
            TARGETS.clear();
            if (wasInWorld) {
                pinnedTarget = null;
                ALERTED_SHINIES.clear();
                wasInWorld = false;
            }
            return;
        }
        wasInWorld = true;
        AutoTrainer.tick(minecraft);

        if (!loggedFirstTick) {
            loggedFirstTick = true;
            PixelmonTracker.LOGGER.info("Pixelmon Tracker is active in-world; HUD and target scanner started");
        }
        if (!historyLoaded) {
            loadLootHistory();
            historyLoaded = true;
            ticksUntilScan = 0;
        }

        while (OPEN_MENU.consumeClick()) {
            minecraft.setScreen(new TrackerScreen());
        }

        while (TOGGLE.consumeClick()) {
            toggleEnabled();
        }
        while (TOGGLE_POKEMON.consumeClick()) {
            togglePokemon();
        }
        while (TOGGLE_LOOT.consumeClick()) {
            toggleLoot();
        }
        while (TOGGLE_MARKERS.consumeClick()) {
            toggleMarkers();
        }
        while (TOGGLE_RADAR.consumeClick()) {
            toggleRadar();
        }
        while (TOGGLE_TRAILS.consumeClick()) {
            toggleTrails();
        }

        if (!enabled) {
            TARGETS.clear();
            return;
        }

        if (--ticksUntilScan <= 0) {
            scan(minecraft);
            ticksUntilScan = SCAN_INTERVAL_TICKS;
        }
        if (showTrails && minecraft.level.getGameTime() % 5L == 0L) {
            spawnParticleTrails(minecraft);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()
                || !(event.getLevel().getBlockEntity(event.getPos()) instanceof PokeChestTileEntity)) {
            return;
        }

        String key = lootKey(event.getLevel().dimension().location().toString(), event.getPos());
        if (CLAIMED_LOOT.add(key)) {
            String targetId = "loot:" + key;
            TARGETS.removeIf(target -> target.type() == TrackedTarget.TargetType.POKELOOT
                    && BlockPos.containing(target.position()).equals(event.getPos()));
            if (pinnedTarget != null && pinnedTarget.id().equals(targetId)) {
                pinnedTarget = null;
            }
            saveLootHistory();
            ticksUntilScan = 0;
            status(Minecraft.getInstance(), "PokeLoot registrado e removido do radar");
        }
    }

    private static void status(Minecraft minecraft, String text) {
        minecraft.player.displayClientMessage(
                Component.literal("[Tracker] ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal(text).withStyle(ChatFormatting.WHITE)),
                true
        );
    }

    private static void scan(Minecraft minecraft) {
        TARGETS.clear();
        Vec3 playerPos = minecraft.player.position();
        String dimension = minecraft.level.dimension().location().toString();
        Set<UUID> matchesThisScan = new HashSet<>();

        boolean hasPinnedPokemon = pinnedTarget != null && pinnedTarget.type() == TrackedTarget.TargetType.POKEMON;
        if (showPokemon || hasPinnedPokemon) {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof PixelmonEntity pixelmon && entity.isAlive()) {
                    String targetId = "pokemon:" + pixelmon.getUUID();
                    boolean pinned = pinnedTarget != null && pinnedTarget.id().equals(targetId);
                    String name = pixelmon.getLocalizedName();
                    String speciesName = normalize(pixelmon.getSpecies().getName());
                    boolean shiny = pixelmon.getPokemon().getPalette().isShiny();
                    if (shiny && ALERTED_SHINIES.add(pixelmon.getUUID())) {
                        double foundDistance = pixelmon.position().distanceTo(playerPos);
                        status(minecraft, "SHINY ENCONTRADO: " + name + " a " + Math.round(foundDistance) + "m");
                        minecraft.player.playSound(SoundEvents.TOTEM_USE, 0.85f, 1.15f);
                    }
                    boolean matchesFilter = POKEMON_FILTERS.isEmpty()
                            || POKEMON_FILTERS.stream().anyMatch(speciesName::contains);
                    boolean matchesSelection = matchesFilter && matchesPokemonCategory(pixelmon);
                    if ((!showPokemon || !matchesSelection) && !pinned) {
                        continue;
                    }

                    if (matchesSelection && (!POKEMON_FILTERS.isEmpty() || hasPokemonCategoryFilter())) {
                        matchesThisScan.add(pixelmon.getUUID());
                        if (ALERTED_MATCHES.add(pixelmon.getUUID())) {
                            double foundDistance = pixelmon.position().distanceTo(playerPos);
                            status(minecraft, "ALVO ENCONTRADO: " + name + " a " + Math.round(foundDistance) + "m");
                            minecraft.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.4f);
                        }
                    }
                    int level = pixelmon.getPokemon().getPokemonLevel();
                    boolean boss = pixelmon.isBossPokemon();
                    boolean mega = isMega(pixelmon);
                    boolean legendary = pixelmon.isLegendary();
                    String tier = boss ? bossTierId(pixelmon).toUpperCase(Locale.ROOT) : "";
                    String flags = (boss ? " [BOSS " + tier + "]" : "")
                            + (shiny ? " [SHINY]" : "")
                            + (mega ? " [MEGA]" : "")
                            + (legendary ? " [LENDARIO]" : "");
                    int color = rarityColor(pixelmon, level, shiny, mega, legendary, boss);
                    TARGETS.add(new TrackedTarget(
                            targetId,
                            TrackedTarget.TargetType.POKEMON,
                            name + " Lv." + level + flags,
                            pixelmon.position().add(0.0, pixelmon.getBbHeight() + 0.5, 0.0),
                            color,
                            dimension,
                            level,
                            shiny,
                            mega,
                            legendary,
                            boss,
                            boss ? pixelmon.getBossTier().getExtraLevels() : 0,
                            false,
                            pixelmon.getPokemon().getSprite()
                    ));
                }
            }
        }

        boolean hasPinnedLoot = pinnedTarget != null && pinnedTarget.type() == TrackedTarget.TargetType.POKELOOT;
        if (showLoot || hasPinnedLoot) {
            int centerChunkX = minecraft.player.chunkPosition().x;
            int centerChunkZ = minecraft.player.chunkPosition().z;
            int radius = Math.min(minecraft.options.getEffectiveRenderDistance(), MAX_CHUNK_RADIUS);

            for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
                for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
                    LevelChunk chunk = minecraft.level.getChunkSource().getChunk(chunkX, chunkZ, false);
                    if (chunk == null) {
                        continue;
                    }
                    for (Map.Entry<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                        if (entry.getValue() instanceof PokeChestTileEntity chest) {
                            BlockPos pos = entry.getKey();
                            String key = lootKey(dimension, pos);
                            boolean pinned = pinnedTarget != null && pinnedTarget.id().equals("loot:" + key);
                            if ((!showLoot || !matchesLootType(chest)) && !pinned) {
                                continue;
                            }
                            if (CLAIMED_LOOT.contains(key)) {
                                continue;
                            }
                            if (!chest.canClaim(minecraft.player.getUUID())) {
                                continue;
                            }
                            String type = chest.isGrotto() ? "Gruta" : chest.getChestType().toString();
                            TARGETS.add(new TrackedTarget(
                                    "loot:" + key,
                                    TrackedTarget.TargetType.POKELOOT,
                                    "PokeLoot " + type,
                                    Vec3.atCenterOf(pos).add(0.0, 0.8, 0.0),
                                    0xFFFFAA00,
                                    dimension,
                                    0,
                                    false,
                                    false,
                                    false,
                                    false,
                                    0,
                                    false,
                                    null
                            ));
                        }
                    }
                }
            }
        }

        if (!POKEMON_FILTERS.isEmpty() || hasPokemonCategoryFilter()) {
            ALERTED_MATCHES.retainAll(matchesThisScan);
        }

        if (pinnedTarget != null) {
            TrackedTarget livePinned = TARGETS.stream()
                    .filter(target -> target.id().equals(pinnedTarget.id()))
                    .findFirst()
                    .orElse(null);
            if (livePinned != null) {
                pinnedTarget = livePinned;
            } else {
                pinnedTarget = pinnedTarget.withStale(true);
                TARGETS.add(pinnedTarget);
            }
        }

        TARGETS.sort(targetComparator(playerPos));
        if (TARGETS.size() != lastLoggedTargetCount) {
            lastLoggedTargetCount = TARGETS.size();
            long pokemonCount = TARGETS.stream().filter(target -> target.type() == TrackedTarget.TargetType.POKEMON).count();
            long lootCount = TARGETS.stream().filter(target -> target.type() == TrackedTarget.TargetType.POKELOOT).count();
            PixelmonTracker.LOGGER.info("Tracker scan: {} Pokemon, {} available PokeLoot", pokemonCount, lootCount);
        }
    }

    /** Consistent rarity palette used by the HUD, world labels and radar. */
    private static int rarityColor(PixelmonEntity pixelmon, int level, boolean shiny,
                                   boolean mega, boolean legendary, boolean boss) {
        if (boss) return 0xFF000000 | pixelmon.getBossTier().getColor().getRGB();
        if (shiny) return 0xFF55FF55;       // shiny: green
        if (legendary) return 0xFFFFD84D;   // legendary: gold
        if (mega) return 0xFFFF55FF;        // mega: magenta
        if (level >= 40) return 0xFFB56CFF; // epic/high level
        if (level >= 20) return 0xFF5599FF; // rare
        return 0xFF67E8F9;                  // common
    }

    private static Comparator<TrackedTarget> targetComparator(Vec3 playerPos) {
        return Comparator
                .comparing((TrackedTarget target) -> pinnedTarget == null || !target.id().equals(pinnedTarget.id()))
                .thenComparing(target -> !target.shiny())
                .thenComparing(target -> !target.mega())
                .thenComparing(target -> !target.legendary())
                .thenComparing(target -> !target.boss())
                .thenComparing(Comparator.comparingInt(TrackedTarget::bossPriority).reversed())
                .thenComparing(Comparator.comparingInt(TrackedTarget::level).reversed())
                .thenComparingDouble(target -> target.position().distanceToSqr(playerPos));
    }

    private static void renderHudLayer(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        if (!loggedFirstHudRender) {
            loggedFirstHudRender = true;
            PixelmonTracker.LOGGER.info("Pixelmon Tracker HUD render event received");
        }

        int x = 8;
        int y = 8;
        int shown = Math.min(MAX_HUD_TARGETS, TARGETS.size());
        List<String> lines = new ArrayList<>(shown);
        String header = "PIXELMON TRACKER  |  " + TARGETS.size() + " alvos" + activeFilterSummary();
        int width = minecraft.font.width(header) + 8;
        width = Math.max(width, minecraft.font.width("F4 menu  J auto  K rastros  F6 radar  F7 marcas  F8 liga") + 8);
        boolean showTrainerStatus = AutoTrainer.isEnabled();
        int listY = showTrainerStatus ? 36 : 25;
        int height = listY + shown * HUD_ROW_HEIGHT;

        Vec3 playerPos = minecraft.player.position();
        for (int i = 0; i < shown; i++) {
            String line = formatTargetLine(minecraft, TARGETS.get(i), playerPos);
            lines.add(line);
            width = Math.max(width, minecraft.font.width(line) + 30);
        }

        graphics.fill(x - 4, y - 4, x + width, y + height, 0xB0101620);
        graphics.fill(x - 4, y - 4, x - 1, y + height, 0xFF22D3EE);
        graphics.drawString(minecraft.font, header, x, y, 0xFF67E8F9, true);
        graphics.drawString(minecraft.font, "F4 menu  J auto  K rastros  F6 radar  F7 marcas  F8 liga", x, y + 11, 0xFFA7B0C0, false);
        if (showTrainerStatus) {
            graphics.drawString(minecraft.font, AutoTrainer.hudLine(), x, y + 22, 0xFFFFAA00, true);
        }

        for (int i = 0; i < shown; i++) {
            TrackedTarget target = TARGETS.get(i);
            boolean pinned = pinnedTarget != null && target.id().equals(pinnedTarget.id());
            int rowY = y + listY + i * HUD_ROW_HEIGHT;
            drawTargetIcon(graphics, minecraft, target, x, rowY + 2, HUD_ICON_SIZE);
            graphics.drawString(minecraft.font, lines.get(i), x + HUD_ICON_SIZE + 6, rowY + 3,
                    pinned ? PINNED_COLOR : target.color(), true);
        }

        if (showRadar) {
            drawRadar(graphics, minecraft);
        }
        if (showWorldMarkers) {
            drawProjectedMarkers(graphics, minecraft);
        }
    }

    private static void drawProjectedMarkers(GuiGraphics graphics, Minecraft minecraft) {
        var camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Vector3f forward = camera.getLookVector();
        Vector3f up = camera.getUpVector();
        Vector3f left = camera.getLeftVector();
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        double fov = minecraft.options.fov().get();
        double focalLength = screenHeight / (2.0 * Math.tan(Math.toRadians(fov) / 2.0));
        String currentDimension = minecraft.level.dimension().location().toString();
        List<int[]> occupied = new ArrayList<>();
        int rendered = 0;

        for (TrackedTarget target : TARGETS) {
            if (rendered >= MAX_WORLD_MARKERS || !target.dimension().equals(currentDimension)) {
                continue;
            }
            double distanceSquared = target.position().distanceToSqr(minecraft.player.position());
            if (distanceSquared > 512.0 * 512.0) {
                continue;
            }

            Vec3 relative = target.position().subtract(cameraPos);
            double depth = relative.x * forward.x() + relative.y * forward.y() + relative.z * forward.z();
            if (depth <= 0.2) {
                continue;
            }
            double horizontal = relative.x * left.x() + relative.y * left.y() + relative.z * left.z();
            double vertical = relative.x * up.x() + relative.y * up.y() + relative.z * up.z();
            int centerX = (int) Math.round(screenWidth / 2.0 - horizontal / depth * focalLength);
            int centerY = (int) Math.round(screenHeight / 2.0 - vertical / depth * focalLength);
            boolean pinned = pinnedTarget != null && target.id().equals(pinnedTarget.id());
            String text = (pinned ? "[FIXO] " : "") + target.label()
                    + (target.stale() ? " [ULTIMA POSICAO]" : "")
                    + "  " + Math.round(Math.sqrt(distanceSquared)) + "m";
            int textWidth = minecraft.font.width(text);
            int boxWidth = textWidth + WORLD_ICON_SIZE + 10;
            int boxHeight = Math.max(18, minecraft.font.lineHeight + 3);
            if (centerX < -boxWidth || centerX > screenWidth + boxWidth
                    || centerY < -boxHeight || centerY > screenHeight + boxHeight) {
                continue;
            }

            int x = Mth.clamp(centerX - boxWidth / 2, 3, Math.max(3, screenWidth - boxWidth - 3));
            int y = Mth.clamp(centerY - boxHeight / 2, 3, Math.max(3, screenHeight - boxHeight - 3));
            for (int attempt = 0; attempt < 8 && overlapsAny(x, y, boxWidth, boxHeight, occupied); attempt++) {
                y = Mth.clamp(y + boxHeight + 2, 3, Math.max(3, screenHeight - boxHeight - 3));
            }
            if (overlapsAny(x, y, boxWidth, boxHeight, occupied)) {
                continue;
            }

            graphics.fill(x, y, x + boxWidth, y + boxHeight, 0xD0101620);
            int markerColor = pinned ? PINNED_COLOR : target.color();
            graphics.fill(x, y, x + (pinned ? 5 : 3), y + boxHeight, markerColor);
            drawTargetIcon(graphics, minecraft, target, x + 4, y + 3, WORLD_ICON_SIZE);
            graphics.drawString(minecraft.font, text, x + WORLD_ICON_SIZE + 8, y + 2, markerColor, true);
            occupied.add(new int[]{x, y, boxWidth, boxHeight});
            rendered++;
        }
    }

    private static boolean overlapsAny(int x, int y, int width, int height, List<int[]> occupied) {
        for (int[] box : occupied) {
            if (x < box[0] + box[2] && x + width > box[0]
                    && y < box[1] + box[3] && y + height > box[1]) {
                return true;
            }
        }
        return false;
    }

    static ResourceLocation fallbackSprite() {
        return FALLBACK_SPRITE;
    }

    static void drawTargetIcon(GuiGraphics graphics, Minecraft minecraft, TrackedTarget target,
                               int x, int y, int size) {
        if (target.type() == TrackedTarget.TargetType.POKELOOT) {
            graphics.fill(x, y, x + size, y + size, 0xFFFFAA00);
            graphics.drawString(minecraft.font, "L", x + 5, y + 3, 0xFF3A2200, true);
            return;
        }
        ResourceLocation sprite = target.sprite() == null ? FALLBACK_SPRITE : target.sprite();
        graphics.blit(sprite, x, y, size, size, 0.0f, 0.0f, 32, 32, 32, 32);
    }

    private static String activeFilterSummary() {
        List<String> filters = new ArrayList<>();
        if (!POKEMON_FILTERS.isEmpty()) filters.add(String.join(",", POKEMON_FILTERS));
        if (pokemonKindFilter != PokemonKindFilter.ALL) filters.add(pokemonKindFilter.label);
        if (bossTierFilter != BossTierFilter.ALL) filters.add("boss:" + bossTierFilter.label);
        if (lootTypeFilter != LootTypeFilter.ALL) filters.add("loot:" + lootTypeFilter.label);
        if (shinyOnly) filters.add("shiny");
        if (minimumLevel != 1 || maximumLevel != Integer.MAX_VALUE) {
            filters.add("lv:" + minimumLevel + "-" + (maximumLevel == Integer.MAX_VALUE ? "*" : maximumLevel));
        }
        return filters.isEmpty() ? "" : "  |  " + String.join("/", filters);
    }

    private static void drawRadar(GuiGraphics graphics, Minecraft minecraft) {
        int centerX = graphics.guiWidth() - RADAR_RADIUS - 12;
        int centerY = graphics.guiHeight() - RADAR_RADIUS - 14;

        for (int dy = -RADAR_RADIUS; dy <= RADAR_RADIUS; dy++) {
            int halfWidth = (int) Math.sqrt(RADAR_RADIUS * RADAR_RADIUS - dy * dy);
            graphics.fill(centerX - halfWidth, centerY + dy, centerX + halfWidth + 1, centerY + dy + 1, 0xB0101620);
        }

        graphics.fill(centerX - RADAR_RADIUS + 5, centerY, centerX + RADAR_RADIUS - 4, centerY + 1, 0x4038BDF8);
        graphics.fill(centerX, centerY - RADAR_RADIUS + 5, centerX + 1, centerY + RADAR_RADIUS - 4, 0x4038BDF8);
        drawRing(graphics, centerX, centerY, RADAR_RADIUS / 2, 0x60475569);
        drawRing(graphics, centerX, centerY, RADAR_RADIUS, 0xFF22D3EE);

        Vec3 playerPos = minecraft.player.position();
        double yaw = Math.toRadians(minecraft.player.getYRot());
        double sinYaw = Math.sin(yaw);
        double cosYaw = Math.cos(yaw);
        String currentDimension = minecraft.level.dimension().location().toString();

        for (TrackedTarget target : TARGETS) {
            if (!target.dimension().equals(currentDimension)) {
                continue;
            }
            double dx = target.position().x - playerPos.x;
            double dz = target.position().z - playerPos.z;
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            if (horizontalDistance > RADAR_RANGE) {
                continue;
            }

            double forward = -sinYaw * dx + cosYaw * dz;
            double right = -cosYaw * dx - sinYaw * dz;
            int blipX = centerX + (int) Math.round(right / RADAR_RANGE * (RADAR_RADIUS - 5));
            int blipY = centerY - (int) Math.round(forward / RADAR_RANGE * (RADAR_RADIUS - 5));
            boolean pinned = pinnedTarget != null && target.id().equals(pinnedTarget.id());
            int size = pinned || target.shiny() || target.boss() || target.legendary() ? 5 : 3;
            int half = size / 2;
            int radarColor = pinned ? PINNED_COLOR : target.color();
            graphics.fill(blipX - half - (pinned ? 1 : 0), blipY - half - (pinned ? 1 : 0),
                    blipX - half + size + (pinned ? 1 : 0), blipY - half + size + (pinned ? 1 : 0), radarColor);
        }

        graphics.fill(centerX - 2, centerY - 2, centerX + 3, centerY + 3, 0xFFFFFFFF);
        String title = "RADAR  " + (int) RADAR_RANGE + "m";
        graphics.drawString(minecraft.font, title, centerX - minecraft.font.width(title) / 2, centerY - RADAR_RADIUS - 11, 0xFF67E8F9, true);
        graphics.drawString(minecraft.font, "P ciano  Loot laranja  Fixo rosa", centerX - 65,
                centerY + RADAR_RADIUS - 10, 0xFFA7B0C0, false);
    }

    private static void spawnParticleTrails(Minecraft minecraft) {
        Vec3 playerPos = minecraft.player.position();
        String currentDimension = minecraft.level.dimension().location().toString();
        if (pinnedTarget != null) {
            if (pinnedTarget.dimension().equals(currentDimension)) {
                spawnParticleTrail(minecraft, playerPos, pinnedTarget, PINNED_TRAIL_PARTICLE);
            }
            return;
        }
        TARGETS.stream()
                .filter(target -> target.type() == TrackedTarget.TargetType.POKEMON)
                .findFirst()
                .ifPresent(target -> spawnParticleTrail(minecraft, playerPos, target, POKEMON_TRAIL_PARTICLE));
        TARGETS.stream()
                .filter(target -> target.type() == TrackedTarget.TargetType.POKELOOT)
                .findFirst()
                .ifPresent(target -> spawnParticleTrail(minecraft, playerPos, target, LOOT_TRAIL_PARTICLE));
    }

    private static void spawnParticleTrail(Minecraft minecraft, Vec3 playerPos, TrackedTarget target,
                                           DustParticleOptions particle) {
        double dx = target.position().x - playerPos.x;
        double dz = target.position().z - playerPos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double visibleDistance = Math.min(distance, RADAR_RANGE);
        if (distance < 0.5) {
            return;
        }

        double endX = playerPos.x + dx / distance * visibleDistance;
        double endZ = playerPos.z + dz / distance * visibleDistance;
        int steps = Math.min(28, Math.max(2, (int) (visibleDistance / 3.5)));
        double groundY = playerPos.y + 0.15;
        for (int step = 1; step <= steps; step++) {
            double progress = step / (double) steps;
            double x = Mth.lerp(progress, playerPos.x, endX);
            double z = Mth.lerp(progress, playerPos.z, endZ);
            groundY = findTrailGroundY(minecraft, x, z, groundY);
            minecraft.level.addParticle(particle, true, x, groundY, z, 0.0, 0.01, 0.0);
        }

        double ringY = findTrailGroundY(minecraft, endX, endZ, groundY);
        for (int step = 0; step < 10; step++) {
            double angle = Math.PI * 2.0 * step / 10.0;
            minecraft.level.addParticle(particle, true,
                    endX + Math.cos(angle) * 0.55, ringY, endZ + Math.sin(angle) * 0.55,
                    0.0, 0.01, 0.0);
        }
    }

    private static double findTrailGroundY(Minecraft minecraft, double x, double z, double previousY) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        int centerY = Mth.floor(previousY);
        for (int y = centerY + 3; y >= centerY - 5; y--) {
            BlockPos feet = new BlockPos(blockX, y, blockZ);
            if (!minecraft.level.getBlockState(feet.below()).isAir()
                    && minecraft.level.getBlockState(feet).isAir()) {
                return y + 0.15;
            }
        }
        return previousY;
    }

    private static void drawRing(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        for (int degrees = 0; degrees < 360; degrees += 3) {
            double angle = Math.toRadians(degrees);
            int x = centerX + (int) Math.round(Math.cos(angle) * radius);
            int y = centerY + (int) Math.round(Math.sin(angle) * radius);
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static String formatTargetLine(Minecraft minecraft, TrackedTarget target, Vec3 playerPos) {
        boolean pinned = pinnedTarget != null && target.id().equals(pinnedTarget.id());
        String prefix = pinned ? "[FIXO] " : "";
        String suffix = target.stale() ? " [ULTIMA POSICAO]" : "";
        String currentDimension = minecraft.level.dimension().location().toString();
        if (!target.dimension().equals(currentDimension)) {
            return prefix + target.label() + " [OUTRA DIMENSAO]" + suffix;
        }
        double distance = Math.sqrt(target.position().distanceToSqr(playerPos));
        BlockPos pos = BlockPos.containing(target.position());
        String direction = relativeDirection(minecraft.player.getYRot(), playerPos, target.position());
        return String.format("%s%s  %s  %.0fm  [%d, %d, %d]%s", prefix, direction, target.label(), distance,
                pos.getX(), pos.getY(), pos.getZ(), suffix);
    }

    private static String relativeDirection(float playerYaw, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float relative = Mth.wrapDegrees(bearing - playerYaw);
        int sector = Mth.floor((relative + 22.5f) / 45.0f);
        return switch (Math.floorMod(sector, 8)) {
            case 0 -> "^";
            case 1 -> "^<";
            case 2 -> "<";
            case 3 -> "v<";
            case 4 -> "v";
            case 5 -> "v>";
            case 6 -> ">";
            default -> "^>";
        };
    }

    private enum PokemonKindFilter {
        ALL("Todos"), NORMAL("Normal"), BOSS("Boss"), MEGA("Mega"),
        LEGENDARY("Lendario"), SPECIAL("Especiais");

        private final String label;
        PokemonKindFilter(String label) { this.label = label; }
    }

    private enum BossTierFilter {
        ALL("Todos", ""), COMMON("Common", "common"), UNCOMMON("Uncommon", "uncommon"),
        RARE("Rare", "rare"), EPIC("Epic", "epic"), LEGENDARY("Legendary", "legendary"),
        ULTIMATE("Ultimate", "ultimate"), HAUNTED("Haunted", "haunted"),
        DROWNED("Drowned", "drowned"), EQUAL("Equal", "equal");

        private final String label;
        private final String id;
        BossTierFilter(String label, String id) { this.label = label; this.id = id; }
    }

    private enum LootTypeFilter {
        ALL("Todos", null), POKEBALL("Poke Ball", EnumPokeChestType.POKEBALL),
        ULTRABALL("Ultra Ball", EnumPokeChestType.ULTRABALL),
        MASTERBALL("Master Ball", EnumPokeChestType.MASTERBALL),
        BEASTBALL("Beast Ball", EnumPokeChestType.BEASTBALL),
        SPECIAL("Special", EnumPokeChestType.SPECIAL), GROTTO("Grotto", null);

        private final String label;
        private final EnumPokeChestType type;
        LootTypeFilter(String label, EnumPokeChestType type) { this.label = label; this.type = type; }
    }
}
