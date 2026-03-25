package io.github.mebsic.core.service;

import org.bukkit.Location;

public interface HubContext {
    CoreApi getCoreApi();

    Location getHubSpawn();

    void handleHubJoin(org.bukkit.entity.Player player);

    void handleHubQuit(org.bukkit.entity.Player player);
}
