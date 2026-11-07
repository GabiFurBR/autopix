package io.github.warleysr.autopix;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import io.github.warleysr.autopix.cart.CartManager;
import io.github.warleysr.autopix.catalog.CatalogManager;
import io.github.warleysr.autopix.commands.APMenuCommand;
import io.github.warleysr.autopix.commands.AutoPixCommand;
import io.github.warleysr.autopix.expansion.AutoPixExpansion;
import io.github.warleysr.autopix.inventory.InventoryListener;
import io.github.warleysr.autopix.inventory.InventoryManager;
import io.github.warleysr.autopix.mercadopago.WebhookHttpServer;
import io.github.warleysr.autopix.subscription.SubscriptionManager;

public class AutoPix extends JavaPlugin {

    private static String PIX_KEY;
    private static String PIX_NAME;
    private static BukkitTask VALIDATE_TASK, MAPS_TASK, HOLOGRAM_TASK, SUBSCRIPTION_TASK;
    private static AutoPix instance;
    private static final AtomicBoolean INITIAL_RECOVERY_DONE = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;
        INITIAL_RECOVERY_DONE.set(false);
        saveDefaultConfig();
        if (!reloadPlugin()) return;

        AutoPixCommand autoPixCommand = new AutoPixCommand();
        PluginCommand apCmd = getCommand("autopix");
        if (apCmd != null) {
            apCmd.setExecutor(autoPixCommand);
            apCmd.setTabCompleter(autoPixCommand);
        }

        PluginCommand menuCmd = getCommand("autopixmenu");
        if (menuCmd != null) {
            menuCmd.setExecutor(new APMenuCommand());
        }

        Bukkit.getPluginManager().registerEvents(new InventoryListener(), this);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new AutoPixExpansion().register();
        }
    }

    @Override
    public void onDisable() {
        OrderManager.setShuttingDown(true);
        OrderManager.setDatabaseReady(false);
        WebhookHttpServer.stop();
        cancelTask(VALIDATE_TASK);
        cancelTask(MAPS_TASK);
        cancelTask(HOLOGRAM_TASK);
        cancelTask(SUBSCRIPTION_TASK);
        VALIDATE_TASK = null;
        MAPS_TASK = null;
        HOLOGRAM_TASK = null;
        SUBSCRIPTION_TASK = null;
        CartManager.clearAll();
        OrderManager.shutdown();
        instance = null;
    }

    private static void cancelTask(BukkitTask task) {
        if (task != null) task.cancel();
    }

    public static AutoPix getInstance() {
        return instance;
    }

    public static String getPixKey() {
        return PIX_KEY;
    }

    public static String getPixName() {
        return PIX_NAME;
    }

    public static boolean reloadPlugin() {
        AutoPix plugin = getInstance();
        if (plugin == null) return false;
        plugin.reloadConfig();

        PIX_KEY = plugin.getConfig().getString("pix.chave");
        PIX_NAME = plugin.getConfig().getString("pix.nome");
        MSG.loadMessages(plugin);
        SoundManager.loadSounds(plugin);
        OrderManager.syncOnlinePlayersFromServer();
        InventoryManager.createMenuInventory(plugin);
        CatalogManager.load(plugin);

        OrderManager.setDatabaseReady(false);

        Runnable dbInitTask = () -> {
            try {
                if (!OrderManager.startOrderManager(plugin)) {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.setEnabled(false));
                    return;
                }
            } catch (SQLException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Bukkit.getConsoleSender().sendMessage(MSG.getMessage("erro-sql")
                            .replace("{mensagem}", exception.getMessage()));
                    plugin.setEnabled(false);
                });
                return;
            }

            if (INITIAL_RECOVERY_DONE.compareAndSet(false, true)) {
                OrderManager.recoverPendingDeliveries(plugin);
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled()) return;
                WebhookHttpServer.start(plugin);
                scheduleBackgroundTasks(plugin);
            });
        };

        if (Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, dbInitTask);
        } else {
            dbInitTask.run();
        }

        return true;
    }

    private static void scheduleBackgroundTasks(AutoPix plugin) {
        if (plugin.getConfig().getBoolean("automatico.ativado")) {
            cancelTask(VALIDATE_TASK);
            int interval = Math.max(5, plugin.getConfig().getInt("automatico.intervalo", 10));
            VALIDATE_TASK = new BukkitRunnable() {
                @Override
                public void run() {
                    OrderManager.validatePendings(plugin);
                }
            }.runTaskTimerAsynchronously(plugin, interval * 20L, interval * 20L);
        } else {
            cancelTask(VALIDATE_TASK);
            VALIDATE_TASK = null;
        }

        cancelTask(MAPS_TASK);
        int remInterval = Math.max(10, plugin.getConfig().getInt("mapa.intervalo", 60));
        MAPS_TASK = new BukkitRunnable() {
            @Override
            public void run() {
                OrderManager.processOrderExpirations(plugin);
            }
        }.runTaskTimerAsynchronously(plugin, remInterval * 20L, remInterval * 20L);

        cancelTask(HOLOGRAM_TASK);
        int holoInterval = Math.max(5, plugin.getConfig().getInt("tempos.holograma", 30));
        int topLimit = Math.max(1, Math.min(100, plugin.getConfig().getInt("top-doadores", 5)));
        HOLOGRAM_TASK = new BukkitRunnable() {
            @Override
            public void run() {
                OrderManager.refreshStatsCacheNow(topLimit);
            }
        }.runTaskTimerAsynchronously(plugin, 0L, holoInterval * 20L);

        cancelTask(SUBSCRIPTION_TASK);
        SUBSCRIPTION_TASK = new BukkitRunnable() {
            @Override
            public void run() {
                if (OrderManager.isDatabaseReady() && !OrderManager.isShuttingDown()) {
                    SubscriptionManager.processDueSubscriptions();
                }
            }
        }.runTaskTimerAsynchronously(plugin, 600L, 1200L);
    }
}
