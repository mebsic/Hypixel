package io.github.mebsic.proxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.proxy.service.RankResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class CancelRestartCommand implements SimpleCommand {
    private final RestartCommand restartCommand;
    private final RankResolver rankResolver;

    public CancelRestartCommand(RestartCommand restartCommand, RankResolver rankResolver) {
        this.restartCommand = restartCommand;
        this.rankResolver = rankResolver;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }

        Player player = (Player) invocation.source();
        if (rankResolver == null || !rankResolver.isStaff(player.getUniqueId())) {
            player.sendMessage(Component.text(CommonMessages.NO_PERMISSION_COMMAND, NamedTextColor.RED));
            return;
        }

        if (restartCommand == null || !restartCommand.cancelRestart()) {
            player.sendMessage(Component.text("A restart has not started yet!", NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text("Done!", NamedTextColor.GREEN));
    }
}
