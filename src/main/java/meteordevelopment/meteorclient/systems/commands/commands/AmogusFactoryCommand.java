/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.commands.commands;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.block.Block;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import meteordevelopment.meteorclient.systems.commands.Command;
import meteordevelopment.meteorclient.systems.commands.Commands;
import meteordevelopment.meteorclient.systems.commands.arguments.ModuleArgumentType;
import meteordevelopment.meteorclient.systems.commands.arguments.PlayerArgumentType;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.amogus_factory.Amogus;
import meteordevelopment.meteorclient.systems.modules.misc.amogus_factory.AmogusConnection;
import meteordevelopment.meteorclient.systems.modules.misc.amogus_factory.AmogusWorker;
import meteordevelopment.meteorclient.systems.modules.world.InfinityMiner;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Random;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class AmogusFactoryCommand extends Command {
	
	private final static SimpleCommandExceptionType AMOGUS_NOT_ACTIVE = new SimpleCommandExceptionType(Text.literal("The Amogus Factory module must be active to use this command."));

	
    public AmogusFactoryCommand() {
        super("amogus-factory", "Executes the Amogus Factory generator", "af");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {

    	builder.then(literal("gather").executes(context -> {
            Amogus amogus = Modules.get().get(Amogus.class);
            if (amogus.isActive()) {
                if (amogus.isHost()) {
                	amogus.host.sendMessage(context.getInput());
                	runInfinityMiner();
                }
                else if (amogus.isWorker()) {
                    runInfinityMiner();
                }
            }
            else {
                throw AMOGUS_NOT_ACTIVE.create();
            }
            return SINGLE_SUCCESS;
        })
    );
    }

    private void runInfinityMiner() {
        InfinityMiner infinityMiner = Modules.get().get(InfinityMiner.class);
        if (infinityMiner.isActive()) infinityMiner.toggle();
//        infinityMiner.smartModuleToggle.set(true);
        if (!infinityMiner.isActive()) infinityMiner.toggle();
    }

    private void scatter(int radius) {
        Random random = new Random();
        double a = random.nextDouble() * 2 * Math.PI;
        double r = radius * Math.sqrt(random.nextDouble());
        double x = mc.player.getX() + r * Math.cos(a);
        double z = mc.player.getZ() + r * Math.sin(a);
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ((int) x, (int) z));
    }
}
