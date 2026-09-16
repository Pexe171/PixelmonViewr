package br.com.pixelmontracker.client;

import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

public final class TrackerScreen extends Screen {
    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 288;
    private static final int CONTENT_WIDTH = PANEL_WIDTH - 32;

    private Page page = Page.GENERAL;
    private EditBox filterInput;
    private EditBox minimumLevelInput;
    private EditBox maximumLevelInput;

    public TrackerScreen() {
        super(Component.literal("Pixelmon Tracker"));
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;

        addTab(left + 16, top + 29, 72, "Geral", Page.GENERAL);
        addTab(left + 94, top + 29, 72, "Filtros", Page.FILTERS);
        addTab(left + 172, top + 29, 72, "Alvos", Page.TARGETS);
        addTab(left + 250, top + 29, 72, "Trainer", Page.TRAINER);

        switch (page) {
            case GENERAL -> initGeneral(left, top);
            case FILTERS -> initFilters(left, top);
            case TARGETS -> initTargets(left, top);
            case TRAINER -> initTrainer(left, top);
        }
    }

    private void initGeneral(int left, int top) {
        addToggle(left + 16, top + 62, "Tracker", TrackerClient::isEnabled, TrackerClient::toggleEnabled);
        addRenderableWidget(Button.builder(Component.literal("Somente PokeLoot"), button -> {
            TrackerClient.onlyLoot();
            rebuildWidgets();
        }).bounds(left + 174, top + 62, 150, 20).build());

        addToggle(left + 16, top + 90, "Pokemon", TrackerClient::isShowingPokemon, TrackerClient::togglePokemon);
        addToggle(left + 174, top + 90, "PokeLoot", TrackerClient::isShowingLoot, TrackerClient::toggleLoot);
        addToggle(left + 16, top + 118, "Radar", TrackerClient::isShowingRadar, TrackerClient::toggleRadar);
        addToggle(left + 174, top + 118, "Marcadores 3D", TrackerClient::isShowingMarkers, TrackerClient::toggleMarkers);
        addToggle(left + 16, top + 146, "Rastros no chao", TrackerClient::isShowingTrails, TrackerClient::toggleTrails);

        addRenderableWidget(Button.builder(claimedLootLabel(), button -> {
            TrackerClient.clearLootHistory();
            button.setMessage(claimedLootLabel());
        }).bounds(left + 174, top + 146, 150, 20).build());

        addToggle(left + 16, top + 176, "Auto Trainer", TrackerClient::isAutoTrainerEnabled,
                TrackerClient::toggleAutoTrainer, CONTENT_WIDTH);

        addToggle(left + 16, top + 201, "Proteger raros", TrackerClient::isAutoTrainerProtectingRare,
                TrackerClient::toggleAutoTrainerProtectRare, CONTENT_WIDTH);

        addRenderableWidget(Button.builder(Component.literal("Desafixar alvo"), button -> {
            TrackerClient.clearPinnedTarget();
            rebuildWidgets();
        }).bounds(left + 16, top + 226, CONTENT_WIDTH, 20).build());
        addCloseButton(left, top);
    }

    private void initFilters(int left, int top) {
        filterInput = new EditBox(font, left + 16, top + 65, CONTENT_WIDTH, 20, Component.literal("Filtro de Pokemon"));
        filterInput.setHint(Component.literal("charmander,pikachu,eevee"));
        filterInput.setValue(TrackerClient.filterText());
        filterInput.setMaxLength(180);
        addRenderableWidget(filterInput);

        addRenderableWidget(Button.builder(Component.literal("Aplicar nomes"), button ->
                TrackerClient.setFilters(filterInput.getValue())
        ).bounds(left + 16, top + 88, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Limpar nomes"), button -> {
            filterInput.setValue("");
            TrackerClient.clearFilters();
        }).bounds(left + 174, top + 88, 150, 20).build());

        addCycle(left + 16, top + 113, "Pokemon", TrackerClient::pokemonKindLabel, TrackerClient::cyclePokemonKindFilter);
        addCycle(left + 174, top + 113, "Tier boss", TrackerClient::bossTierLabel, TrackerClient::cycleBossTierFilter);
        addCycle(left + 16, top + 138, "PokeLoot", TrackerClient::lootTypeLabel, TrackerClient::cycleLootTypeFilter, CONTENT_WIDTH);
        addToggle(left + 16, top + 163, "Somente Shiny", TrackerClient::isShinyOnly, TrackerClient::toggleShinyOnly, CONTENT_WIDTH);

        minimumLevelInput = levelBox(left + 16, top + 208, 54, "Min", TrackerClient.minimumLevelText());
        maximumLevelInput = levelBox(left + 78, top + 208, 54, "Max", TrackerClient.maximumLevelText());
        addRenderableWidget(Button.builder(Component.literal("Aplicar nivel"), button ->
                TrackerClient.setLevelFilter(minimumLevelInput.getValue(), maximumLevelInput.getValue())
        ).bounds(left + 140, top + 208, 184, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Resetar filtros"), button -> {
            TrackerClient.resetCategoryFilters();
            filterInput.setValue("");
            TrackerClient.clearFilters();
            rebuildWidgets();
        }).bounds(left + 16, top + 247, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Fechar"), button -> onClose())
                .bounds(left + 174, top + 247, 150, 20).build());
    }

    private void initTargets(int left, int top) {
        List<TrackedTarget> targets = TrackerClient.targetSnapshot();
        String pinnedId = TrackerClient.pinnedTargetId();
        int shown = Math.min(7, targets.size());
        for (int index = 0; index < shown; index++) {
            TrackedTarget target = targets.get(index);
            boolean pinned = target.id().equals(pinnedId);
            String prefix = pinned ? "[FIXADO] " : "";
            String label = font.plainSubstrByWidth("     " + prefix + target.label(), CONTENT_WIDTH - 10);
            addRenderableWidget(Button.builder(Component.literal(label), button -> {
                TrackerClient.togglePinnedTarget(target.id());
                rebuildWidgets();
            }).bounds(left + 16, top + 61 + index * 24, CONTENT_WIDTH, 20).build());
        }

        if (targets.isEmpty()) {
            Button empty = Button.builder(Component.literal("Nenhum alvo carregado"), button -> {})
                    .bounds(left + 16, top + 61, CONTENT_WIDTH, 20).build();
            empty.active = false;
            addRenderableWidget(empty);
        }

        addRenderableWidget(Button.builder(Component.literal("Desafixar alvo"), button -> {
            TrackerClient.clearPinnedTarget();
            rebuildWidgets();
        }).bounds(left + 16, top + 229, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Atualizar lista"), button -> rebuildWidgets())
                .bounds(left + 174, top + 229, 150, 20).build());
        addCloseButton(left, top);
    }

    private void initTrainer(int left, int top) {
        List<Pokemon> party = TrackerClient.trainerParty();
        UUID selected = TrackerClient.trainingPokemonId();
        for (int index = 0; index < party.size() && index < 6; index++) {
            Pokemon pokemon = party.get(index);
            boolean training = pokemon.getUUID().equals(selected);
            String prefix = training ? "[TREINO] " : "";
            String health = pokemon.getHealth() + "/" + pokemon.getMaxHealth() + " HP";
            String label = font.plainSubstrByWidth("     " + prefix + pokemon.getDisplayName().getString()
                    + " Lv." + pokemon.getPokemonLevel() + "  " + health, CONTENT_WIDTH - 10);
            addRenderableWidget(Button.builder(Component.literal(label), button -> {
                TrackerClient.selectTrainingPokemon(pokemon.getUUID());
                rebuildWidgets();
            }).bounds(left + 16, top + 61 + index * 28, CONTENT_WIDTH, 24).build());
        }
        if (party.isEmpty()) {
            Button empty = Button.builder(Component.literal("Equipe ainda nao carregada"), button -> {})
                    .bounds(left + 16, top + 61, CONTENT_WIDTH, 24).build();
            empty.active = false;
            addRenderableWidget(empty);
        }
        addRenderableWidget(Button.builder(Component.literal("Desativar treino por troca"), button -> {
            TrackerClient.selectTrainingPokemon(null);
            rebuildWidgets();
        }).bounds(left + 16, top + 229, CONTENT_WIDTH, 20).build());
        addCloseButton(left, top);
    }

    private EditBox levelBox(int x, int y, int width, String hint, String value) {
        EditBox box = new EditBox(font, x, y, width, 20, Component.literal(hint));
        box.setHint(Component.literal(hint));
        box.setValue(value);
        box.setMaxLength(3);
        box.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        addRenderableWidget(box);
        return box;
    }

    private void addTab(int x, int y, int width, String label, Page destination) {
        Button tab = Button.builder(Component.literal(label), button -> {
            page = destination;
            rebuildWidgets();
        }).bounds(x, y, width, 20).build();
        tab.active = page != destination;
        addRenderableWidget(tab);
    }

    private void addCloseButton(int left, int top) {
        addRenderableWidget(Button.builder(Component.literal("Fechar"), button -> onClose())
                .bounds(left + 16, top + 253, CONTENT_WIDTH, 20).build());
    }

    private void addToggle(int x, int y, String label, State state, Runnable toggle) {
        addToggle(x, y, label, state, toggle, 150);
    }

    private void addToggle(int x, int y, String label, State state, Runnable toggle, int buttonWidth) {
        Button button = Button.builder(toggleLabel(label, state.get()), pressed -> {
            toggle.run();
            pressed.setMessage(toggleLabel(label, state.get()));
        }).bounds(x, y, buttonWidth, 20).build();
        addRenderableWidget(button);
    }

    private void addCycle(int x, int y, String label, Label value, Runnable cycle) {
        addCycle(x, y, label, value, cycle, 150);
    }

    private void addCycle(int x, int y, String label, Label value, Runnable cycle, int buttonWidth) {
        Button button = Button.builder(cycleLabel(label, value.get()), pressed -> {
            cycle.run();
            pressed.setMessage(cycleLabel(label, value.get()));
        }).bounds(x, y, buttonWidth, 20).build();
        addRenderableWidget(button);
    }

    private Component cycleLabel(String label, String value) {
        return Component.literal(label + ": " + value);
    }

    private Component toggleLabel(String label, boolean active) {
        return Component.literal(label + ": " + (active ? "ON" : "OFF"));
    }

    private Component claimedLootLabel() {
        return Component.literal("Limpar pegos (" + TrackerClient.claimedLootCount() + ")");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE0101620);
        graphics.fill(left, top, left + 4, top + PANEL_HEIGHT, 0xFF22D3EE);
        graphics.drawCenteredString(font, "PIXELMON TRACKER", width / 2, top + 11, 0xFF67E8F9);
        if (page == Page.FILTERS) {
            graphics.drawString(font, "Nomes:", left + 16, top + 54, 0xFFA7B0C0, false);
            graphics.drawString(font, "Nivel minimo / maximo (vazio = sem limite):", left + 16, top + 196, 0xFFA7B0C0, false);
        } else if (page == Page.TARGETS) {
            graphics.drawString(font, "Clique para fixar; clique novamente para soltar.", left + 16, top + 51, 0xFFA7B0C0, false);
        } else if (page == Page.TRAINER) {
            graphics.drawString(font, "Escolha quem inicia e recebe XP pela troca.", left + 16, top + 51, 0xFFA7B0C0, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        if (page == Page.TARGETS) {
            List<TrackedTarget> targets = TrackerClient.targetSnapshot();
            int shown = Math.min(7, targets.size());
            for (int index = 0; index < shown; index++) {
                TrackerClient.drawTargetIcon(graphics, net.minecraft.client.Minecraft.getInstance(),
                        targets.get(index), left + 19, top + 62 + index * 24, 18);
            }
        } else if (page == Page.FILTERS) {
            drawFilterIcon(graphics, left + 145, top + 114, 0xFF22D3EE);
            drawFilterIcon(graphics, left + 303, top + 114, 0xFFAA66FF);
            drawFilterIcon(graphics, left + 303, top + 139, 0xFFFFAA00);
        } else if (page == Page.TRAINER) {
            List<Pokemon> party = TrackerClient.trainerParty();
            UUID selected = TrackerClient.trainingPokemonId();
            for (int index = 0; index < party.size() && index < 6; index++) {
                Pokemon pokemon = party.get(index);
                int rowY = top + 61 + index * 28;
                if (pokemon.getUUID().equals(selected)) {
                    graphics.fill(left + 16, rowY, left + 20, rowY + 24, 0xFFFF2BD6);
                }
                graphics.blit(pokemon.getSprite(), left + 20, rowY + 2, 20, 20,
                        0.0f, 0.0f, 32, 32, 32, 32);
            }
        }
    }

    private void drawFilterIcon(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF101620);
        graphics.fill(x, y, x + 3, y + 18, color);
        graphics.blit(TrackerClient.fallbackSprite(), x + 2, y + 1, 16, 16,
                0.0f, 0.0f, 32, 32, 32, 32);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private enum Page { GENERAL, FILTERS, TARGETS, TRAINER }

    @FunctionalInterface
    private interface State { boolean get(); }

    @FunctionalInterface
    private interface Label { String get(); }
}
