/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IMineProcess;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.*;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Vec3;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.notebot.decoder.*;
import meteordevelopment.meteorclient.utils.notebot.NotebotUtils;
import meteordevelopment.meteorclient.utils.notebot.song.Note;
import meteordevelopment.meteorclient.utils.notebot.song.Song;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.enums.Instrument;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.apache.commons.io.FilenameUtils;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


public class BuildingBot extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<List<Block>> targetBlocks = sgGeneral.add(new BlockListSetting.Builder()
            .name("whitelist")
            .description("Which blocks to pick up")
            .defaultValue(
                Blocks.COAL_ORE
            )
            .build()
        );
    private final Setting<String> schematicName = sgGeneral.add(new StringSetting.Builder()
            .name("schematic-name")
            .description("X coordinate for resource dropoff location")
            .defaultValue("1endstone_walls.schem")
            .build()
        );
    private final Setting<Integer> resourceX = sgGeneral.add(new IntSetting.Builder()
            .name("resource-x")
            .description("X coordinate for resource dropoff location")
            .defaultValue(0)
            .build()
        );
    private final Setting<Integer> resourceY = sgGeneral.add(new IntSetting.Builder()
            .name("resource-y")
            .description("Y coordinate for resource dropoff location")
            .defaultValue(0)
            .build()
        );
    private final Setting<Integer> resourceZ = sgGeneral.add(new IntSetting.Builder()
            .name("resource-z")
            .description("Z coordinate for resource dropoff location")
            .defaultValue(0)
            .build()
        );
    private final BlockPos.Mutable resourcePos = new BlockPos.Mutable();
    private final BlockPos.Mutable buildingPos = new BlockPos.Mutable();
    private boolean isGettingResources = false;
    private boolean isReturningToBuilding = false;
    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    private final Settings baritoneSettings = BaritoneAPI.getSettings();

    public BuildingBot() {
        super(Categories.World, "building-bot", "Build schematics and fetch resource");
        
    }


    @Override
    public void onActivate() {
    	buildingPos.set(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
        resetVariables();
    }

    @Override
    public void onDeactivate() {
        baritone.getPathingBehavior().cancelEverything();
    }

    private void resetVariables() {
        isGettingResources = false;
        isReturningToBuilding = false;
        baritone.getBuilderProcess().build(schematicName.get(), buildingPos);
    }

    private void scanForChests() {
        if (mc.interactionManager == null || mc.world == null || mc.player == null) return;
        int min = (int) (-mc.interactionManager.getReachDistance()) - 4;
        int max = (int) mc.interactionManager.getReachDistance() + 4;

        // Scan for chests horizontally
        // 6^3 kek
        for (int y = min; y < max; y++) {
            for (int x = min; x < max; x++) {
                for (int z = min; z < max; z++) {
                    BlockPos pos = mc.player.getBlockPos().add(x, y + 1, z);

                    BlockState blockState = mc.world.getBlockState(pos);
                    if (blockState.getBlock() != Blocks.CHEST) continue;
                    Vec3d vec3d2 = Vec3d.ofCenter(pos);
                    double sqDist = mc.player.getEyePos().squaredDistanceTo(vec3d2);
                    if (sqDist > ServerPlayNetworkHandler.MAX_BREAK_SQUARED_DISTANCE) continue;
                    
                    if (!isValidScanSpot(pos)) continue;
                    Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 100, () -> interactWithChest(pos));
                }
            }

        }
    }
    
    private void interactWithChest(BlockPos pos) {
    	mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(pos), Direction.DOWN, pos, false), 0));
    }
    
    private boolean isValidScanSpot(BlockPos pos) {
        if (mc.world.getBlockState(pos).getBlock() != Blocks.CHEST) return false;
        return mc.world.getBlockState(pos.up()).isAir();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
    	// If the bot has stopped building and is not getting resources
        if(baritone.getBuilderProcess().isPaused() && !isGettingResources) {
        	isGettingResources = true;
        	isReturningToBuilding = false;
        	// Set baritone direction
        	
        	resourcePos.set(resourceX.get(), resourceY.get(), resourceZ.get());
        	info("Going to Resource Chest");
        	baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(resourcePos));
        }
        // Is getting resources but the inventory is full, so we
        // have the bot move to the building position
        else if(isGettingResources && isInventoryFull()) {
        	isGettingResources = false;
        	isReturningToBuilding = true;
        	info("Returning to building");
        	baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(buildingPos));
        }
        else if(isGettingResources) {
        		if(mc.player.getBlockPos().equals(resourcePos)) {
        			scanForChests();
        			info("Getting Resources.");
        	        ScreenHandler handler = mc.player.currentScreenHandler;
        	        steal(handler);
        			if(isInventoryFull()) {
        				isGettingResources = false;
            			isReturningToBuilding = true;
        			}
        		}
        	}
        	
        	// If getting resources check if you are at the position
        	else if(isReturningToBuilding) {
        		if(mc.player.getBlockPos().equals(buildingPos)) {
        			//baritone.getBuilderProcess().resume();
        			baritone.getBuilderProcess().build(schematicName.get(), buildingPos);
        			info("Resuming Build");
        			isReturningToBuilding = false;
        			isGettingResources = false;
        		}
        	}
    }
  
    private int getRows(ScreenHandler handler) {
        return (handler instanceof GenericContainerScreenHandler ? ((GenericContainerScreenHandler) handler).getRows() : 3);
    }
    
    public void steal(ScreenHandler handler) {
        MeteorExecutor.execute(() -> moveSlots(handler, 0, getRows(handler) * 9));
    }
    
    private void moveSlots(ScreenHandler handler, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!handler.getSlot(i).hasStack()) continue;

            int sleep = 1;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            // Exit if user closes screen
            if (mc.currentScreen == null) break;

            InvUtils.quickMove().slotId(i);
        }
    }
    
    
    private boolean isBaritoneNotWalking() {
        return !(baritone.getPathingControlManager().mostRecentInControl().orElse(null) instanceof ICustomGoalProcess);
    }
    
    private boolean isInventoryFull() {
        for (int i = 0; i <= 35; i++) {
            ItemStack itemStack = mc.player.getInventory().getStack(i);
            if (itemStack.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}

