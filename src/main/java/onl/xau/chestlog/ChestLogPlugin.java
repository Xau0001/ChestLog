package onl.xau.chestlog;

import org.bukkit.plugin.java.JavaPlugin;

public final class ChestLogPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("ChestLog aktiviert.");
    }

    @Override
    public void onDisable() {
        getLogger().info("ChestLog deaktiviert.");
    }
}
