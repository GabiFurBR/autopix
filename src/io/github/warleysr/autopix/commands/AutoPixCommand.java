package io.github.warleysr.autopix.commands;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.MSG;
import io.github.warleysr.autopix.OrderManager;
import io.github.warleysr.autopix.SoundManager;
import io.github.warleysr.autopix.TimeManager;
import io.github.warleysr.autopix.domain.DonorInfo;
import io.github.warleysr.autopix.domain.Order;
import io.github.warleysr.autopix.domain.PixData;
import io.github.warleysr.autopix.events.AutoPixEventDispatcher;
import io.github.warleysr.autopix.events.PaymentCancelledEvent;
import io.github.warleysr.autopix.inventory.InventoryManager;
import io.github.warleysr.autopix.mercadopago.MPValidator;
import io.github.warleysr.autopix.mercadopago.MercadoPagoAPI;

public class AutoPixCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String command, String[] args) {
        AutoPix plugin = AutoPix.getInstance();
        if (args.length >= 1) {
            String sub = args[0].toLowerCase(Locale.ROOT);

            if (sub.equals("info")) {
                if (!(sender instanceof Player)) {
                    MSG.sendMessage(sender, "in-game");
                    return false;
                }
                InventoryManager.openInfo((Player) sender);
                return true;
            } else if (sub.equals("historico") || sub.equals("history") || sub.equals("pedidos")) {
                if (!(sender instanceof Player)) {
                    MSG.sendMessage(sender, "in-game");
                    return false;
                }
                Player p = (Player) sender;
                if (!p.hasPermission("autopix.use")) {
                    MSG.sendMessage(p, "sem-permissao");
                    return false;
                }
                InventoryManager.openHistoryMenu(p);
                return true;
            } else if (sub.equals("menu") || sub.equals("loja")) {
                if (!(sender instanceof Player)) {
                    MSG.sendMessage(sender, "in-game");
                    return false;
                }
                Player p = (Player) sender;
                if (!p.hasPermission("autopix.use")) {
                    MSG.sendMessage(p, "sem-permissao");
                    return false;
                }
                InventoryManager.openMenu(p, "principal");
                return true;
            } else if (sub.equals("lista")) {
                if (args.length == 1) {
                    if (!(sender instanceof Player)) {
                        MSG.sendMessage(sender, "ajuda-lista");
                        return false;
                    }
                    if (!TimeManager.canExecute(plugin, (Player) sender, "list")) {
                        return false;
                    }
                    sendOrderList(sender, sender.getName());
                    return true;
                }
                if (!sender.hasPermission("autopix.admin")) {
                    MSG.sendMessage(sender, "sem-permissao");
                    return false;
                }
                sendOrderList(sender, args[1]);
                return true;
            } else if (sub.equals("validar")) {
                if (!(sender instanceof Player)) {
                    MSG.sendMessage(sender, "in-game");
                    return false;
                }
                Player player = (Player) sender;
                if (plugin.getConfig().getBoolean("automatico.ativado")) {
                    return false;
                }
                if (args.length == 1) {
                    MSG.sendMessage(player, "ajuda-validar");
                    return false;
                }
                String pixId = args[1].trim();
                if (pixId.length() != 32 || pixId.charAt(0) != 'E') {
                    MSG.sendMessage(player, "pix-invalido");
                    SoundManager.play(player, SoundManager.PAYMENT_REJECTED);
                    return false;
                }
                if (!TimeManager.canExecute(plugin, player, "validate")) {
                    return false;
                }
                MSG.sendMessage(player, "validando");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    MPValidator.validateTransaction(plugin, pixId, player);
                });
                return true;
            } else if (sub.equals("cancelar")) {
                if (!(sender instanceof Player)) {
                    MSG.sendMessage(sender, "in-game");
                    return false;
                }
                Player p = (Player) sender;
                if (!TimeManager.canExecute(plugin, p, "cancel")) {
                    return false;
                }
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Order order = OrderManager.getLastOrder(p.getName());
                        if (order == null || order.isValidated()) {
                            MSG.sendMessage(p, "sem-pedido-pendente");
                            return;
                        }

                        PixData pd = OrderManager.getPixData(order);
                        if (pd == null || !pd.isPending()) {
                            MSG.sendMessage(p, "sem-pedido-pendente");
                            return;
                        }

                        try {
                            boolean cancelled = MercadoPagoAPI.cancelPayment(plugin, pd.getPaymentId());
                            if (!cancelled) {
                                MSG.sendMessage(p, "erro-cancelar");
                                SoundManager.play(p, SoundManager.CONNECTION_ERROR);
                                return;
                            }

                            if (!OrderManager.cancelPendingPixData(pd)) {
                                MSG.sendMessage(p, "erro-cancelar");
                                SoundManager.play(p, SoundManager.CONNECTION_ERROR);
                                return;
                            }

                            OrderManager.refreshStatsCacheAsync(plugin);
                            TimeManager.resetDelay(p.getName(), "create");

                            Bukkit.getScheduler().runTask(plugin, () -> {
                                InventoryManager.removeUnpaidMaps(p);
                                SoundManager.play(p, SoundManager.PAYMENT_CANCELLED);
                                MSG.sendActionBar(p, "actionbar-cancelado");
                                MSG.sendMessage(p, "cancelado");
                                InventoryManager.refreshOpenInventory(p);

                                AutoPixEventDispatcher.dispatch(new PaymentCancelledEvent(
                                        order.getId(), order.getPlayer(), p.getUniqueId(),
                                        order.getProduct(), order.getPrice(), pd.getPaymentId(),
                                        "cancelled", "manual"));
                            });
                        } catch (Exception e) {
                            plugin.getLogger().warning("Erro ao cancelar pedido de " + p.getName() + ": " + e.getMessage());
                            MSG.sendMessage(p, "erro-cancelar");
                            SoundManager.play(p, SoundManager.CONNECTION_ERROR);
                        }
                    }
                }.runTaskAsynchronously(plugin);
                return true;
            } else if (sub.equals("reload")) {
                if (!sender.hasPermission("autopix.admin")) {
                    MSG.sendMessage(sender, "sem-permissao");
                    return false;
                }
                AutoPix.reloadPlugin();
                MSG.sendMessage(sender, "reload-executado");
                return true;
            } else if (sub.equals("top")) {
                if (sender instanceof Player && !TimeManager.canExecute(plugin, (Player) sender, "top")) {
                    return false;
                }

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    List<DonorInfo> topDonors = OrderManager.getTopDonors();
                    if (topDonors.isEmpty()) {
                        MSG.sendMessage(sender, "sem-doadores");
                        return;
                    }

                    StringBuilder message = new StringBuilder();
                    message.append(MSG.getMessage("cabecalho-top")).append("\n");

                    for (DonorInfo info : topDonors) {
                        message.append(MSG.getMessage("corpo-top")
                                .replace("{doador}", info.getDonor())
                                .replace("{total}", String.format(Locale.ROOT, "%.2f", info.getTotal()).replace('.', ',')));
                        message.append("\n");
                    }
                    message.append("\n");

                    final String finalOutput = message.toString();
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(finalOutput));
                });
                return true;
            }
        }

        MSG.sendMessage(sender, "ajuda-autopix");
        return false;
    }

    private void sendOrderList(final CommandSender sender, final String player) {
        AutoPix plugin = AutoPix.getInstance();
        FileConfiguration config = plugin.getConfig();
        int maxMinutes = Math.max(1, config.getInt("mapa.tempo-pagar", 30));
        boolean automaticMode = config.getBoolean("automatico.ativado", true);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Order> orders = OrderManager.getOrders(player);
            if (orders.isEmpty()) {
                MSG.sendMessage(sender, "sem-ordens");
                return;
            }
            StringBuilder message = new StringBuilder();
            message.append(MSG.getMessage("cabecalho")).append("\n");
            for (Order ord : orders) {
                PixData pd = automaticMode ? OrderManager.getPixData(ord) : null;
                String status = InventoryManager.resolveOrderStatus(ord, pd, automaticMode, maxMinutes);

                message.append(MSG.getMessage("corpo")
                        .replace("{id}", Integer.toString(ord.getId()))
                        .replace("{data}", DATE_FORMAT.format(Instant.ofEpochMilli(ord.getCreated().getTime())))
                        .replace("{preco}", String.format(Locale.ROOT, "%.2f", ord.getPrice()).replace('.', ','))
                        .replace("{produto}", ord.getProduct())
                        .replace("{status}", getPrettyStatus(status)));
                message.append("\n");
            }

            message.append("\n");
            final String finalOutput = message.toString();
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(finalOutput));
        });
    }

    public static String getPrettyStatus(String status) {
        if (status == null) return "\u00a77INDEFINIDO";
        switch (status.toLowerCase(Locale.ROOT)) {
            case "approved":
                return "\u00a7aAPROVADO";
            case "pending":
                return "\u00a7ePENDENTE";
            case "cancelled":
                return "\u00a7cCANCELADO";
            case "rejected":
                return "\u00a7cREJEITADO";
            case "amount_mismatch":
                return "\u00a7cVALOR DIVERGENTE";
            default:
                return "\u00a77INDEFINIDO";
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subcommands = new ArrayList<>();
            subcommands.add("info");
            subcommands.add("lista");
            subcommands.add("historico");
            subcommands.add("cancelar");
            subcommands.add("top");
            if (!AutoPix.getInstance().getConfig().getBoolean("automatico.ativado", true)) {
                subcommands.add("validar");
            }
            if (sender.hasPermission("autopix.admin")) {
                subcommands.add("reload");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            subcommands.removeIf(s -> !s.startsWith(prefix));
            return subcommands;
        }
        return Collections.emptyList();
    }
}
