package io.github.mebsic.build;

import io.github.mebsic.build.command.EditCommand;
import io.github.mebsic.build.listener.BuildHubFeatureListener;
import io.github.mebsic.build.listener.BuildPlayerListener;
import io.github.mebsic.build.service.BuildAccessService;
import io.github.mebsic.build.service.BuildLobbyRedirectService;
import io.github.mebsic.build.service.BuildMapConfigService;
import io.github.mebsic.core.CorePlugin;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class BuildPlugin extends JavaPlugin {
    private CorePlugin corePlugin;
    private io.github.mebsic.game.service.ServerRegistryService registryService;
    private BuildAccessService accessService;
    private BuildMapConfigService mapConfigService;
    private BuildLobbyRedirectService lobbyRedirectService;

    @Override
    public void onEnable() {
        Plugin plugin = getServer().getPluginManager().getPlugin("Hycopy");
        if (!(plugin instanceof CorePlugin)) {
            getLogger().severe("Hycopy plugin not found. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.corePlugin = (CorePlugin) plugin;
        this.corePlugin.ensureServerIdentity();

        this.registryService = new io.github.mebsic.game.service.ServerRegistryService(this, corePlugin.getConfig(), null);
        this.registryService.start();

        int registryStaleSeconds = Math.max(0, corePlugin.getConfig().getInt("registry.staleSeconds", 20));
        this.accessService = new BuildAccessService(this, corePlugin);
        this.mapConfigService = new BuildMapConfigService(corePlugin);
        this.lobbyRedirectService = new BuildLobbyRedirectService(this, corePlugin, accessService, registryStaleSeconds);
        this.lobbyRedirectService.subscribeToRankSync();

        registerCommands();
        getServer().getPluginManager().registerEvents(
                new BuildPlayerListener(this, accessService, lobbyRedirectService, mapConfigService),
                this
        );
        getServer().getPluginManager().registerEvents(new BuildHubFeatureListener(mapConfigService), this);

        for (Player player : getServer().getOnlinePlayers()) {
            lobbyRedirectService.validateStaffAccess(player, 0);
        }
    }

    @Override
    public void onDisable() {
        if (mapConfigService != null) {
            mapConfigService.shutdown();
            mapConfigService = null;
        }
        if (lobbyRedirectService != null) {
            lobbyRedirectService.shutdown();
            lobbyRedirectService = null;
        }
        if (registryService != null) {
            registryService.stop();
            registryService = null;
        }
    }

    private void registerCommands() {
        registerCommand("edit", new EditCommand(accessService, mapConfigService));
    }

    private void registerCommand(String name, CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null || executor == null) {
            return;
        }
        command.setExecutor(executor);
        if (executor instanceof TabCompleter) {
            command.setTabCompleter((TabCompleter) executor);
        }
    }
}
