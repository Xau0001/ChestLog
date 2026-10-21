package onl.xau.chestlog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import onl.xau.chestlog.admin.AdminStore;
import onl.xau.chestlog.admin.AdminStore.Admin;
import onl.xau.chestlog.command.ChestLogCommand;
import onl.xau.chestlog.container.ContainerResolver;
import onl.xau.chestlog.gui.LogGui;
import onl.xau.chestlog.listener.ContainerListener;
import onl.xau.chestlog.listener.InspectorListener;
import onl.xau.chestlog.listener.PlayerListener;
import onl.xau.chestlog.storage.LogDatabase;
import onl.xau.chestlog.storage.LogWriter;

public final class ChestLogPlugin extends JavaPlugin {

    private static final long TICKS_PER_SECOND = 20L;
    private static final long TICKS_PER_DAY = TICKS_PER_SECOND * 60 * 60 * 24;
    private static final int BATCH_SIZE = 100;

    private volatile PluginConfig settings;
    private ExecutorService fileExecutor;
    private AdminStore admins;
    private LogWriter writer;
    private LogService logService;
    private ContainerListener containerListener;
    private LogGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Path dataDir = getDataFolder().toPath();
        settings = PluginConfig.from(readConfigAtStartup(dataDir.resolve("config.yml")), getLogger());

        fileExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ChestLog-Datei");
            t.setDaemon(true);
            return t;
        });
        admins = new AdminStore(dataDir.resolve("admins.yml"), fileExecutor);
        loadAdminsAtStartup();

        try {
            writer = new LogWriter(LogDatabase.open(dataDir.resolve("chestlog.db")), getLogger(), BATCH_SIZE,
                    Duration.ofSeconds(1));
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Datenbank konnte nicht geöffnet werden, ChestLog wird deaktiviert", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logService = new LogService(writer, this::settings, getLogger());

        ContainerResolver resolver = new ContainerResolver(this::settings);
        gui = new LogGui(this, logService, admins);
        containerListener = new ContainerListener(this, logService, resolver);

        PluginManager plugins = getServer().getPluginManager();
        plugins.registerEvents(containerListener, this);
        plugins.registerEvents(new InspectorListener(this::settings, admins, resolver, gui), this);
        plugins.registerEvents(gui, this);
        plugins.registerEvents(new PlayerListener(admins, getLogger()), this);

        ChestLogCommand command = new ChestLogCommand(this, admins);
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand("chestlog"), "chestlog in plugin.yml");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        getServer().getScheduler().runTaskTimer(this, logService::flushExpired, TICKS_PER_SECOND, TICKS_PER_SECOND);
        // beim Start und danach einmal täglich
        getServer().getScheduler().runTaskTimer(this, this::purgeOldEntries, 1L, TICKS_PER_DAY);

        if (admins.size() == 0) {
            getLogger().info("Noch niemand berechtigt. Ersten Admin über die Konsole eintragen: chestlog add <Name>");
        }
    }

    @Override
    public void onDisable() {
        if (gui != null) {
            gui.closeAll();
        }
        if (containerListener != null) {
            containerListener.completeAll();
        }
        if (logService != null) {
            logService.flushAll();
        }
        if (writer != null) {
            writer.close();
        }
        if (fileExecutor != null) {
            fileExecutor.shutdown();
            try {
                if (!fileExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    getLogger().severe("admins.yml wurde nicht rechtzeitig gespeichert.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public PluginConfig settings() {
        return settings;
    }

    /** Schließt ein offenes Log-Fenster des Spielers, z. B. nachdem er aus der Liste entfernt wurde. */
    public void closeLogWindow(UUID player) {
        Player online = getServer().getPlayer(player);
        if (online != null && gui != null) {
            gui.close(online);
        }
    }

    /** Führt die Aufgabe im nächsten Tick auf dem Main-Thread aus, sofern das Plugin noch läuft. */
    public void runSync(Runnable task) {
        if (!isEnabled()) {
            return;
        }
        try {
            getServer().getScheduler().runTask(this, task);
        } catch (IllegalPluginAccessException e) {
            // Plugin wird gerade deaktiviert
        }
    }

    /**
     * Liest {@code config.yml} und {@code admins.yml} asynchron neu und übernimmt sie auf dem Main-Thread. Bei einem
     * Fehler bleibt der bisherige Stand erhalten.
     */
    public CompletableFuture<Void> reloadAsync() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        reload(result, 0);
        return result;
    }

    private void reload(CompletableFuture<Void> result, int attempt) {
        Path configFile = getDataFolder().toPath().resolve("config.yml");
        int adminVersion = admins.version();
        try {
            fileExecutor.execute(() -> {
                YamlConfiguration yaml = new YamlConfiguration();
                Map<UUID, Admin> loaded;
                try {
                    if (Files.exists(configFile)) {
                        yaml.load(configFile.toFile());
                    }
                } catch (IOException | InvalidConfigurationException e) {
                    result.completeExceptionally(new IllegalStateException("config.yml: " + e.getMessage(), e));
                    return;
                }
                try {
                    loaded = AdminStore.read(admins.file());
                } catch (IOException | IllegalArgumentException e) {
                    result.completeExceptionally(new IllegalStateException("admins.yml: " + e.getMessage(), e));
                    return;
                }
                runSync(() -> {
                    // Wurde die Liste zwischen Lesen und Übernehmen geändert (add, remove, Namens-Update beim Join),
                    // wäre das Gelesene veraltet. Dann nochmal lesen; das Speichern der Änderung läuft vorher.
                    if (admins.version() != adminVersion) {
                        if (attempt < 3) {
                            reload(result, attempt + 1);
                        } else {
                            result.completeExceptionally(new IllegalStateException(
                                    "admins.yml wird gerade geändert, bitte gleich nochmal versuchen"));
                        }
                        return;
                    }
                    PluginConfig config = PluginConfig.from(yaml, getLogger());
                    settings = config;
                    logService.applySettings(config);
                    admins.replaceAll(loaded);
                    for (Player player : getServer().getOnlinePlayers()) {
                        if (!admins.isAdmin(player.getUniqueId())) {
                            gui.close(player);
                        }
                    }
                    result.complete(null);
                });
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
    }

    private YamlConfiguration readConfigAtStartup(Path file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().log(Level.WARNING, "config.yml konnte nicht gelesen werden, verwende Standardwerte", e);
        }
        return yaml;
    }

    private void loadAdminsAtStartup() {
        Path file = admins.file();
        try {
            admins.replaceAll(AdminStore.read(file));
        } catch (IOException | IllegalArgumentException e) {
            Path broken = file.resolveSibling(file.getFileName() + ".broken");
            getLogger().log(Level.SEVERE, "admins.yml konnte nicht gelesen werden. Sie wird nach " + broken.getFileName()
                    + " verschoben, die Liste der Berechtigten ist leer.", e);
            try {
                Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                getLogger().log(Level.SEVERE, "admins.yml konnte nicht verschoben werden", moveError);
            }
        }
    }

    private void purgeOldEntries() {
        int days = settings.retentionDays();
        if (days <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        logService.deleteOlderThan(cutoff).whenComplete((deleted, error) -> {
            if (error != null) {
                getLogger().log(Level.SEVERE, "Alte Einträge konnten nicht gelöscht werden", error);
            } else if (deleted > 0) {
                getLogger().info(deleted + " Einträge älter als " + days + " Tage gelöscht.");
            }
        });
    }
}
