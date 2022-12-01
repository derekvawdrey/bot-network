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
import meteordevelopment.meteorclient.utils.notebot.decoder.*;
import meteordevelopment.meteorclient.utils.notebot.NotebotUtils;
import meteordevelopment.meteorclient.utils.notebot.song.Note;
import meteordevelopment.meteorclient.utils.notebot.song.Song;
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


public class ResourceBot extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<List<Block>> targetBlocks = sgGeneral.add(new BlockListSetting.Builder()
            .name("whitelist")
            .description("Which blocks to show x-rayed.")
            .defaultValue(
                Blocks.COAL_ORE,
                Blocks.DEEPSLATE_COAL_ORE,
                Blocks.IRON_ORE,
                Blocks.DEEPSLATE_IRON_ORE,
                Blocks.GOLD_ORE,
                Blocks.DEEPSLATE_GOLD_ORE,
                Blocks.LAPIS_ORE,
                Blocks.DEEPSLATE_LAPIS_ORE,
                Blocks.REDSTONE_ORE,
                Blocks.DEEPSLATE_REDSTONE_ORE,
                Blocks.DIAMOND_ORE,
                Blocks.DEEPSLATE_DIAMOND_ORE,
                Blocks.EMERALD_ORE,
                Blocks.DEEPSLATE_EMERALD_ORE,
                Blocks.COPPER_ORE,
                Blocks.DEEPSLATE_COPPER_ORE,
                Blocks.NETHER_GOLD_ORE,
                Blocks.NETHER_QUARTZ_ORE,
                Blocks.ANCIENT_DEBRIS
            )
            .build()
        );
    public final Setting<List<Item>> targetItems = sgGeneral.add(new ItemListSetting.Builder()
            .name("target-items")
            .description("The target items to collect.")
            .defaultValue(Items.DIAMOND)
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
    private final Setting<Integer> miningX = sgGeneral.add(new IntSetting.Builder()
            .name("mining-x")
            .description("X coordinate for mining location")
            .defaultValue(0)
            .build()
        );
    private final Setting<Integer> miningY = sgGeneral.add(new IntSetting.Builder()
            .name("mining-y")
            .description("Y coordinate for mining location")
            .defaultValue(0)
            .build()
        );
    private final Setting<Integer> miningZ = sgGeneral.add(new IntSetting.Builder()
            .name("mining-z")
            .description("Z coordinate for mining location")
            .defaultValue(0)
            .build()
        );
    
    private final BlockPos.Mutable resourcePos = new BlockPos.Mutable();
    private final BlockPos.Mutable miningPos = new BlockPos.Mutable();
    private boolean isMining = false;
    private boolean isEating = false;
    private boolean isReturningResources = false;
    private boolean isReturningToMiningPosition = false;
    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    private final Settings baritoneSettings = BaritoneAPI.getSettings();


    public ResourceBot() {
        super(Categories.World, "resource-bot", "Mines selected resources automatically");
    }


    @Override
    public void onActivate() {
        resetVariables();
    }

    @Override
    public void onDeactivate() {
        baritone.getPathingBehavior().cancelEverything();
    }

    private void resetVariables() {
        isMining = false;
        isEating = false;
        isReturningResources = false;
        isReturningToMiningPosition = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
    	//First we check if inventory is full, if it is return to resource position and deposit resources.
        if(isInventoryFull()) returnResources();
        
        //If the bot is not returning resources mine
        else if(!isReturningResources && !isReturningToMiningPosition) {
        	mine();
        }
    }
    
    private boolean isInventoryFull() {
        for (int i = 0; i <= 35; i++) {
            ItemStack itemStack = mc.player.getInventory().getStack(i);

            for (Item item : targetItems.get()) {
                if ((itemStack.getItem() == item && itemStack.getCount() < itemStack.getMaxCount())
                    || itemStack.isEmpty()) {
                    return false;
                }
            }
        }

        return true;
    }
    
    private void returnResources() {
    	if(!isReturningResources) {
	    	resourcePos.set(resourceX.get(), resourceY.get(), resourceZ.get());
	    	info("Depositing resources.");
	        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(resourcePos));
	        isReturningResources = true;
    	}else {
    		//Check if the baritone has finished the goal pathing. Then return to mining area
    		if(mc.player.getBlockPos().equals(resourcePos)) {
    			isReturningResources = false;
    			returnToMiningArea();
    		}
    	}
    }
    private void returnToMiningArea() {
    	if(!isReturningToMiningPosition) {
	    	miningPos.set(miningX.get(), miningY.get(), miningZ.get());
	    	info("Returning to mining position");
	        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(miningPos));
	        isReturningToMiningPosition = true;
	        isReturningResources = false;
    	}else {
    		//Check if the baritone has finished the goal pathing. and then start mining
    		if(mc.player.getBlockPos().equals(miningPos)) {
    			isReturningToMiningPosition = false;
    			mine();
    		}
    	}
    }
    private void mine() {
    	if(!isMining && !isEating) {
    		Block[] array = new Block[targetBlocks.get().size()];
            baritone.getPathingBehavior().cancelEverything();
            info("Returning to Mining.");
            baritone.getMineProcess().mine(targetBlocks.get().toArray(array));
            isMining = true;
    	}else {
    		eat();
    	}
    }
    private void eat() {
    	//Check if needs to eat, then eats
//    	isMining = false;
//    	isEating = true;
    }

    /**
     * Scans for chests nearby and adds them to the map
     */
    private void scanForChests() {
        if (mc.interactionManager == null || mc.world == null || mc.player == null) return;
        int min = (int) (-mc.interactionManager.getReachDistance()) - 2;
        int max = (int) mc.interactionManager.getReachDistance() + 2;

        // Scan for noteblocks horizontally
        // 6^3 kek
        for (int y = min; y < max; y++) {
            for (int x = min; x < max; x++) {
                for (int z = min; z < max; z++) {
                    BlockPos pos = mc.player.getBlockPos().add(x, y + 1, z);

                    BlockState blockState = mc.world.getBlockState(pos);
                    if (blockState.getBlock() != Blocks.CHEST) continue;

                    // Copied from ServerPlayNetworkHandler#onPlayerInteractBlock
                    Vec3d vec3d2 = Vec3d.ofCenter(pos);
                    double sqDist = mc.player.getEyePos().squaredDistanceTo(vec3d2);
                    if (sqDist > ServerPlayNetworkHandler.MAX_BREAK_SQUARED_DISTANCE) continue;

                    if (!isValidScanSpot(pos)) continue;
                    
                    //Get items from chest 
                    //do a scan for chests and put them in a scannedChest array
                }
            }

        }
    }
    
    private boolean isValidScanSpot(BlockPos pos) {
        if (mc.world.getBlockState(pos).getBlock() != Blocks.CHEST) return false;
        return mc.world.getBlockState(pos.up()).isAir();
    }
}

