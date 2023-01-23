/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.utils.world.BlockUtils;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IMineProcess;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.notebot.NotebotUtils;
import meteordevelopment.meteorclient.utils.notebot.song.Note;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.DisconnectS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.function.Predicate;

public class InfinityMiner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhenFull = settings.createGroup("When Full");

    // General

    public final Setting<List<Block>> targetBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("target-blocks")
        .description("The target blocks to mine.")
        .defaultValue(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)
        .filter(this::filterBlocks)
        .build()
    );

    public final Setting<List<Item>> targetItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("target-items")
        .description("The target items to collect.")
        .defaultValue(Items.DIAMOND)
        .build()
    );

    public final Setting<List<Block>> repairBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("repair-blocks")
        .description("The repair blocks to mine.")
        .defaultValue(Blocks.COAL_ORE, Blocks.REDSTONE_ORE, Blocks.NETHER_QUARTZ_ORE)
        .filter(this::filterBlocks)
        .build()
    );

    public final Setting<Double> startRepairing = sgGeneral.add(new DoubleSetting.Builder()
        .name("repair-threshold")
        .description("The durability percentage at which to start repairing.")
        .defaultValue(20)
        .range(1, 99)
        .sliderRange(1, 99)
        .build()
    );

    public final Setting<Double> startMining = sgGeneral.add(new DoubleSetting.Builder()
        .name("mine-threshold")
        .description("The durability percentage at which to start mining.")
        .defaultValue(70)
        .range(1, 99)
        .sliderRange(1, 99)
        .build()
    );

    // When Full

    public final Setting<Boolean> depositResources = sgWhenFull.add(new BoolSetting.Builder()
        .name("deposit_resources")
        .description("Will walk to 'resources' when your inventory is full.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> logOut = sgWhenFull.add(new BoolSetting.Builder()
        .name("log-out")
        .description("Logs out when your inventory is full. Will walk home FIRST if walk home is enabled.")
        .defaultValue(false)
        .build()
    );

    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    private final Settings baritoneSettings = BaritoneAPI.getSettings();

    private final BlockPos.Mutable resourcePos = new BlockPos.Mutable();

    private boolean prevMineScanDroppedItems;
    private boolean repairing;

    public InfinityMiner() {
        super(Categories.World, "infinity-miner", "Allows you to essentially mine forever by mining repair blocks when the durability gets low. Needs a mending pickaxe.");
    }

    @Override
    public void onActivate() {
        prevMineScanDroppedItems = baritoneSettings.mineScanDroppedItems.value;
        baritoneSettings.mineScanDroppedItems.value = true;
        resourcePos.set(mc.player.getBlockPos());
        repairing = false;
    }

    @Override
    public void onDeactivate() {
        baritone.getPathingBehavior().cancelEverything();
        baritoneSettings.mineScanDroppedItems.value = prevMineScanDroppedItems;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
    	if(!shouldEat()) {
	        if (isFull()) {
	            if (depositResources.get()) {
	                if (isBaritoneNotWalking()) {
	                    info("Depositing resources.");
	                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(resourcePos));
	                }
	                else if (mc.player.getBlockPos().equals(resourcePos)) {
	                	//Deposit resources
	                	scanForChests();
	                }
	            }
	            
	
	            return;
	        }
	
	        if (!findPickaxe()) {
	            error("Could not find a usable mending pickaxe.");
	            toggle();
	            return;
	        }
	
	        if (!checkThresholds()) {
	            error("Start mining value can't be lower than start repairing value.");
	            toggle();
	            return;
	        }
	
	        if (repairing) {
	            if (!needsRepair()) {
	                warning("Finished repairing, going back to mining.");
	                repairing = false;
	                mineTargetBlocks();
	                return;
	            }
	
	            if (isBaritoneNotMining()) mineRepairBlocks();
	        }
	        else {
	            if (needsRepair()) {
	                warning("Pickaxe needs repair, beginning repair process");
	                repairing = true;
	                mineRepairBlocks();
	                return;
	            }
	
	            if (isBaritoneNotMining()) mineTargetBlocks();
	        }
	    }
    }
    
	private boolean shouldEat() {
        return mc.player.getHungerManager().getFoodLevel() <= 16;
    }
	

    private void scanForChests() {
        if (mc.interactionManager == null || mc.world == null || mc.player == null) return;
        int min = (int) (-mc.interactionManager.getReachDistance()) - 3;
        int max = (int) mc.interactionManager.getReachDistance() + 3;

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
    
    private boolean needsRepair() {
        ItemStack itemStack = mc.player.getMainHandStack();
        double toolPercentage = ((itemStack.getMaxDamage() - itemStack.getDamage()) * 100f) / (float) itemStack.getMaxDamage();
        return !(toolPercentage > startMining.get() || (toolPercentage > startRepairing.get() && !repairing));
    }

    private boolean findPickaxe() {
    	
        Predicate<ItemStack> pickaxePredicate = (stack -> stack.getItem() instanceof PickaxeItem
            && Utils.hasEnchantments(stack, Enchantments.MENDING) &&
           (((stack.getMaxDamage() - stack.getDamage()) * 100f) / (float) stack.getMaxDamage()) > startRepairing.get());
        FindItemResult bestPick = InvUtils.findInHotbar(pickaxePredicate);

        if (bestPick.isOffhand()) InvUtils.quickMove().fromOffhand().toHotbar(mc.player.getInventory().selectedSlot);
        else if (bestPick.isHotbar()) InvUtils.swap(bestPick.slot(), false);

        return InvUtils.testInMainHand(pickaxePredicate);
    }

    private boolean checkThresholds() {
        return startRepairing.get() < startMining.get();
    }

    private void mineTargetBlocks() {
        Block[] array = new Block[targetBlocks.get().size()];

        baritone.getPathingBehavior().cancelEverything();
        baritone.getMineProcess().mine(targetBlocks.get().toArray(array));
    }

    private void mineRepairBlocks() {
        Block[] array = new Block[repairBlocks.get().size()];

        baritone.getPathingBehavior().cancelEverything();
        baritone.getMineProcess().mine(repairBlocks.get().toArray(array));
    }

    private void logOut() {
        toggle();
        mc.player.networkHandler.sendPacket(new DisconnectS2CPacket(Text.literal("[Infinity Miner] Inventory is full.")));
    }

    private boolean isBaritoneNotMining() {
        return !(baritone.getPathingControlManager().mostRecentInControl().orElse(null) instanceof IMineProcess);
    }

    private boolean isBaritoneNotWalking() {
        return !(baritone.getPathingControlManager().mostRecentInControl().orElse(null) instanceof ICustomGoalProcess);
    }

    private boolean filterBlocks(Block block) {
        return block != Blocks.AIR && block.getDefaultState().getHardness(mc.world, null) != -1 && !(block instanceof FluidBlock);
    }

    private boolean isFull() {
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
}
