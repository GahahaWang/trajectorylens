package dev.soityy.trajectorylens.client.command;

import dev.soityy.trajectorylens.client.track.FlowTracker;
import dev.soityy.trajectorylens.client.track.LootTracker;
import dev.soityy.trajectorylens.client.track.OverlayState;
import dev.soityy.trajectorylens.client.track.ProjectileOverlay;
import dev.soityy.trajectorylens.client.track.ThreatOverlay;
import dev.soityy.trajectorylens.client.track.WalkRangeOverlay;
import dev.soityy.trajectorylens.client.Lang;
import dev.soityy.trajectorylens.client.TrajectoryLensClient;
import dev.soityy.trajectorylens.client.ui.Report;
import dev.soityy.trajectorylens.client.ui.SettingsIO;
import dev.soityy.trajectorylens.util.PathTools;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Client-side fallbacks of the same command tree (used when the server does not
 * run this mod). With the mod on the server the server copies take precedence.
 */
public final class ClientCommands {
    private ClientCommands() {
    }

    public static void register(OverlayState state, WalkRangeOverlay ranges, ProjectileOverlay projectiles, FlowTracker flow) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root =
                LiteralArgumentBuilder.<FabricClientCommandSource>literal("trajectorylens");
            root.then(targetTree(state));
            root.then(colorTree(state));
            root.then(rangeTree(ranges));
            root.then(colorsTree(state, ranges));
            root.then(toggleTree(state));
            root.then(statusTree(state, ranges));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("gui")
                .executes(ctx -> {
                    TrajectoryLensClient.openPanel();
                    return 1;
                }));
            root.then(switchTree("projectiles", projectiles, true, state, ranges, projectiles, flow));
            root.then(switchTree("tnt", projectiles, false, state, ranges, projectiles, flow));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("losttrack")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(ctx -> lootSwitch(ctx.getSource(), true)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(ctx -> lootSwitch(ctx.getSource(), false)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle").executes(ctx -> lootSwitch(ctx.getSource(), null))));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("lost")
                .executes(ctx -> {
                    for (String line : TrajectoryLensClient.loot().report()) {
                        ctx.getSource().sendFeedback(Component.literal(line));
                    }
                    return 1;
                })
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear").executes(ctx -> {
                    TrajectoryLensClient.loot().clear();
                    ctx.getSource().sendFeedback(Component.literal(Lang.tr("trajectorylens.command.lost_cleared")));
                    return 1;
                })));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("threat")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(ctx -> threatSwitch(ctx.getSource(), TrajectoryLensClient.threats(), state, ranges, projectiles, flow, true)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(ctx -> threatSwitch(ctx.getSource(), TrajectoryLensClient.threats(), state, ranges, projectiles, flow, false)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle").executes(ctx -> threatSwitch(ctx.getSource(), TrajectoryLensClient.threats(), state, ranges, projectiles, flow, null))));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("census").executes(ctx -> {
                for (String line : TrajectoryLensClient.census().report()) {
                    ctx.getSource().sendFeedback(Component.literal(line));
                }
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("aim")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(ctx -> aimSwitch(ctx.getSource(), projectiles, flow, state, ranges, true)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(ctx -> aimSwitch(ctx.getSource(), projectiles, flow, state, ranges, false)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle").executes(ctx -> aimSwitch(ctx.getSource(), projectiles, flow, state, ranges, null))));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("chain")
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(ctx -> chainSwitch(ctx.getSource(), projectiles, flow, state, ranges, true)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(ctx -> chainSwitch(ctx.getSource(), projectiles, flow, state, ranges, false)))
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle").executes(ctx -> chainSwitch(ctx.getSource(), projectiles, flow, state, ranges, null))));
            root.then(switchNode("jam", TrajectoryLensClient.flow(), state, ranges, projectiles, flow));
            root.then(switchNode("despawn", TrajectoryLensClient.loot(), state, ranges, projectiles, flow));
            root.then(switchNode("falling", TrajectoryLensClient.projectiles(), state, ranges, projectiles, flow));
            root.then(lostTimeTree("jamtime", "trajectorylens.feature.jam_time", TimeKind.JAM, state, ranges, projectiles, flow));
            root.then(lostTimeTree("losttime", "trajectorylens.feature.lost_time", TimeKind.MARKER, state, ranges, projectiles, flow));
            root.then(lostTimeTree("glowtime", "trajectorylens.feature.glow_time", TimeKind.GLOW, state, ranges, projectiles, flow));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("chaindepth")
                .executes(ctx -> chainOption(ctx.getSource(), state, ranges, projectiles, flow, -1, true))
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("layers", IntegerArgumentType.integer(1, 4))
                    .executes(ctx -> chainOption(ctx.getSource(), state, ranges, projectiles, flow,
                        IntegerArgumentType.getInteger(ctx, "layers"), true))));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("chainhorizon")
                .executes(ctx -> chainOption(ctx.getSource(), state, ranges, projectiles, flow, -1, false))
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("seconds", IntegerArgumentType.integer(1, 30))
                    .executes(ctx -> chainOption(ctx.getSource(), state, ranges, projectiles, flow,
                        IntegerArgumentType.getInteger(ctx, "seconds"), false))));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("export").executes(ctx -> {
                var f = Report.write(Report.lines(state, ranges, projectiles, flow, TrajectoryLensClient.census(),
                    TrajectoryLensClient.threats(), TrajectoryLensClient.loot()));
                ctx.getSource().sendFeedback(Component.literal(f != null
                    ? Lang.tr("trajectorylens.command.report_written", f.getName())
                    : Lang.tr("trajectorylens.command.report_failed")));
                return 1;
            }));
            root.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("flowcount")
                .then(flowSwitch("on", state, ranges, projectiles, flow))
                .then(flowSwitch("off", state, ranges, projectiles, flow))
                .then(flowSwitch("toggle", state, ranges, projectiles, flow)));
            root.then(counterTree(state, ranges, projectiles, flow));
            dispatcher.register(root);
            // short alias: /tl ... redirects to the full command tree
            var main = dispatcher.getRoot().getChild("trajectorylens");
            if (main != null) {
                dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("tl").redirect(main));
            }
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> targetTree(OverlayState state) {
        var tree = LiteralArgumentBuilder.<FabricClientCommandSource>literal("target");
        tree.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
            .executes(ctx -> {
                state.clearTargets();
                ctx.getSource().sendFeedback(Component.literal(Lang.tr("trajectorylens.command.watch_cleared")));
                return 1;
            }));
        tree.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("item", StringArgumentType.string())
            .suggests(ClientCommands::suggestItems)
            .executes(ctx -> {
                String raw = StringArgumentType.getString(ctx, "item");
                String err = state.setTargetFromId(raw);
                if (err != null) {
                    ctx.getSource().sendError(Component.literal("[TrajectoryLens] " + err));
                    return 0;
                }
                ctx.getSource().sendFeedback(Component.literal(
                    Lang.tr("trajectorylens.command.target_added", raw, state.targetName())));
                return 1;
            }));
        return tree;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> colorTree(OverlayState state) {
        var tree = LiteralArgumentBuilder.<FabricClientCommandSource>literal("color");
        tree.then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("item", StringArgumentType.string())
            .suggests(ClientCommands::suggestItems)
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("color", StringArgumentType.string())
                .executes(ctx -> setColorLocal(ctx.getSource(), state,
                    StringArgumentType.getString(ctx, "item"),
                    StringArgumentType.getString(ctx, "color")))));
        return tree;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> rangeTree(WalkRangeOverlay ranges) {
        var tree = LiteralArgumentBuilder.<FabricClientCommandSource>literal("range");
        tree.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("type", StringArgumentType.string())
                .suggests(ClientCommands::suggestEntityTypes)
                .executes(ctx -> {
                    String raw = StringArgumentType.getString(ctx, "type");
                    String err = ranges.addTypeFromId(raw);
                    if (err != null) {
                        ctx.getSource().sendError(Component.literal("[TrajectoryLens] " + err));
                        return 0;
                    }
                    ctx.getSource().sendFeedback(Component.literal(Lang.tr("trajectorylens.command.range_added", raw, ranges.summary())));
                    return 1;
                })));
        tree.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear")
            .executes(ctx -> {
                ranges.clearTypes();
                ctx.getSource().sendFeedback(Component.literal(Lang.tr("trajectorylens.command.range_cleared")));
                return 1;
            }));
        tree.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list")
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Component.literal("[TrajectoryLens] " + ranges.summary()));
                return 1;
            }));
        tree.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("time")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("seconds", IntegerArgumentType.integer(1, 60))
                .executes(ctx -> {
                    ranges.setHorizon(IntegerArgumentType.getInteger(ctx, "seconds"));
                    ctx.getSource().sendFeedback(Component.literal(Lang.tr("trajectorylens.command.range_time", ranges.horizonSeconds())));
                    return 1;
                })));
        tree.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("color")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("type", StringArgumentType.string())
                .suggests(ClientCommands::suggestEntityTypes)
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("color", StringArgumentType.string())
                    .executes(ctx -> {
                        String typeRaw = StringArgumentType.getString(ctx, "type");
                        String spec = StringArgumentType.getString(ctx, "color");
                        String typeId = WalkRangeOverlay.canonicalTypeId(WalkRangeOverlay.resolveType(typeRaw));
                        if (typeId == null) {
                            ctx.getSource().sendError(Component.literal(
                                "[TrajectoryLens] " + Lang.tr("trajectorylens.error.unknown_entity_type", typeRaw)));
                            return 0;
                        }
                        String err = ranges.setColor(typeId, spec);
                        if (err != null) {
                            ctx.getSource().sendError(Component.literal("[TrajectoryLens] " + err));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(Component.literal(
                            Lang.tr("trajectorylens.command.range_color", typeId, colorLabel(spec))));
                        return 1;
                    }))));
        tree.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle")
            .executes(ctx -> {
                ranges.toggle();
                ctx.getSource().sendFeedback(Component.literal(changed("trajectorylens.feature.range", Lang.onOff(ranges.isEnabled()))));
                return 1;
            }));
        return tree;
    }

    public static String changed(String featureKey, String value) {
        return Lang.tr("trajectorylens.command.changed", Lang.tr(featureKey), value);
    }

    public static String colorLabel(String spec) {
        String trimmed = spec.trim();
        if (trimmed.equalsIgnoreCase("auto")) {
            return Lang.tr("trajectorylens.common.auto");
        }
        Integer argb = PathTools.parseColor(trimmed);
        return argb == null ? trimmed : "#" + SettingsIO.specOf(argb);
    }

    /** on / off / toggle switch for the projectile & TNT overlays. */
    private static int lootSwitch(FabricClientCommandSource src, Boolean on) {
        LootTracker loot = TrajectoryLensClient.loot();
        boolean value = on != null ? on : !loot.enabled();
        loot.setEnabled(value);
        src.sendFeedback(Component.literal(changed("trajectorylens.feature.losttrack", Lang.onOff(value))));
        return 1;
    }

    private static int threatSwitch(FabricClientCommandSource src, ThreatOverlay threats, OverlayState state,
                                    WalkRangeOverlay ranges, ProjectileOverlay proj, FlowTracker flow, Boolean on) {
        boolean value = on != null ? on : !threats.enabled();
        threats.setEnabled(value);
        SettingsIO.snapshot(state, ranges, proj, flow, threats, TrajectoryLensClient.loot()).save();
        src.sendFeedback(Component.literal(changed("trajectorylens.feature.threat", Lang.onOff(value))));
        return 1;
    }

    private static int aimSwitch(FabricClientCommandSource src, ProjectileOverlay proj, FlowTracker flow,
                                 OverlayState state, WalkRangeOverlay ranges, Boolean on) {
        boolean value = on != null ? on : !proj.aimEnabled();
        proj.setAim(value);
        SettingsIO.snapshot(state, ranges, proj, flow, TrajectoryLensClient.threats(), TrajectoryLensClient.loot()).save();
        src.sendFeedback(Component.literal(changed("trajectorylens.feature.aim", Lang.onOff(value))));
        return 1;
    }

    /** /trajectorylens jam|despawn|falling on|off|toggle -- the newer client-side overlays. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> switchNode(String name, Object target,
        OverlayState state, WalkRangeOverlay ranges, ProjectileOverlay proj, FlowTracker flow) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal(name)
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on")
                .executes(ctx -> extraSwitch(ctx.getSource(), target, Boolean.TRUE, state, ranges, proj, flow)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off")
                .executes(ctx -> extraSwitch(ctx.getSource(), target, Boolean.FALSE, state, ranges, proj, flow)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle")
                .executes(ctx -> extraSwitch(ctx.getSource(), target, null, state, ranges, proj, flow)));
    }

    private static int extraSwitch(FabricClientCommandSource src, Object target, Boolean on,
                                   OverlayState state, WalkRangeOverlay ranges, ProjectileOverlay proj, FlowTracker flow) {
        String label;
        boolean value;
        if (target instanceof FlowTracker f) {
            label = "trajectorylens.feature.jam";
            value = on != null ? on : !f.jamEnabled();
            f.setJam(value);
        } else if (target instanceof LootTracker l) {
            label = "trajectorylens.feature.despawn";
            value = on != null ? on : !l.despawnWarnEnabled();
            l.setDespawnWarn(value);
        } else {
            label = "trajectorylens.feature.falling";
            value = on != null ? on : !proj.fallingEnabled();
            proj.setFalling(value);
        }
        SettingsIO.snapshot(state, ranges, proj, flow, TrajectoryLensClient.threats(), TrajectoryLensClient.loot()).save();
        src.sendFeedback(Component.literal(changed(label, Lang.onOff(value))));
        return 1;
    }

    /** Which "duration" setting a time command edits. */
    private enum TimeKind { MARKER, GLOW, JAM }

    /** /trajectorylens losttime [秒] | glowtime [秒] | jamtime [秒] -- how long markers, hopper highlights and jam detection last. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> lostTimeTree(String name, String label, TimeKind kind,
        OverlayState state, WalkRangeOverlay ranges, ProjectileOverlay proj, FlowTracker flow) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal(name)
            .executes(ctx -> lostTime(ctx.getSource(), label, kind, -1, state, ranges, proj, flow))
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("seconds", IntegerArgumentType.integer(1, 300))
                .executes(ctx -> lostTime(ctx.getSource(), label, kind, IntegerArgumentType.getInteger(ctx, "seconds"),
                    state, ranges, proj, flow)));
    }

    private static int lostTime(FabricClientCommandSource src, String label, TimeKind kind, int seconds,
                                OverlayState state, WalkRangeOverlay ranges, ProjectileOverlay proj, FlowTracker flow) {
        LootTracker loot = TrajectoryLensClient.loot();
        int now;
        switch (kind) {
            case MARKER -> {
                if (seconds > 0) {
                    loot.setMarkerSeconds(seconds);
                } else {
                    loot.cycleMarkerSeconds();
                }
                now = loot.markerSeconds();
            }
            case GLOW -> {
                if (seconds > 0) {
                    loot.setGlowSeconds(seconds);
                } else {
                    loot.cycleGlowSeconds();
                }
                now = loot.glowSeconds();
            }
            default -> {
                if (seconds > 0) {
                    flow.setJamSeconds(seconds);
                } else {
                    flow.cycleJamSeconds();
                }
                now = flow.jamSeconds();
            }
        }
        SettingsIO.snapshot(state, ranges, proj, flow, TrajectoryLensClient.threats(), loot).save();
        src.sendFeedback(Component.literal(changed(label, Lang.tr("trajectorylens.common.seconds", now))));
        return 1;
    }

    /** /trajectorylens chaindepth [层] / chainhorizon [秒]. */
    private static int chainOption(FabricClientCommandSource src, OverlayState state, WalkRangeOverlay ranges,
                                   ProjectileOverlay proj, FlowTracker flow, int value, boolean depth) {
        if (depth) {
            if (value > 0) {
                proj.setChainDepth(value);
            } else {
                proj.cycleChainDepth();
            }
        } else {
            if (value > 0) {
                proj.setChainHorizonSeconds(value);
            } else {
                proj.cycleChainHorizon();
            }
        }
        SettingsIO.snapshot(state, ranges, proj, flow, TrajectoryLensClient.threats(), TrajectoryLensClient.loot()).save();
        src.sendFeedback(Component.literal(depth
            ? changed("trajectorylens.feature.chain_depth", Lang.tr("trajectorylens.common.layers", proj.chainDepth()))
            : changed("trajectorylens.feature.chain_horizon", Lang.tr("trajectorylens.common.seconds", proj.chainHorizonSeconds()))));
        return 1;
    }

    private static int chainSwitch(FabricClientCommandSource src, ProjectileOverlay proj, FlowTracker flow,
                                   OverlayState state, WalkRangeOverlay ranges, Boolean on) {
        boolean value = on != null ? on : !proj.chainEnabled();
        proj.setChain(value);
        SettingsIO.snapshot(state, ranges, proj, flow, TrajectoryLensClient.threats(), TrajectoryLensClient.loot()).save();
        src.sendFeedback(Component.literal(changed("trajectorylens.feature.chain", Lang.onOff(value))));
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> flowSwitch(
        String op, OverlayState state, WalkRangeOverlay ranges, ProjectileOverlay proj, FlowTracker flow) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal(op)
            .executes(ctx -> {
                boolean on = switch (op) {
                    case "on" -> true;
                    case "off" -> false;
                    default -> !flow.countersEnabled();
                };
                flow.setCounters(on);
                SettingsIO.snapshot(state, ranges, proj, flow, TrajectoryLensClient.threats(), TrajectoryLensClient.loot()).save();
                ctx.getSource().sendFeedback(Component.literal(changed("trajectorylens.feature.counters", Lang.onOff(on))));
                return 1;
            });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> counterTree(
        OverlayState state, WalkRangeOverlay ranges, ProjectileOverlay proj, FlowTracker flow) {
        var tree = LiteralArgumentBuilder.<FabricClientCommandSource>literal("counter");
        tree.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("add")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                .executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    String err = flow.addCounter(name);
                    if (err != null) {
                        ctx.getSource().sendError(Component.literal("[TrajectoryLens] " + err));
                        return 0;
                    }
                    SettingsIO.snapshot(state, ranges, proj, flow, TrajectoryLensClient.threats(), TrajectoryLensClient.loot()).save();
                    ctx.getSource().sendFeedback(Component.literal(Lang.tr("trajectorylens.command.counter_added", name)));
                    return 1;
                })));
        tree.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("remove")
            .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                .executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    boolean ok = flow.removeCounter(name);
                    SettingsIO.snapshot(state, ranges, proj, flow, TrajectoryLensClient.threats(), TrajectoryLensClient.loot()).save();
                    ctx.getSource().sendFeedback(Component.literal(Lang.tr(ok
                        ? "trajectorylens.command.counter_removed" : "trajectorylens.command.counter_missing", name)));
                    return ok ? 1 : 0;
                })));
        tree.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear").executes(ctx -> {
            flow.clearCounters();
            SettingsIO.snapshot(state, ranges, proj, flow, TrajectoryLensClient.threats(), TrajectoryLensClient.loot()).save();
            ctx.getSource().sendFeedback(Component.literal(Lang.tr("trajectorylens.command.counters_cleared")));
            return 1;
        }));
        tree.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list").executes(ctx -> {
            ctx.getSource().sendFeedback(Component.literal("[TrajectoryLens] " + flow.summary()));
            return 1;
        }));
        return tree;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> switchTree(
        String name, ProjectileOverlay proj, boolean isProjectiles, OverlayState state, WalkRangeOverlay ranges,
        ProjectileOverlay sameProj, FlowTracker flow) {
        var tree = LiteralArgumentBuilder.<FabricClientCommandSource>literal(name);
        for (String op : new String[]{"on", "off", "toggle"}) {
            tree.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal(op)
                .executes(ctx -> {
                    boolean on = switch (op) {
                        case "on" -> true;
                        case "off" -> false;
                        default -> isProjectiles ? !proj.projectilesEnabled() : !proj.tntEnabled();
                    };
                    if (isProjectiles) {
                        proj.setProjectiles(on);
                    } else {
                        proj.setTnt(on);
                    }
                    SettingsIO.snapshot(state, ranges, proj, flow, TrajectoryLensClient.threats(), TrajectoryLensClient.loot()).save();
                    ctx.getSource().sendFeedback(Component.literal(changed(
                        isProjectiles ? "trajectorylens.feature.projectiles" : "trajectorylens.feature.tnt", Lang.onOff(on))));
                    return 1;
                }));
        }
        return tree;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> colorsTree(OverlayState state, WalkRangeOverlay ranges) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("colors")
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Component.literal("[TrajectoryLens] " + state.listing()));
                ctx.getSource().sendFeedback(Component.literal("[TrajectoryLens] " + ranges.summary()));
                return 1;
            });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> toggleTree(OverlayState state) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle")
            .executes(ctx -> {
                state.toggle();
                ctx.getSource().sendFeedback(Component.literal(changed("trajectorylens.feature.drops", Lang.onOff(state.visible()))));
                return 1;
            });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> statusTree(OverlayState state, WalkRangeOverlay ranges) {
        return LiteralArgumentBuilder.<FabricClientCommandSource>literal("status")
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Component.literal("[TrajectoryLens] " + state.listing()));
                ctx.getSource().sendFeedback(Component.literal("[TrajectoryLens] " + ranges.summary()));
                return 1;
            });
    }

    private static int setColorLocal(FabricClientCommandSource src, OverlayState state, String itemRaw, String colorRaw) {
        if (src.getLevel() == null) {
            src.sendError(Component.literal("[TrajectoryLens] " + Lang.tr("trajectorylens.error.no_world")));
            return 0;
        }
        String id = itemRaw.trim();
        String ns;
        String path;
        int colon = id.indexOf(':');
        if (colon < 0) {
            ns = "minecraft";
            path = id;
        } else {
            ns = id.substring(0, colon);
            path = id.substring(colon + 1);
        }
        Identifier parsed;
        try {
            parsed = Identifier.fromNamespaceAndPath(ns, path);
        } catch (Exception ex) {
            src.sendError(Component.literal("[TrajectoryLens] " + Lang.tr("trajectorylens.error.invalid_item", itemRaw)));
            return 0;
        }
        Registry<Item> items = src.getLevel().registryAccess().lookup(Registries.ITEM).orElse(null);
        if (items == null || !items.containsKey(parsed)) {
            src.sendError(Component.literal("[TrajectoryLens] " + Lang.tr("trajectorylens.error.unknown_item", itemRaw)));
            return 0;
        }
        String err = state.setItemColor(parsed.toString(), colorRaw.trim());
        if (err != null) {
            src.sendError(Component.literal("[TrajectoryLens] " + err));
            return 0;
        }
        src.sendFeedback(Component.literal(Lang.tr("trajectorylens.command.item_color", parsed, colorLabel(colorRaw))));
        return 1;
    }

    /** Vanilla-style item id Tab completion for the client-side command tree. */
    private static CompletableFuture<Suggestions> suggestItems(
        CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getLevel() == null) {
            return builder.buildFuture();
        }
        Registry<Item> items = ctx.getSource().getLevel().registryAccess().lookup(Registries.ITEM).orElse(null);
        if (items == null) {
            return builder.buildFuture();
        }
        String typed = builder.getRemainingLowerCase();
        Set<String> seen = new HashSet<>();
        for (Identifier key : items.keySet()) {
            if ("minecraft".equals(key.getNamespace())) {
                addSuggestion(builder, seen, key.getPath(), typed);
            }
            addSuggestion(builder, seen, key.toString(), typed);
        }
        return builder.buildFuture();
    }

    /** Entity-type completion ("zombie", "minecraft:villager", ...). */
    private static CompletableFuture<Suggestions> suggestEntityTypes(
        CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        if (ctx.getSource().getLevel() == null) {
            return builder.buildFuture();
        }
        Registry<EntityType<?>> reg =
            ctx.getSource().getLevel().registryAccess().lookup(Registries.ENTITY_TYPE).orElse(null);
        if (reg == null) {
            return builder.buildFuture();
        }
        String typed = builder.getRemainingLowerCase();
        Set<String> seen = new HashSet<>();
        for (Identifier key : reg.keySet()) {
            if ("minecraft".equals(key.getNamespace())) {
                addSuggestion(builder, seen, key.getPath(), typed);
            }
            addSuggestion(builder, seen, key.toString(), typed);
        }
        return builder.buildFuture();
    }

    private static void addSuggestion(SuggestionsBuilder builder, Set<String> seen, String candidate, String typed) {
        if (seen.add(candidate) && candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
            builder.suggest(candidate);
        }
    }
}