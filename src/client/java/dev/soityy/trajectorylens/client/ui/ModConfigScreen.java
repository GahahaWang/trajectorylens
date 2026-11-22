package dev.soityy.trajectorylens.client.ui;

import dev.soityy.trajectorylens.client.Lang;

import dev.soityy.trajectorylens.client.track.EntityCensus;
import dev.soityy.trajectorylens.client.track.FlowTracker;
import dev.soityy.trajectorylens.client.track.LootTracker;
import dev.soityy.trajectorylens.client.track.OverlayState;
import dev.soityy.trajectorylens.client.track.ProjectileOverlay;
import dev.soityy.trajectorylens.client.track.ThreatOverlay;
import dev.soityy.trajectorylens.client.track.WalkRangeOverlay;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;

/**
 * Tweakeroo-style tabbed settings screen. Pages stay short (no scrolling).
 * Watch lists can be edited here (add/remove item & entity types, incl.
 * quick-pick from nearby entities). Hotkeys bind by direct key capture.
 */
public class ModConfigScreen extends Screen {

    /**
     * Tab labels as translation keys. They are translated when the screen
     * is built, never in a static initialiser: a static field would freeze whichever language was
     * loaded first and keep it for the rest of the session.
     */
    private static final String[] PAGES = {
        "trajectorylens.panel.tab_overview", "trajectorylens.panel.tab_drops", "trajectorylens.panel.tab_projectiles",
        "trajectorylens.panel.tab_blasts", "trajectorylens.panel.tab_mobs", "trajectorylens.panel.tab_logistics",
        "trajectorylens.panel.tab_keys", "trajectorylens.panel.tab_tools"
    };

    private final Screen lastScreen;
    private final OverlayState drops;
    private final WalkRangeOverlay ranges;
    private final ProjectileOverlay projectiles;
    private final FlowTracker flow;
    private final EntityCensus census;
    private final ThreatOverlay threats;
    private final LootTracker loot;
    private final int page;
    private final List<LabeledButton> labels = new ArrayList<>();

    // direct key capture: 0 none, 1 range, 2 panel
    private int binding;
    private final Set<Integer> pressed = new LinkedHashSet<>();
    private boolean prevDown;
    private Button bindRangeBtn;
    private Button bindPanelBtn;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 78, 30);

    private record LabeledButton(Button button, Supplier<String> text) {
    }

    public ModConfigScreen(Screen lastScreen, OverlayState drops, WalkRangeOverlay ranges,
                           ProjectileOverlay projectiles, FlowTracker flow, EntityCensus census, ThreatOverlay threats,
                           LootTracker loot, int page) {
        super(Component.literal(Lang.tr("trajectorylens.panel.title")));
        this.lastScreen = lastScreen;
        this.drops = drops;
        this.ranges = ranges;
        this.projectiles = projectiles;
        this.flow = flow;
        this.census = census;
        this.threats = threats;
        this.loot = loot;
        this.page = Math.max(0, Math.min(PAGES.length - 1, page));
    }

    @Override
    protected void init() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(3));
        header.addChild(new StringWidget(this.getTitle(), this.font));
        // two rows of four tabs so labels stay readable
        int perRow = 4;
        int tabWidth = Math.max(56, (this.width - 24 - (perRow - 1) * 3) / perRow);
        for (int row = 0; row < 2; row++) {
            LinearLayout tabs = header.addChild(LinearLayout.horizontal()).spacing(3);
            for (int col = 0; col < perRow; col++) {
                int i = row * perRow + col;
                if (i >= PAGES.length) {
                    break;
                }
                final int pi = i;
                tabs.addChild(Button.builder(
                        Component.literal((i == this.page ? "§e» " : "§7") + Lang.tr(PAGES[i])),
                        b -> this.reload(pi))
                    .width(tabWidth).build());
            }
        }

        // two columns on wide windows: pages stay scroll-free without cramping labels
        int columns = this.width >= 560 ? 2 : 1;
        GridLayout grid = new GridLayout();
        grid.defaultCellSetting().padding(1).alignHorizontallyCenter();
        GridLayout.RowHelper helper = grid.createRowHelper(columns);
        int btnWidth = Math.min(330, (this.width - 24 - (columns - 1) * 6) / columns);
        buildPage(helper, btnWidth);

        this.layout.addToContents(grid);
        this.layout.addToFooter(Button.builder(Component.literal(Lang.tr("trajectorylens.panel.done")), b -> this.onClose()).width(150).build());
        this.layout.visitWidgets(w -> this.addRenderableWidget(w));
        this.repositionElements();
    }

    private void reload(int newPage) {
        this.minecraft.gui.setScreen(new ModConfigScreen(this.lastScreen, this.drops, this.ranges, this.projectiles, this.flow,
            this.census, this.threats, this.loot, newPage));
    }

    private void buildPage(GridLayout.RowHelper helper, int w) {
        switch (this.page) {
            case 0 -> buildOverviewPage(helper, w);
            case 1 -> buildDropsPage(helper, w);
            case 2 -> buildProjectilePage(helper, w);
            case 3 -> buildBlastPage(helper, w);
            case 4 -> buildMobPage(helper, w);
            case 5 -> buildFlowPage(helper, w);
            case 6 -> buildKeysPage(helper, w);
            default -> buildToolsPage(helper, w);
        }
    }

    // ------------------------------------------------------------------ 总览

    /** Everything a player flips most often, plus a live "what is on right now" readout. */
    private void buildOverviewPage(GridLayout.RowHelper helper, int w) {
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.drops", this.drops.visible()),
            Lang.tr("trajectorylens.panel.drops_tooltip_long"),
            () -> {
                this.drops.toggle();
                act();
            });
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.projectiles", this.projectiles.projectilesEnabled()),
            Lang.tr("trajectorylens.panel.projectiles_tooltip"),
            () -> {
                this.projectiles.toggleProjectiles();
                act();
            });
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.range", this.ranges.isEnabled()),
            Lang.tr("trajectorylens.panel.range_tooltip_long"),
            () -> {
                this.ranges.toggle();
                act();
            });
        helper.addChild(Button.builder(Component.literal(Lang.tr("trajectorylens.panel.enable_all")), b -> {
            this.drops.setEnabledState(true);
            this.projectiles.setProjectiles(true);
            this.projectiles.setTnt(true);
            this.projectiles.setAim(true);
            this.projectiles.setChain(true);
            this.projectiles.setFalling(true);
            this.ranges.setEnabledState(true);
            this.threats.setEnabled(true);
            this.loot.setEnabled(true);
            this.loot.setDespawnWarn(true);
            this.flow.setCounters(true);
            this.flow.setJam(true);
            act();
        }).width(w).build());
        helper.addChild(Button.builder(Component.literal(Lang.tr("trajectorylens.panel.disable_all")), b -> {
            this.drops.setEnabledState(false);
            this.projectiles.setProjectiles(false);
            this.projectiles.setTnt(false);
            this.projectiles.setAim(false);
            this.projectiles.setChain(false);
            this.projectiles.setFalling(false);
            this.ranges.setEnabledState(false);
            this.threats.setEnabled(false);
            this.loot.setEnabled(false);
            this.loot.setDespawnWarn(false);
            this.flow.setCounters(false);
            this.flow.setJam(false);
            act();
        }).width(w).build());
        helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.tracking",
            this.projectiles.entries().size(), this.flow.counters().size(), this.flow.jams().size())), this.font));
        helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.hotkeys",
            KeySettings.display(KeySettings.rangeChord), KeySettings.display(KeySettings.panelChord))), this.font));
    }

    // ------------------------------------------------------------- 掉落物

    /** Drop trajectories, the item watch list and everything about losing items. */
    private void buildDropsPage(GridLayout.RowHelper helper, int w) {
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.drops", this.drops.visible()),
            Lang.tr("trajectorylens.panel.drops_tooltip"),
            () -> {
                this.drops.toggle();
                act();
            });
        helper.addChild(Button.builder(Component.literal(Lang.tr("trajectorylens.panel.search_item")), b ->
            this.minecraft.gui.setScreen(new SearchPickerScreen(this.lastScreen, this.drops, this.ranges, this.projectiles, this.flow,
                this.census, this.threats, this.loot, true, this.page))
        ).width(w).build());
        helper.addChild(Button.builder(Component.literal(Lang.tr("trajectorylens.panel.clear_item_watch")), b -> {
            this.drops.clearTargets();
            act();
        }).width(w).build());
        addWatchedRows(helper, w, watchItemIds(), true);
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.losttrack", this.loot.enabled()),
            Lang.tr("trajectorylens.panel.losttrack_tooltip"),
            () -> {
                this.loot.toggle();
                act();
            });
        addToggle(helper, w,
            () -> Lang.tr("trajectorylens.panel.lost_time", this.loot.markerSeconds()),
            Lang.tr("trajectorylens.panel.lost_time_tooltip"),
            () -> {
                this.loot.cycleMarkerSeconds();
                act();
            });
        addToggle(helper, w,
            () -> Lang.tr("trajectorylens.panel.glow_time", this.loot.glowSeconds()),
            Lang.tr("trajectorylens.panel.glow_time_tooltip"),
            () -> {
                this.loot.cycleGlowSeconds();
                act();
            });
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.despawn", this.loot.despawnWarnEnabled()),
            Lang.tr("trajectorylens.panel.despawn_tooltip"),
            () -> {
                this.loot.toggleDespawnWarn();
                act();
            });
        helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.item_color_hint")), this.font));
    }

    /** Inline "current watch list" rows shared by the drops and mob pages. */
    private void addWatchedRows(GridLayout.RowHelper helper, int w, List<String> watched, boolean forItems) {
        if (watched.isEmpty()) {
            helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.watching_all")), this.font));
            return;
        }
        int shown = Math.min(3, watched.size());
        for (int i = 0; i < shown; i++) {
            final String id = watched.get(i);
            helper.addChild(Button.builder(Component.literal("✕ " + shortId(id)), b -> {
                if (forItems) {
                    Item it = resolveItem(id);
                    if (it != null) {
                        this.drops.removeTarget(it);
                    }
                } else {
                    this.ranges.removeType(id);
                }
                act();
            }).width(w).build());
        }
        if (watched.size() > 3) {
            helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.more_types", watched.size())), this.font));
        }
    }

    // --------------------------------------------------------------- 生物

    /** Mobs: threat readout plus the walk-range prediction and its watch list. */
    private void buildMobPage(GridLayout.RowHelper helper, int w) {
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.threat", this.threats.enabled()),
            Lang.tr("trajectorylens.panel.threat_tooltip"),
            () -> {
                this.threats.toggle();
                act();
            });
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.range", this.ranges.isEnabled()),
            Lang.tr("trajectorylens.panel.range_tooltip"),
            () -> {
                this.ranges.toggle();
                act();
            });
        addToggle(helper, w,
            () -> Lang.tr("trajectorylens.panel.range_horizon", this.ranges.horizonSeconds()),
            Lang.tr("trajectorylens.panel.range_horizon_tooltip"),
            () -> {
                int cur = this.ranges.horizonSeconds();
                int next = switch (cur) {
                    case 5 -> 10;
                    case 10 -> 15;
                    case 15 -> 20;
                    case 20 -> 30;
                    case 30 -> 60;
                    default -> 5;
                };
                this.ranges.setHorizon(next);
                act();
            });
        helper.addChild(Button.builder(Component.literal(Lang.tr("trajectorylens.panel.search_entity")), b ->
            this.minecraft.gui.setScreen(new SearchPickerScreen(this.lastScreen, this.drops, this.ranges, this.projectiles, this.flow,
                this.census, this.threats, this.loot, false, this.page))
        ).width(w).build());
        helper.addChild(Button.builder(Component.literal(Lang.tr("trajectorylens.panel.clear_type_watch")), b -> {
            this.ranges.clearTypes();
            act();
        }).width(w).build());
        addWatchedRows(helper, w, this.ranges.typeIdsSnapshot(), false);
        helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.range_color_hint")), this.font));
    }


    /** Projectiles in flight, the held-item aim preview and the blast warning. */
    private void buildProjectilePage(GridLayout.RowHelper helper, int w) {
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.projectiles", this.projectiles.projectilesEnabled()),
            Lang.tr("trajectorylens.panel.projectiles_tooltip"),
            () -> {
                this.projectiles.toggleProjectiles();
                act();
            });
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.aim", this.projectiles.aimEnabled()),
            Lang.tr("trajectorylens.panel.aim_tooltip"),
            () -> {
                this.projectiles.toggleAim();
                act();
            });
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.tnt", this.projectiles.tntEnabled()),
            Lang.tr("trajectorylens.panel.tnt_tooltip"),
            () -> {
                this.projectiles.toggleTnt();
                act();
            });
        addToggle(helper, w,
            () -> Lang.tr("trajectorylens.panel.text_size", this.projectiles.textPercent()),
            Lang.tr("trajectorylens.panel.text_size_tooltip"),
            () -> {
                this.projectiles.cycleTextPercent();
                act();
            });
        helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.projectiles_note")), this.font));
    }

    /** What an explosion does next: chained blasts, launched entities, falling blocks. */
    private void buildBlastPage(GridLayout.RowHelper helper, int w) {
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.chain", this.projectiles.chainEnabled()),
            Lang.tr("trajectorylens.panel.chain_tooltip"),
            () -> {
                this.projectiles.toggleChain();
                act();
            });
        addToggle(helper, w,
            () -> Lang.tr("trajectorylens.panel.chain_depth", this.projectiles.chainDepth()),
            Lang.tr("trajectorylens.panel.chain_depth_tooltip"),
            () -> {
                this.projectiles.cycleChainDepth();
                act();
            });
        addToggle(helper, w,
            () -> Lang.tr("trajectorylens.panel.chain_horizon", this.projectiles.chainHorizonSeconds()),
            Lang.tr("trajectorylens.panel.chain_horizon_tooltip"),
            () -> {
                this.projectiles.cycleChainHorizon();
                act();
            });
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.falling", this.projectiles.fallingEnabled()),
            Lang.tr("trajectorylens.panel.falling_tooltip"),
            () -> {
                this.projectiles.toggleFalling();
                act();
            });
        helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.chain_note")), this.font));
    }

    /** Logistics: entity census, chokepoint counters with trend, hopper jams. */
    private void buildFlowPage(GridLayout.RowHelper helper, int w) {
        helper.addChild(new StringWidget(Component.literal("§e" + this.census.compact()), this.font));
        helper.addChild(new StringWidget(Component.literal("§6" + this.loot.summary()), this.font));
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.jam", this.flow.jamEnabled()),
            Lang.tr("trajectorylens.panel.jam_tooltip"),
            () -> {
                this.flow.toggleJam();
                act();
            });
        addToggle(helper, w,
            () -> Lang.tr("trajectorylens.panel.jam_time", this.flow.jamSeconds()),
            Lang.tr("trajectorylens.panel.jam_time_tooltip"),
            () -> {
                this.flow.cycleJamSeconds();
                act();
            });
        addToggle(helper, w,
            () -> toggleText("trajectorylens.panel.counters", this.flow.countersEnabled()),
            Lang.tr("trajectorylens.panel.counters_tooltip"),
            () -> {
                this.flow.toggleCounters();
                act();
            });
        helper.addChild(Button.builder(Component.literal(Lang.tr("trajectorylens.panel.add_counter")), b -> {
            String name = "c" + (this.flow.counters().size() + 1);
            String err = this.flow.addCounter(name);
            var pl = this.minecraft.player;
            if (pl != null) {
                pl.sendSystemMessage(Component.literal(err != null
                    ? "[TrajectoryLens] " + err
                    : Lang.tr("trajectorylens.panel.counter_added", name)));
            }
            act();
        }).width(w).build());

        int shown = 0;
        for (var c : this.flow.counters().values()) {
            if (shown++ >= 2) {
                helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.more_counters", this.flow.counters().size())), this.font));
                break;
            }
            final String name = c.name;
            String dir = switch (c.trendDir()) {
                case 1 -> "§a↑";
                case -1 -> "§c↓";
                default -> "§7→";
            };
            Button row = Button.builder(Component.literal(Lang.tr("trajectorylens.panel.counter_row",
                    name, c.perMinute(), c.spark(), dir, Lang.fixed(c.backlog, 0))), b -> {
                this.flow.removeCounter(name);
                act();
            }).width(w).build();
            row.setTooltip(Tooltip.create(Component.literal(Lang.tr("trajectorylens.panel.counter_row_tooltip"))));
            helper.addChild(row);
        }
        if (this.flow.counters().isEmpty()) {
            helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.no_counters")), this.font));
        }
        helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.jam_legend")), this.font));
    }

    /** Tools: dump state to chat, export a report file, vanilla key settings, cheatsheet. */
    private void buildToolsPage(GridLayout.RowHelper helper, int w) {
        Button listBtn = Button.builder(Component.literal(Lang.tr("trajectorylens.panel.dump")), b -> {
            var pl = this.minecraft.player;
            if (pl != null) {
                pl.sendSystemMessage(Component.literal("[TrajectoryLens] " + this.drops.listing()));
                pl.sendSystemMessage(Component.literal("[TrajectoryLens] " + this.ranges.summary()));
                pl.sendSystemMessage(Component.literal("[TrajectoryLens] " + this.projectiles.summary()));
                pl.sendSystemMessage(Component.literal("[TrajectoryLens] " + this.flow.summary()));
            }
        }).width(w).build();
        listBtn.setTooltip(Tooltip.create(Component.literal(Lang.tr("trajectorylens.panel.dump_tooltip"))));
        helper.addChild(listBtn);

        Button exportBtn = Button.builder(Component.literal(Lang.tr("trajectorylens.panel.export")), b -> {
            var f = Report.write(Report.lines(this.drops, this.ranges, this.projectiles, this.flow,
                this.census, this.threats, this.loot));
            var pl = this.minecraft.player;
            if (pl != null) {
                pl.sendSystemMessage(Component.literal(f != null
                    ? Lang.tr("trajectorylens.command.report_written", f.getName())
                    : Lang.tr("trajectorylens.command.report_failed")));
            }
        }).width(w).build();
        exportBtn.setTooltip(Tooltip.create(Component.literal(Lang.tr("trajectorylens.panel.export_tooltip"))));
        helper.addChild(exportBtn);

        Button keysBtn = Button.builder(Component.literal(Lang.tr("trajectorylens.panel.vanilla_controls")), b ->
            this.minecraft.gui.setScreen(new net.minecraft.client.gui.screens.options.controls.ControlsScreen(this, this.minecraft.options))
        ).width(w).build();
        keysBtn.setTooltip(Tooltip.create(Component.literal(Lang.tr("trajectorylens.panel.vanilla_controls_tooltip"))));
        helper.addChild(keysBtn);

        Button reportBtn = Button.builder(Component.literal(Lang.tr("trajectorylens.panel.lost_log")), b -> {
            var pl = this.minecraft.player;
            if (pl != null) {
                for (String line : this.loot.report()) {
                    pl.sendSystemMessage(Component.literal(line));
                }
            }
        }).width(w).build();
        reportBtn.setTooltip(Tooltip.create(Component.literal(Lang.tr("trajectorylens.panel.lost_log_tooltip"))));
        helper.addChild(reportBtn);

        helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.cmd_logistics")), this.font));
        helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.cmd_items")), this.font));
        helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.cmd_combat")), this.font));
        helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.cmd_display")), this.font));
    }

    private void buildKeysPage(GridLayout.RowHelper helper, int w) {
        bindRangeBtn = bindRow(helper, w, Lang.tr("trajectorylens.panel.range_chord"),
            Lang.tr("trajectorylens.panel.chord_tooltip"), 1);
        bindPanelBtn = bindRow(helper, w, Lang.tr("trajectorylens.panel.panel_chord"),
            Lang.tr("trajectorylens.panel.panel_chord_tooltip"), 2);
        helper.addChild(new StringWidget(Component.literal(Lang.tr("trajectorylens.panel.chord_howto")), this.font));
    }

    private String bindingText() {
        return Lang.tr("trajectorylens.panel.binding", this.pressed.isEmpty()
            ? Lang.tr("trajectorylens.panel.press_keys")
            : KeySettings.display(KeySettings.chordFrom(List.copyOf(this.pressed))));
    }

    private static String toggleText(String key, boolean on) {
        return Lang.tr(key, Lang.tr(on ? "trajectorylens.panel.on" : "trajectorylens.panel.off"));
    }

    private void addToggle(GridLayout.RowHelper helper, int w, Supplier<String> text, String tooltip, Runnable action) {
        Button b = Button.builder(Component.literal(text.get()), btn -> {
            action.run();
            repaint();
        }).width(w).build();
        b.setTooltip(Tooltip.create(Component.literal(tooltip)));
        helper.addChild(b);
        this.labels.add(new LabeledButton(b, text));
    }

    private Button bindRow(GridLayout.RowHelper helper, int w, String name, String tooltip, int target) {
        Button b = Button.builder(Component.literal(Lang.tr("trajectorylens.panel.placeholder")), btn -> {
            if (this.binding == target) {
                this.binding = 0;
                this.pressed.clear();
            } else {
                this.binding = target;
                this.pressed.clear();
            }
            repaint();
        }).width(w).build();
        b.setTooltip(Tooltip.create(Component.literal(tooltip)));
        helper.addChild(b);
        if (target == 1) {
            this.bindRangeBtn = b;
        } else {
            this.bindPanelBtn = b;
        }
        repaint();
        return b;
    }

    /** Call after any change that should rebuild the current page (list changes). */
    private void act() {
        save();
        this.reload(this.page);
    }

    private void save() {
        SettingsIO.snapshot(this.drops, this.ranges, this.projectiles, this.flow, this.threats, this.loot).save();
    }

    /** Refresh labels of toggle/cycle/bind rows without rebuilding. */
    private void repaint() {
        for (LabeledButton lb : this.labels) {
            lb.button().setMessage(Component.literal(lb.text().get()));
        }
        if (this.bindRangeBtn != null) {
            String t = this.binding == 1
                ? bindingText()
                : Lang.tr("trajectorylens.panel.range_chord_label", KeySettings.display(KeySettings.rangeChord));
            this.bindRangeBtn.setMessage(Component.literal(t));
        }
        if (this.bindPanelBtn != null) {
            String t = this.binding == 2
                ? bindingText()
                : Lang.tr("trajectorylens.panel.panel_chord_label", KeySettings.display(KeySettings.panelChord));
            this.bindPanelBtn.setMessage(Component.literal(t));
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.binding != 0) {
            int code = event.key();
            if (code == InputConstants.KEY_ESCAPE) {
                this.binding = 0;
                this.pressed.clear();
                repaint();
                return true;
            }
            if (code == InputConstants.KEY_BACKSPACE) {
                setChord(new int[0]);
                this.binding = 0;
                this.pressed.clear();
                repaint();
                save();
                return true;
            }
            this.pressed.add(code);
            repaint();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void tick() {
        if (this.binding != 0 && !this.pressed.isEmpty()) {
            var win = this.minecraft.getWindow();
            boolean anyDown = false;
            for (int k : this.pressed) {
                if (InputConstants.isKeyDown(k)) {
                    anyDown = true;
                    break;
                }
            }
            if (!anyDown && this.prevDown) {
                setChord(KeySettings.chordFrom(List.copyOf(this.pressed)));
                this.binding = 0;
                this.pressed.clear();
                repaint();
                save();
            }
            this.prevDown = anyDown;
        } else {
            this.prevDown = false;
        }
        if (this.binding != 0) {
            repaint();
        }
        super.tick();
    }

    private void setChord(int[] chord) {
        if (this.binding == 1) {
            KeySettings.rangeChord = chord;
        } else if (this.binding == 2) {
            KeySettings.panelChord = chord;
        }
    }

    // ---------- data helpers ----------

    private List<String> watchItemIds() {
        List<String> out = new ArrayList<>();
        for (Item it : this.drops.watchItems()) {
            String id = OverlayState.itemIdOf(it.getDefaultInstance());
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    private Item resolveItem(String canonicalId) {
        var level = this.minecraft.level;
        if (level == null) {
            return null;
        }
        net.minecraft.core.Registry<Item> reg =
            level.registryAccess().lookup(net.minecraft.core.registries.Registries.ITEM).orElse(null);
        if (reg == null) {
            return null;
        }
        try {
            var id = net.minecraft.resources.Identifier.fromNamespaceAndPath(
                canonicalId.contains(":") ? canonicalId.substring(0, canonicalId.indexOf(':')) : "minecraft",
                canonicalId.contains(":") ? canonicalId.substring(canonicalId.indexOf(':') + 1) : canonicalId);
            return reg.getValue(id);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> nearbyTypes(boolean forItems) {
        List<String> out = new ArrayList<>();
        var level = this.minecraft.level;
        var player = this.minecraft.player;
        if (level == null || player == null) {
            return out;
        }
        Set<String> have = new LinkedHashSet<>();
        if (forItems) {
            List<String> watched = watchItemIds();
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(48.0))) {
                if (!e.isAlive() || e.getItem().isEmpty()) {
                    continue;
                }
                String id = OverlayState.itemIdOf(e.getItem());
                if (id == null || have.contains(id) || watched.contains(id)) {
                    continue;
                }
                have.add(id);
                out.add(id);
                if (out.size() >= 8) {
                    break;
                }
            }
        } else {
            List<String> watched = this.ranges.typeIdsSnapshot();
            for (Mob m : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(48.0))) {
                if (!m.isAlive()) {
                    continue;
                }
                String id = WalkRangeOverlay.typeIdOf(m);
                if (id == null || have.contains(id) || watched.contains(id)) {
                    continue;
                }
                have.add(id);
                out.add(id);
                if (out.size() >= 8) {
                    break;
                }
            }
        }
        return out;
    }

    private static String shortId(String id) {
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    @Override
    public void onClose() {
        save();
        this.minecraft.gui.setScreen(this.lastScreen);
    }
}