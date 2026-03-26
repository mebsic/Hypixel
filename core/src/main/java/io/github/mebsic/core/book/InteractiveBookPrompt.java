package io.github.mebsic.core.book;

import io.github.mebsic.core.CorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public abstract class InteractiveBookPrompt {
    private final UUID viewerUuid;

    protected InteractiveBookPrompt(UUID viewerUuid) {
        this.viewerUuid = viewerUuid;
    }

    public final UUID getViewerUuid() {
        return viewerUuid;
    }

    public abstract ItemStack buildBook(CorePlugin plugin, String token);

    public abstract void onYes(CorePlugin plugin, Player viewer);

    public abstract void onNo(CorePlugin plugin, Player viewer);

    public void onCancel(CorePlugin plugin, UUID viewerUuid) {
        // Optional override.
    }
}
