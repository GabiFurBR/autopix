package io.github.warleysr.autopix.inventory;

import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.MSG;
import io.github.warleysr.autopix.api.AutoPixAPI;
import io.github.warleysr.autopix.cart.CartManager;
import io.github.warleysr.autopix.OrderManager;
import io.github.warleysr.autopix.SoundManager;
import io.github.warleysr.autopix.TimeManager;
import io.github.warleysr.autopix.domain.Order;
import io.github.warleysr.autopix.domain.OrderProduct;
import io.github.warleysr.autopix.domain.OrderItem;
import io.github.warleysr.autopix.domain.CartItem;
import io.github.warleysr.autopix.domain.PixData;
import io.github.warleysr.autopix.events.AutoPixEventDispatcher;
import io.github.warleysr.autopix.events.CheckoutFailedEvent;
import io.github.warleysr.autopix.events.OrderCreatedEvent;
import io.github.warleysr.autopix.events.PaymentCancelledEvent;
import io.github.warleysr.autopix.mercadopago.MercadoPagoAPI;
import io.github.warleysr.autopix.qrcode.ImageCreator;
import io.github.warleysr.autopix.qrcode.PixGenerator;

public class InventoryListener implements Listener {

    private static final Map<String, String> BUYING_MENU = new ConcurrentHashMap<>();
    private static final Map<String, Integer> BUYING_SLOT = new ConcurrentHashMap<>();
    private static final Map<String, Float> DISCOUNT_PRICES = new ConcurrentHashMap<>();
    private static final Map<String, String> APPLIED_COUPON_NAME = new ConcurrentHashMap<>();
    private static final Map<String, Integer> APPLIED_COUPON_PCT = new ConcurrentHashMap<>();
    private static final Set<String> SET_DISCOUNT = ConcurrentHashMap.newKeySet();
    private static final Set<String> SET_CART_COUPON = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> LAST_CLICK_MS = new ConcurrentHashMap<>();

    private static final DateTimeFormatter COUPON_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        final Player p = (Player) e.getWhoClicked();
        final String playerKey = p.getName().toLowerCase(Locale.ROOT);

        Inventory topInv = e.getView().getTopInventory();
        InventoryHolder holder = topInv != null ? topInv.getHolder() : null;
        AutoPixInventoryHolder apHolder = (holder instanceof AutoPixInventoryHolder)
                ? (AutoPixInventoryHolder) holder
                : null;

        String menu = apHolder != null && apHolder.getMenuType() == AutoPixInventoryHolder.MenuType.SHOP
                ? apHolder.getMenuName()
                : InventoryManager.getMenuByTitle(e.getView().getTitle());

        boolean isCartMenu = apHolder != null
                && apHolder.getMenuType() == AutoPixInventoryHolder.MenuType.CART;
        boolean isConfirmMenu = (apHolder != null && apHolder.getMenuType() == AutoPixInventoryHolder.MenuType.CONFIRM)
                || e.getView().getTitle().equals(InventoryManager.getConfirmTitle());

        boolean isHistoryMenu = (apHolder != null && apHolder.getMenuType() == AutoPixInventoryHolder.MenuType.HISTORY)
                || e.getView().getTitle().equals(InventoryManager.getHistoryTitle());

        if (menu != null) {
            e.setCancelled(true);
            if (e.getClickedInventory() == null || !e.getClickedInventory().equals(topInv)) return;
            if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;
            if (isClickDebounced(playerKey)) return;

            if (e.getSlot() == InventoryManager.getHistoryButtonSlot()) {
                InventoryManager.openHistoryMenu(p);
                return;
            }
            if (InventoryManager.isCartButton(menu, e.getSlot())) {
                InventoryManager.openCartMenu(p);
                return;
            }

            final OrderProduct op = InventoryManager.getOrderProduct(menu, e.getSlot());
            if (op == null) return;

            if (!CartManager.add(p, menu, e.getSlot(), 1)) {
                p.sendMessage("§b[AutoPix] §cNão foi possível adicionar este produto ao carrinho.");
                SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
                return;
            }
            SoundManager.play(p, SoundManager.SELECT_PRODUCT);
            p.sendMessage("§b[AutoPix] §a" + op.getProduct()
                    + " §7foi adicionado ao carrinho. §e/pix carrinho");
        } else if (isCartMenu) {
            e.setCancelled(true);
            if (e.getClickedInventory() == null || !e.getClickedInventory().equals(topInv)) return;
            if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;
            if (isClickDebounced(playerKey)) return;

            if (e.getSlot() == 45) {
                InventoryManager.openMenu(p, "principal");
                return;
            }
            if (e.getSlot() == 47) {
                p.closeInventory();
                SET_CART_COUPON.add(playerKey);
                MSG.sendMessage(p, "inserir-cupom");
                return;
            }
            if (e.getSlot() == 49) {
                CartManager.clear(p);
                InventoryManager.openCartMenu(p);
                SoundManager.play(p, SoundManager.PAYMENT_CANCELLED);
                return;
            }
            if (e.getSlot() == 53) {
                if (!AutoPixAPI.checkout(p, true)) {
                    SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
                }
                return;
            }

            String cartKey = apHolder != null ? apHolder.getCartEntry(e.getSlot()) : null;
            if (cartKey == null) return;
            boolean changed;
            if (e.isShiftClick() && e.isRightClick()) {
                changed = CartManager.remove(p, cartKey);
            } else if (e.isRightClick()) {
                changed = CartManager.changeQuantity(p, cartKey, -1);
            } else if (e.isLeftClick()) {
                changed = CartManager.changeQuantity(p, cartKey, 1);
            } else {
                changed = false;
            }
            if (changed) InventoryManager.openCartMenu(p);
        } else if (isConfirmMenu) {
            e.setCancelled(true);
            if (e.getClickedInventory() == null || !e.getClickedInventory().equals(topInv)) return;
            if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;
            if (isClickDebounced(playerKey)) return;

            if (e.getSlot() == InventoryManager.getCancelSlot()) {
                p.closeInventory();
                SoundManager.play(p, SoundManager.SELECT_PRODUCT);
                return;
            }
            if (e.getSlot() == InventoryManager.getDiscountSlot()) {
                p.closeInventory();
                SET_DISCOUNT.add(playerKey);
                SoundManager.play(p, SoundManager.SELECT_PRODUCT);
                MSG.sendMessage(p, "inserir-cupom");
                return;
            }
            if (e.getSlot() != InventoryManager.getConfirmSlot()) return;

            p.closeInventory();

            if (p.getInventory().getItemInHand().getType() != Material.AIR
                    && !InventoryManager.isPaymentMap(p.getInventory().getItemInHand())) {
                MSG.sendMessage(p, "mao-vazia");
                SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
                return;
            }

            String selectedMenu = BUYING_MENU.get(playerKey);
            Integer selectedSlot = BUYING_SLOT.get(playerKey);
            if (selectedMenu == null || selectedSlot == null) return;

            final OrderProduct op = InventoryManager.getOrderProduct(selectedMenu, selectedSlot);
            if (op == null) return;

            if (!OrderManager.tryAcquireCreateLock(p.getName())) {
                MSG.sendMessage(p, "processando-pedido");
                return;
            }

            if (!TimeManager.canExecute(AutoPix.getInstance(), p, "create")) {
                OrderManager.releaseCreateLock(p.getName());
                return;
            }

            SoundManager.play(p, SoundManager.CONFIRM_PURCHASE);
            final float price = OrderManager.roundPrice(DISCOUNT_PRICES.getOrDefault(playerKey, op.getPrice()));
            final AutoPix plugin = AutoPix.getInstance();

            new BukkitRunnable() {
                @Override
                public void run() {
                    PixData createdPixData = null;
                    try {
                        boolean automaticMode = plugin.getConfig().getBoolean("automatico.ativado", true);
                        boolean generateMap = plugin.getConfig().getBoolean("pix.mapa", true);

                        String qrPayload;
                        if (automaticMode) {
                            Order lastOrder = OrderManager.getLastOrder(p.getName());
                            if (lastOrder != null && !lastOrder.isValidated()) {
                                PixData pd = OrderManager.getPixData(lastOrder);
                                int maxMin = Math.max(1, plugin.getConfig().getInt("mapa.tempo-pagar", 30));
                                long elapsedMin = TimeUnit.MILLISECONDS.toMinutes(
                                        Math.max(0L, System.currentTimeMillis() - lastOrder.getCreated().getTime()));
                                if (pd != null && pd.isPending() && elapsedMin < maxMin) {
                                    TimeManager.resetDelay(p.getName(), "create");
                                    MSG.sendMessage(p, "cancelar-primeiro");
                                    SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
                                    return;
                                }
                            }

                            MSG.sendMessage(p, "criando-mapa");
                            MSG.sendActionBar(p, "actionbar-gerando", "{produto}", op.getProduct());
                            createdPixData = MercadoPagoAPI.createPixPayment(plugin, p, op, price);
                            if (createdPixData == null) {
                                TimeManager.resetDelay(p.getName(), "create");
                                return;
                            }
                            qrPayload = createdPixData.getQrCode();
                        } else {
                            MSG.sendMessage(p, "criando-mapa");
                            MSG.sendActionBar(p, "actionbar-gerando", "{produto}", op.getProduct());
                            qrPayload = PixGenerator.generatePayload(
                                    AutoPix.getPixKey(), AutoPix.getPixName(), op.getProduct(), price);
                        }

                        if (qrPayload == null || qrPayload.trim().isEmpty()) {
                            TimeManager.resetDelay(p.getName(), "create");
                            MSG.sendMessage(p, "erro");
                            SoundManager.play(p, SoundManager.CONNECTION_ERROR);
                            return;
                        }

                        Order ord = OrderManager.createOrderWithPixData(
                                p.getName(), op.getProduct(), price, createdPixData);
                        if (ord == null) {
                            TimeManager.resetDelay(p.getName(), "create");
                            if (createdPixData != null) {
                                try {
                                    MercadoPagoAPI.cancelPayment(plugin, createdPixData.getPaymentId());
                                } catch (Exception cancelEx) {
                                    plugin.getLogger().warning("Nao foi possivel cancelar cobranca orfa #"
                                            + createdPixData.getPaymentId() + " no Mercado Pago: " + cancelEx.getMessage());
                                }
                            }
                            MSG.sendMessage(p, "erro");
                            SoundManager.play(p, SoundManager.CONNECTION_ERROR);
                            return;
                        }

                        OrderManager.refreshStatsCacheAsync(plugin);

                        final BufferedImage qr = (automaticMode || generateMap)
                                ? ImageCreator.generateQR(qrPayload)
                                : null;
                        final String finalPaymentId = createdPixData != null ? createdPixData.getPaymentId() : null;
                        final int maxMinutes = Math.max(1, plugin.getConfig().getInt("mapa.tempo-pagar", 30));

                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                for (String preCmd : op.getPreCommands()) {
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                                            preCmd.replace("{player}", p.getName()));
                                }

                                if (p.isOnline()) {
                                    if ((automaticMode || generateMap) && qr != null) {
                                        ItemStack map = ImageCreator.generateMap(qr, p, op);
                                        if (!InventoryManager.deliverPaymentMapSafely(p, map)) {
                                            MSG.sendMessage(p, "mao-vazia");
                                        }
                                    }

                                    SoundManager.play(p, SoundManager.GENERATE_QR);
                                    MSG.sendTitle(p, "titulo-qr", "subtitulo-qr", 10, 70, 20,
                                            "{produto}", op.getProduct(),
                                            "{tempo}", Integer.toString(maxMinutes));
                                    MSG.sendActionBar(p, "actionbar-pendente",
                                            "{produto}", op.getProduct(),
                                            "{tempo}", Integer.toString(maxMinutes));
                                }

                                AutoPixEventDispatcher.dispatch(new OrderCreatedEvent(
                                        ord.getId(), ord.getPlayer(), p.getUniqueId(),
                                        ord.getProduct(), ord.getPrice(), finalPaymentId,
                                        "pending", ord.getCreated().getTime()));
                            }
                        }.runTask(plugin);

                    } catch (Exception ex) {
                        TimeManager.resetDelay(p.getName(), "create");
                        plugin.getLogger().warning("Erro ao gerar pedido PIX para " + p.getName() + ": " + ex.getMessage());
                        MSG.sendMessage(p, "erro");
                        SoundManager.play(p, SoundManager.CONNECTION_ERROR);
                    } finally {
                        OrderManager.releaseCreateLock(p.getName());
                    }
                }
            }.runTaskAsynchronously(plugin);
        } else if (isHistoryMenu) {
            e.setCancelled(true);
            if (e.getClickedInventory() == null || !e.getClickedInventory().equals(topInv)) return;
            if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR) return;
            if (isClickDebounced(playerKey)) return;

            if (e.getSlot() == 27) {
                InventoryManager.openMenu(p, "principal");
                return;
            }
            if (e.getSlot() == 35) {
                p.closeInventory();
                InventoryManager.openInfo(p);
                return;
            }

            if (e.getSlot() >= 0 && e.getSlot() < 27 && e.getCurrentItem().getType() == Material.CLOCK) {
                if (e.isShiftClick()) {
                    p.closeInventory();
                    p.performCommand("pix cancelar");
                } else {
                    p.closeInventory();
                    Bukkit.getScheduler().runTaskAsynchronously(AutoPix.getInstance(), () -> {
                        Order lastOrder = OrderManager.getLastOrder(p.getName());
                        if (lastOrder != null && !lastOrder.isValidated()) {
                            PixData pd = OrderManager.getPixData(lastOrder);
                            if (pd != null && pd.isPending()) {
                                InventoryManager.restorePendingMapToPlayer(p, lastOrder, pd);
                            }
                        }
                    });
                }
            }
        } else if (InventoryManager.isPaymentMap(e.getCurrentItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onCheckoutFailed(CheckoutFailedEvent event) {
        Player player = Bukkit.getPlayer(event.getPlayerUniqueId());
        if (player == null || !player.isOnline()) {
            player = Bukkit.getPlayerExact(event.getPlayerName());
        }
        if (player == null || !player.isOnline()) return;

        if ("pending_order".equalsIgnoreCase(event.getReason())) {
            MSG.sendMessage(player, "cancelar-primeiro");
        } else {
            MSG.sendMessage(player, "erro");
        }
        SoundManager.play(player, SoundManager.CONNECTION_ERROR);
    }

    private static boolean isClickDebounced(String playerKey) {
        long now = System.currentTimeMillis();
        Long last = LAST_CLICK_MS.get(playerKey);
        if (last != null && (now - last) < 250L) {
            return true;
        }
        LAST_CLICK_MS.put(playerKey, now);
        return false;
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack item = e.getItemDrop().getItemStack();
        if (InventoryManager.isPaymentMap(item)) {
            if (item.getItemMeta().hasLore()) {
                e.setCancelled(true);
            } else {
                e.getItemDrop().remove();
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.hasItem() && InventoryManager.isPaymentMap(e.getItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(InventoryManager::isPaymentMap);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        OrderManager.markPlayerOnline(p.getName(), p.getUniqueId());

        final AutoPix plugin = AutoPix.getInstance();
        if (plugin == null) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                Order order = OrderManager.getLastOrder(p.getName());
                if (order == null) {
                    InventoryManager.removeUnpaidMaps(p);
                    return;
                }

                int maxMinutes = Math.max(1, plugin.getConfig().getInt("mapa.tempo-pagar", 30));
                long elapsedMinutes = TimeUnit.MILLISECONDS.toMinutes(
                        Math.max(0L, System.currentTimeMillis() - order.getCreated().getTime()));

                if (order.isValidated() || elapsedMinutes >= maxMinutes) {
                    InventoryManager.removeUnpaidMaps(p);
                    return;
                }

                List<OrderItem> savedItems = OrderManager.getOrderItems(order.getId());
                OrderProduct op = savedItems.isEmpty()
                        ? InventoryManager.getProductByOrder(order)
                        : new OrderProduct(order.getProduct(), order.getPrice(), null,
                                java.util.Collections.emptyList(), java.util.Collections.emptyList());
                if (op == null) return;

                PixData pd = OrderManager.getPixData(order);
                if (pd == null || !pd.isPending()) {
                    InventoryManager.removeUnpaidMaps(p);
                    return;
                }

                final long remainingMinutes = Math.max(1L, maxMinutes - elapsedMinutes);
                try {
                    final BufferedImage qrCode = ImageCreator.generateQR(pd.getQrCode());
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (!p.isOnline()) return;
                            int mapSlot = InventoryManager.findPaymentMapSlot(p);
                            if (mapSlot >= 0) {
                                ItemStack map = ImageCreator.generateMap(qrCode, p, op);
                                p.getInventory().setItem(mapSlot, map);
                            }
                            MSG.sendMessage(p, "pedido-recuperado-join",
                                    "{produto}", order.getProduct(),
                                    "{tempo}", Long.toString(remainingMinutes),
                                    "{id}", Integer.toString(order.getId()));
                            MSG.sendActionBar(p, "actionbar-pendente",
                                    "{produto}", order.getProduct(),
                                    "{tempo}", Long.toString(remainingMinutes));
                        }
                    }.runTask(plugin);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Erro ao recuperar QR Code no login de "
                            + p.getName() + ": " + ex.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        String playerKey = p.getName().toLowerCase(Locale.ROOT);
        OrderManager.markPlayerOffline(p.getName());
        SET_DISCOUNT.remove(playerKey);
        SET_CART_COUPON.remove(playerKey);
        LAST_CLICK_MS.remove(playerKey);
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        if (e.isCancelled()) return;
        Player p = e.getPlayer();
        String playerKey = p.getName().toLowerCase(Locale.ROOT);

        if (SET_CART_COUPON.contains(playerKey)) {
            e.setCancelled(true);
            String rawCoupon = e.getMessage().trim();
            if (rawCoupon.equalsIgnoreCase("cancelar")) {
                SET_CART_COUPON.remove(playerKey);
                MSG.sendMessage(p, "compra-cancelada");
                SoundManager.play(p, SoundManager.PAYMENT_CANCELLED);
                return;
            }

            FileConfiguration cfg = AutoPix.getInstance().getConfig();
            String coupon = findCouponKeyIgnoreCase(cfg, rawCoupon);
            if (coupon == null) {
                MSG.sendMessage(p, "cupom-invalido");
                SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
                return;
            }

            List<CartItem> cartItems = CartManager.getItems(p);
            if (cartItems.isEmpty()) {
                SET_CART_COUPON.remove(playerKey);
                MSG.sendMessage(p, "compra-cancelada");
                return;
            }

            if (cfg.isSet("cupons." + coupon + ".validade")) {
                String expire = cfg.getString("cupons." + coupon + ".validade", "").trim();
                if (!expire.isEmpty()) {
                    try {
                        LocalDate expireDate = LocalDate.parse(expire, COUPON_DATE_FORMAT);
                        if (LocalDate.now().isAfter(expireDate)) {
                            MSG.sendMessage(p, "cupom-expirado");
                            SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
                            return;
                        }
                    } catch (DateTimeParseException ex) {
                        AutoPix.getInstance().getLogger().warning("Data de validade invalida no cupom "
                                + coupon + ": " + expire);
                    }
                }
            }

            List<String> allowed = cfg.getStringList("cupons." + coupon + ".itens");
            boolean appliesToAll = !allowed.isEmpty();
            for (CartItem item : cartItems) {
                if (!isProductAllowedForCoupon(allowed, item.getProduct())) {
                    appliesToAll = false;
                    break;
                }
            }
            if (!appliesToAll) {
                MSG.sendMessage(p, "cupom-nao-aplicavel");
                SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
                return;
            }

            int discount = Math.max(1, Math.min(99, cfg.getInt("cupons." + coupon + ".porcentagem", 0)));
            CartManager.applyDiscount(p, coupon, discount);
            SET_CART_COUPON.remove(playerKey);
            p.sendMessage("§b[AutoPix] §aCupom " + coupon + " aplicado: -" + discount
                    + "%. Total: §fR$ " + String.format(Locale.ROOT, "%.2f",
                    CartManager.getTotal(p).doubleValue()).replace('.', ','));
            SoundManager.play(p, SoundManager.CONFIRM_PURCHASE);
            Bukkit.getScheduler().runTask(AutoPix.getInstance(), () -> InventoryManager.openCartMenu(p));
            return;
        }

        if (!SET_DISCOUNT.contains(playerKey)) return;

        e.setCancelled(true);

        String rawCoupon = e.getMessage().trim();
        if (rawCoupon.equalsIgnoreCase("cancelar")) {
            SET_DISCOUNT.remove(playerKey);
            MSG.sendMessage(p, "compra-cancelada");
            SoundManager.play(p, SoundManager.PAYMENT_CANCELLED);
            return;
        }

        FileConfiguration cfg = AutoPix.getInstance().getConfig();
        String matchedCoupon = findCouponKeyIgnoreCase(cfg, rawCoupon);
        if (matchedCoupon == null) {
            MSG.sendMessage(p, "cupom-invalido");
            SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
            return;
        }

        String menu = BUYING_MENU.get(playerKey);
        Integer slot = BUYING_SLOT.get(playerKey);
        if (menu == null || slot == null) {
            SET_DISCOUNT.remove(playerKey);
            return;
        }
        OrderProduct op = InventoryManager.getOrderProduct(menu, slot);
        if (op == null) {
            SET_DISCOUNT.remove(playerKey);
            return;
        }

        if (!isProductAllowedForCoupon(cfg.getStringList("cupons." + matchedCoupon + ".itens"), op.getProduct())) {
            MSG.sendMessage(p, "cupom-nao-aplicavel");
            SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
            return;
        }

        if (cfg.isSet("cupons." + matchedCoupon + ".validade")) {
            String expire = cfg.getString("cupons." + matchedCoupon + ".validade", "").trim();
            if (!expire.isEmpty()) {
                try {
                    LocalDate expireDate = LocalDate.parse(expire, COUPON_DATE_FORMAT);
                    if (LocalDate.now().isAfter(expireDate)) {
                        MSG.sendMessage(p, "cupom-expirado");
                        SoundManager.play(p, SoundManager.PAYMENT_REJECTED);
                        return;
                    }
                } catch (DateTimeParseException ex) {
                    AutoPix.getInstance().getLogger().warning("Data de validade invalida no cupom "
                            + matchedCoupon + ": " + expire);
                }
            }
        }

        int discount = Math.max(1, Math.min(99, cfg.getInt("cupons." + matchedCoupon + ".porcentagem", 0)));
        float newPrice = OrderManager.roundPrice((1.0 - (discount / 100.0)) * op.getPrice());
        if (newPrice < 0.01f) {
            newPrice = 0.01f;
        }

        SET_DISCOUNT.remove(playerKey);
        DISCOUNT_PRICES.put(playerKey, newPrice);
        APPLIED_COUPON_NAME.put(playerKey, matchedCoupon);
        APPLIED_COUPON_PCT.put(playerKey, discount);

        String formattedValue = String.format(Locale.ROOT, "%.2f", newPrice).replace('.', ',');
        MSG.sendMessage(p, "cupom-aplicado",
                "{porcentagem}", Integer.toString(discount),
                "{valor}", formattedValue,
                "{produto}", op.getProduct());
        SoundManager.play(p, SoundManager.CONFIRM_PURCHASE);

        final float finalPrice = newPrice;
        final String couponName = matchedCoupon;
        final int finalDiscount = discount;
        new BukkitRunnable() {
            @Override
            public void run() {
                InventoryManager.openConfirmation(p, op, finalPrice, couponName, finalDiscount);
            }
        }.runTask(AutoPix.getInstance());
    }

    private static String findCouponKeyIgnoreCase(FileConfiguration cfg, String input) {
        ConfigurationSection section = cfg.getConfigurationSection("cupons");
        if (section == null || input == null) return null;
        for (String key : section.getKeys(false)) {
            if (key.equalsIgnoreCase(input.trim())) {
                return key;
            }
        }
        return null;
    }

    private static boolean isProductAllowedForCoupon(List<String> allowedItems, String productName) {
        if (allowedItems == null || productName == null) return false;
        for (String item : allowedItems) {
            if (item != null && item.trim().equalsIgnoreCase(productName.trim())) {
                return true;
            }
        }
        return false;
    }
}
