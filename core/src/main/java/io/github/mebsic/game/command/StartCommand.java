package io.github.mebsic.game.command;

import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.game.manager.GameManager;
import io.github.mebsic.game.model.GameState;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class StartCommand implements CommandExecutor {
    private final GameManager gameManager;
    private final CoreApi coreApi;

    public StartCommand(GameManager gameManager, CoreApi coreApi) {
        this.gameManager = gameManager;
        this.coreApi = coreApi;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = GameCommandUtil.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (!GameCommandUtil.requireRank(sender, coreApi, Rank.STAFF)) {
            return true;
        }
        GameState state = gameManager.getState();
        if (state == GameState.STARTING) {
            player.sendMessage(ChatColor.RED + "The game is already starting!");
            return true;
        }
        if (state == GameState.IN_GAME || state == GameState.ENDING) {
            player.sendMessage(ChatColor.RED + "The game has already started!");
            return true;
        }
        if (!gameManager.canForceStart()) {
            player.sendMessage(gameManager.getNotEnoughPlayersMessage());
            return true;
        }
        gameManager.forceStart();
        player.sendMessage(ChatColor.GREEN + "Done!");
        return true;
    }
}
