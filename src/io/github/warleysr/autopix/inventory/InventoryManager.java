package io.github.warleysr.autopix.inventory;

import java.awt.image.BufferedImage;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.MSG;
import io.github.warleysr.autopix.OrderManager;
import io.github.warleysr.autopix.SoundManager;
import io.github.warleysr.autopix.domain.Order;
import io.github.warleysr.autopix.domain.OrderProduct;
import io.github.warleysr.autopix.domain.CartItem;
import io.github.warleysr.autopix.domain.OrderItem;
import io.github.warleysr.autopix.cart.CartManager;
import io.github.warleysr.autopix.domain.PixData;
import io.github.warleysr.autopix.qrcode.ImageCreator;

public class InventoryManager {

    private static final Map<String, Inventory> MENUS = new ConcurrentHashMap<>();
    private static final Map<String, String> MENUS_TITLES = new ConcurrentHashMap<>();
    private static volatile String confirmTitle = "\u00a79\u00a7lConfirmar compra por \u00a7c\u00a7lPIX";
    private static volatile String historyTitle = "\u00a79\u00a7lHist\u00f3rico de Pedidos \u00a7b\u00a7lPIX";
    private static volatile String mapTitle = "\u00a7aQR code de pagamento";
    private static volatile int confirmMenuSize = 27;
    private static volatile int cancelSlot = 18;
    private static volatile int confirmSlot = 26;
    private static volatile int discountSlot = 13;
    private static volatile int summarySlot = 4;
    private static volatile int historyButtonSlot = -1;
    private static volatile ItemStack cancelTemplate;
    private static volatile ItemStack confirmTemplate;
    private static volatile ItemStack discountTemplate;
    private static volatile ItemStack historyButtonTemplate;
    private static volatile ItemStack INFO;

    private static final Map<String, Map<Integer, OrderProduct>> PRODUCTS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> CART_BUTTON_SLOTS = new ConcurrentHashMap<>();
    private static final DateTimeFormatter MAP_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy '\u00e0s' HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter SHORT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    public static void createMenuInventory(AutoPix ap) {
        MENUS.clear();
        MENUS_TITLES.clear();
        PRODUCTS.clear();
        CART_BUTTON_SLOTS.clear();
        historyButtonSlot = -1;

        FileConfiguration cfg = ap.getConfig();
        ConfigurationSection menuSection = cfg.getConfigurationSection("menu");
        if (menuSection != null) {
            for (String rawMenu : menuSection.getKeys(false)) {
                String menu = rawMenu.toLowerCase(Locale.ROOT);
                if (menu.equals("confirmar") || menu.equals("historico")) continue;

                String menuPath = "menu." + rawMenu;
                String menuTitle = MSG.colorize(cfg.getString(menuPath + ".titulo", "&9&lCompre produtos por &c&lPIX"));
                int size = normalizeInventorySize(cfg.getInt(menuPath + ".tamanho", 54), 54);

                AutoPixInventoryHolder holder = new AutoPixInventoryHolder(
                        AutoPixInventoryHolder.MenuType.SHOP, menu, null);
                Inventory inv = Bukkit.createInventory(holder, size, menuTitle);
                holder.setInventory(inv);

                if (cfg.getBoolean(menuPath + ".preencher-fundo", false)) {
                    fillEmptySlots(inv, createFillerPane(cfg.getString(menuPath + ".material-fundo", "GRAY_STAINED_GLASS_PANE")));
                }

                Map<Integer, OrderProduct> menuProducts = new ConcurrentHashMap<>();
                PRODUCTS.put(menu, menuProducts);

                ConfigurationSection prodSection = cfg.getConfigurationSection(menuPath + ".produtos");
                if (prodSection != null) {
                    for (String product : prodSection.getKeys(false)) {
                        String prodPath = menuPath + ".produtos." + product;
                        float price = OrderManager.roundPrice(cfg.getDouble(prodPath + ".preco", 0.0));
                        int slot = cfg.getInt(prodPath + ".icone.slot", 1) - 1;
                        if (slot < 0 || slot >= size) continue;

                        ItemStack icon = loadItem(cfg, prodPath + ".icone", price);
                        inv.setItem(slot, icon);

                        List<String> preCommands = cfg.getStringList(prodPath + ".pre-comandos");
                        List<String> commands = cfg.getStringList(prodPath + ".comandos");
                        menuProducts.put(slot, new OrderProduct(product, price, icon, preCommands, commands));
                    }
                }

                int hSlot = -1;
                if (cfg.isConfigurationSection(menuPath + ".historico")) {
                    hSlot = cfg.getInt(menuPath + ".historico.slot", size) - 1;
                    if (hSlot >= 0 && hSlot < size) {
                        historyButtonSlot = hSlot;
                        historyButtonTemplate = loadItem(cfg, menuPath + ".historico.icone", 0);
                        inv.setItem(hSlot, historyButtonTemplate);
                    }
                }

                int cartSlot = -1;
                for (int candidate = size - 1; candidate >= 0; candidate--) {
                    if (menuProducts.containsKey(candidate) || candidate == hSlot) continue;
                    cartSlot = candidate;
                    break;
                }
                if (cartSlot >= 0) {
                    ItemStack cartButton = createSimpleItem(Material.CHEST, "§6§lMeu carrinho",
                            Arrays.asList("§7Adicione produtos à compra", "§7e finalize tudo em um PIX só.", " ",
                                    "§eClique para abrir o carrinho."));
                    inv.setItem(cartSlot, cartButton);
                    CART_BUTTON_SLOTS.put(menu, cartSlot);
                }

                MENUS.put(menu, inv);
                MENUS_TITLES.put(menuTitle, menu);
            }
        }

        confirmTitle = MSG.colorize(cfg.getString("menu.confirmar.titulo", "&9&lConfirmar compra por &c&lPIX"));
        confirmMenuSize = normalizeInventorySize(cfg.getInt("menu.confirmar.tamanho", 27), 27);
        historyTitle = MSG.colorize(cfg.getString("menu.historico.titulo", "&9&lHist\u00f3rico de Pedidos &b&lPIX"));
        mapTitle = MSG.colorize(cfg.getString("mapa.nome", "&aQR code de pagamento"));

        cancelSlot = clampSlot(cfg.getInt("menu.confirmar.cancelar.slot", 19) - 1, confirmMenuSize, 18);
        confirmSlot = clampSlot(cfg.getInt("menu.confirmar.confirmar.slot", 27) - 1, confirmMenuSize, 26);
        discountSlot = clampSlot(cfg.getInt("menu.confirmar.desconto.slot", 14) - 1, confirmMenuSize, 13);
        summarySlot = clampSlot(cfg.getInt("menu.confirmar.resumo.slot", 5) - 1, confirmMenuSize, 4);

        cancelTemplate = loadItem(cfg, "menu.confirmar.cancelar.icone", 0);
        confirmTemplate = loadItem(cfg, "menu.confirmar.confirmar.icone", 0);
        discountTemplate = loadItem(cfg, "menu.confirmar.desconto.icone", 0);

        INFO = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) INFO.getItemMeta();
        if (meta != null) {
            ConfigurationSection pagesSection = cfg.getConfigurationSection("info.paginas");
            if (pagesSection != null) {
                for (String page : pagesSection.getKeys(false)) {
                    StringBuilder content = new StringBuilder();
                    for (String line : cfg.getStringList("info.paginas." + page)) {
                        content.append(MSG.colorize(line)).append("\n");
                    }
                    meta.addPage(content.toString());
                }
            }
            meta.setTitle(MSG.colorize(cfg.getString("info.titulo", "&aInfo PIX")));
            meta.setAuthor(MSG.colorize(cfg.getString("info.autor", "&bAutoPix")));
            INFO.setItemMeta(meta);
        }
    }

    public static void openMenu(Player p, String menu) {
        if (p == null || menu == null) return;
        String key = menu.toLowerCase(Locale.ROOT);
        if (key.equals("historico") || key.equals("history") || key.equals("pedidos")) {
            openHistoryMenu(p);
            return;
        }
        Inventory inv = MENUS.get(key);
        if (inv != null) {
            p.openInventory(inv);
            SoundManager.play(p, SoundManager.OPEN_MENU);
        }
    }

    public static void openConfirmation(Player p) {
        openConfirmation(p, null, null, null, 0);
    }

    public static void openConfirmation(Player p, OrderProduct product, Float finalPrice,
                                        String appliedCoupon, int discountPercent) {
        if (p == null) return;

        AutoPixInventoryHolder holder = new AutoPixInventoryHolder(
                AutoPixInventoryHolder.MenuType.CONFIRM, "confirmar", p.getName());
        Inventory inv = Bukkit.createInventory(holder, confirmMenuSize, confirmTitle);
        holder.setInventory(inv);

        AutoPix plugin = AutoPix.getInstance();
        boolean fillBackground = plugin != null && plugin.getConfig().getBoolean("menu.confirmar.preencher-fundo", true);
        if (fillBackground) {
            fillEmptySlots(inv, createFillerPane("GRAY_STAINED_GLASS_PANE"));
        }

        if (cancelTemplate != null && cancelSlot >= 0 && cancelSlot < confirmMenuSize) {
            inv.setItem(cancelSlot, cancelTemplate.clone());
        }
        if (discountTemplate != null && discountSlot >= 0 && discountSlot < confirmMenuSize) {
            inv.setItem(discountSlot, buildDiscountButton(discountTemplate.clone(), appliedCoupon, discountPercent));
        }
        if (confirmTemplate != null && confirmSlot >= 0 && confirmSlot < confirmMenuSize) {
            float effectivePrice = finalPrice != null
                    ? finalPrice
                    : (product != null ? product.getPrice() : 0.0f);
            inv.setItem(confirmSlot, buildConfirmButton(confirmTemplate.clone(), product, effectivePrice));
        }

        if (product != null) {
            int targetSummarySlot = summarySlot;
            if (targetSummarySlot == cancelSlot || targetSummarySlot == confirmSlot || targetSummarySlot == discountSlot) {
                targetSummarySlot = 4;
            }
            if (targetSummarySlot >= 0 && targetSummarySlot < confirmMenuSize) {
                float effectivePrice = finalPrice != null ? finalPrice : product.getPrice();
                inv.setItem(targetSummarySlot, buildProductSummaryItem(product, effectivePrice, appliedCoupon, discountPercent));
            }
        }

        p.openInventory(inv);
    }

    public static void openHistoryMenu(Player p) {
        if (p == null) return;
        AutoPix plugin = AutoPix.getInstance();
        if (plugin == null) return;

        SoundManager.play(p, SoundManager.OPEN_MENU);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Order> orders = OrderManager.getOrders(p.getName(), 27);
            Map<Integer, PixData> pixDataMap = new HashMap<>();
            for (Order ord : orders) {
                PixData pd = OrderManager.getPixData(ord);
                if (pd != null) {
                    pixDataMap.put(ord.getId(), pd);
                }
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                AutoPixInventoryHolder holder = new AutoPixInventoryHolder(
                        AutoPixInventoryHolder.MenuType.HISTORY, "historico", p.getName());
                Inventory inv = Bukkit.createInventory(holder, 36, historyTitle);
                holder.setInventory(inv);

                populateHistoryInventory(plugin, inv, p, orders, pixDataMap);
                p.openInventory(inv);
            });
        });
    }

    public static void refreshOpenInventory(Player p) {
        if (p == null || !p.isOnline() || Bukkit.getServer() == null) return;
        if ( p.getOpenInventory() == null || p.getOpenInventory().getTopInventory() == null) return;

        Inventory top = p.getOpenInventory().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof AutoPixInventoryHolder)) return;

        AutoPixInventoryHolder apHolder = (AutoPixInventoryHolder) holder;
        if (apHolder.getMenuType() == AutoPixInventoryHolder.MenuType.HISTORY) {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin == null || !plugin.isEnabled()) return;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<Order> orders = OrderManager.getOrders(p.getName(), 27);
                Map<Integer, PixData> pixDataMap = new HashMap<>();
                for (Order ord : orders) {
                    PixData pd = OrderManager.getPixData(ord);
                    if (pd != null) {
                        pixDataMap.put(ord.getId(), pd);
                    }
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) return;
                    Inventory currentTop = p.getOpenInventory().getTopInventory();
                    if (currentTop != null && currentTop.getHolder() == apHolder) {
                        populateHistoryInventory(plugin, currentTop, p, orders, pixDataMap);
                    }
                });
            });
        }
    }

    private static void populateHistoryInventory(AutoPix plugin, Inventory inv, Player p,
                                                 List<Order> orders, Map<Integer, PixData> pixDataMap) {
        inv.clear();
        ItemStack borderPane = createFillerPane("BLACK_STAINED_GLASS_PANE");
        for (int i = 27; i < 36; i++) {
            inv.setItem(i, borderPane);
        }

        ItemStack backBtn = createSimpleItem(Material.ARROW, "\u00a7e\u2190 Voltar para a Loja",
                Arrays.asList("\u00a77Clique para abrir o menu", "\u00a77principal de produtos PIX."));
        inv.setItem(27, backBtn);

        int approvedCount = 0;
        double totalSpent = 0.0;
        for (Order ord : orders) {
            if (ord.isValidated()) {
                approvedCount++;
                totalSpent += ord.getPrice();
            }
        }

        ItemStack statsItem = createSimpleItem(Material.GOLD_INGOT, "\u00a76\u00a7lResumo da Sua Conta",
                Arrays.asList(
                        "\u00a77Jogador: \u00a7f" + p.getName(),
                        "\u00a77Compras aprovadas (recentes): \u00a7a" + approvedCount,
                        "\u00a77Total investido (recentes): \u00a7aR$ " + formatPrice(totalSpent),
                        " ",
                        "\u00a78Atualizado automaticamente em tempo real"
                ));
        inv.setItem(31, statsItem);

        ItemStack helpItem = createSimpleItem(Material.BOOK, "\u00a7b\u00a7lAjuda & Instru\u00e7\u00f5es",
                Arrays.asList(
                        "\u00a77Clique para abrir o guia",
                        "\u00a77de pagamento via PIX."
                ));
        inv.setItem(35, helpItem);

        if (orders.isEmpty()) {
            ItemStack emptyItem = createSimpleItem(Material.BARRIER, "\u00a7cNenhum pedido encontrado",
                    Arrays.asList(
                            "\u00a77Voc\u00ea ainda n\u00e3o gerou nenhum",
                            "\u00a77pedido por PIX.",
                            " ",
                            "\u00a7eClique em Voltar para ver os produtos!"
                    ));
            inv.setItem(13, emptyItem);
            return;
        }

        int maxMinutes = Math.max(1, plugin.getConfig().getInt("mapa.tempo-pagar", 30));
        boolean automaticMode = plugin.getConfig().getBoolean("automatico.ativado", true);

        int slot = 0;
        for (Order ord : orders) {
            if (slot >= 27) break;
            PixData pd = pixDataMap.get(ord.getId());
            String status = resolveOrderStatus(ord, pd, automaticMode, maxMinutes);

            Material iconMat;
            String statusBadge;
            List<String> lore = new ArrayList<>();
            lore.add("\u00a77Produto: \u00a7f" + ord.getProduct());
            lore.add("\u00a77Valor: \u00a7aR$ " + formatPrice(ord.getPrice()));
            lore.add("\u00a77Data: \u00a7f" + SHORT_DATE_FORMAT.format(Instant.ofEpochMilli(ord.getCreated().getTime())));

            switch (status) {
                case "approved":
                    iconMat = Material.EMERALD;
                    statusBadge = "\u00a7a\u00a7lAPROVADO";
                    lore.add("\u00a77Status: " + statusBadge);
                    if (ord.getTransaction() != null) {
                        lore.add("\u00a77Comprovante: \u00a78" + ord.getTransaction());
                    }
                    break;
                case "pending":
                    iconMat = Material.CLOCK;
                    statusBadge = "\u00a7e\u00a7lAGUARDANDO PAGAMENTO";
                    long elapsedMin = TimeUnit.MILLISECONDS.toMinutes(
                            Math.max(0L, System.currentTimeMillis() - ord.getCreated().getTime()));
                    long remainingMin = Math.max(1L, maxMinutes - elapsedMin);
                    lore.add("\u00a77Status: " + statusBadge);
                    lore.add("\u00a77Tempo restante: \u00a7e" + remainingMin + " min");
                    lore.add(" ");
                    lore.add("\u00a7a\u25b6 Clique esquerdo: \u00a7fReceber mapa QR Code");
                    lore.add("\u00a7c\u25b6 Shift + Clique: \u00a7fCancelar este pedido");
                    break;
                case "amount_mismatch":
                    iconMat = Material.REDSTONE;
                    statusBadge = "\u00a7c\u00a7lVALOR DIVERGENTE";
                    lore.add("\u00a77Status: " + statusBadge);
                    lore.add("\u00a7cContate a administra\u00e7\u00e3o.");
                    break;
                case "rejected":
                    iconMat = Material.RED_DYE;
                    statusBadge = "\u00a7c\u00a7lREJEITADO";
                    lore.add("\u00a77Status: " + statusBadge);
                    break;
                default:
                    iconMat = Material.RED_DYE;
                    statusBadge = "\u00a7c\u00a7lCANCELADO / EXPIRADO";
                    lore.add("\u00a77Status: " + statusBadge);
                    break;
            }

            ItemStack orderIcon = createSimpleItem(iconMat,
                    "\u00a7bPedido \u00a7f#" + ord.getId() + " \u00a78- " + statusBadge, lore);
            inv.setItem(slot++, orderIcon);
        }
    }

    public static String resolveOrderStatus(Order ord, PixData pd, boolean automaticMode, int maxMinutes) {
        if (ord.isValidated()) return "approved";
        if (automaticMode && pd != null && pd.getStatus() != null) {
            if ("pending".equalsIgnoreCase(pd.getStatus())) {
                long diff = Math.max(0L, System.currentTimeMillis() - ord.getCreated().getTime());
                if (TimeUnit.MILLISECONDS.toMinutes(diff) >= maxMinutes) {
                    return "cancelled";
                }
            }
            return pd.getStatus().toLowerCase(Locale.ROOT);
        }
        long diff = Math.max(0L, System.currentTimeMillis() - ord.getCreated().getTime());
        if (TimeUnit.MILLISECONDS.toMinutes(diff) >= maxMinutes) {
            return "cancelled";
        }
        return "pending";
    }

    private static ItemStack buildProductSummaryItem(OrderProduct product, float finalPrice,
                                                     String appliedCoupon, int discountPercent) {
        ItemStack base = product.getIcon() != null ? product.getIcon().clone() : new ItemStack(Material.DIAMOND);
        ItemMeta meta = base.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("\u00a7e\u00a7lResumo do Pedido: \u00a7f" + product.getProduct());
            List<String> lore = new ArrayList<>();
            lore.add("\u00a77Produto: \u00a7f" + product.getProduct());
            if (appliedCoupon != null && discountPercent > 0) {
                lore.add("\u00a77Pre\u00e7o original: \u00a7c\u00a7mR$ " + formatPrice(product.getPrice()));
                lore.add("\u00a77Cupom aplicado: \u00a7b" + appliedCoupon + " \u00a7a(-" + discountPercent + "%)");
                lore.add("\u00a77Valor final PIX: \u00a7a\u00a7lR$ " + formatPrice(finalPrice));
            } else {
                lore.add("\u00a77Valor a pagar: \u00a7a\u00a7lR$ " + formatPrice(finalPrice));
            }
            AutoPix plugin = AutoPix.getInstance();
            int payMinutes = plugin != null ? plugin.getConfig().getInt("mapa.tempo-pagar", 30) : 30;
            lore.add("\u00a77Prazo de pagamento: \u00a7f" + payMinutes + " minutos");
            lore.add(" ");
            lore.add("\u00a7eClique em \u00a7aConfirmar \u00a7epara gerar o QR Code!");
            meta.setLore(lore);
            base.setItemMeta(meta);
        }
        return base;
    }

    private static ItemStack buildDiscountButton(ItemStack item, String appliedCoupon, int discountPercent) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && appliedCoupon != null && discountPercent > 0) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(" ");
            lore.add("\u00a7a\u2714 Cupom ativo: \u00a7f" + appliedCoupon + " (-" + discountPercent + "%)");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack buildConfirmButton(ItemStack item, OrderProduct product, float finalPrice) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && product != null) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(" ");
            lore.add("\u00a77Produto: \u00a7f" + product.getProduct());
            lore.add("\u00a77Total: \u00a7aR$ " + formatPrice(finalPrice));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @SuppressWarnings("deprecation")
    public static void openInfo(Player p) {
        if (p == null || INFO == null) return;
        SoundManager.play(p, SoundManager.OPEN_MENU);
        try {
            Player.class.getMethod("openBook", ItemStack.class);
            p.openBook(INFO);
        } catch (NoSuchMethodException e) {
            if (p.getInventory().getItemInHand().getType() == Material.AIR) {
                p.getInventory().setItemInHand(INFO);
            } else {
                MSG.sendMessage(p, "mao-vazia");
            }
        }
    }

    public static String getMenuByTitle(String title) {
        if (title == null) return null;
        return MENUS_TITLES.get(title);
    }

    public static String getMapTitle() {
        return mapTitle;
    }

    public static String getConfirmTitle() {
        return confirmTitle;
    }

    public static String getHistoryTitle() {
        return historyTitle;
    }

    public static int getCancelSlot() {
        return cancelSlot;
    }

    public static int getConfirmSlot() {
        return confirmSlot;
    }

    public static int getDiscountSlot() {
        return discountSlot;
    }

    public static int getHistoryButtonSlot() {
        return historyButtonSlot;
    }

    public static boolean isCartButton(String menu, int slot) {
        if (menu == null) return false;
        Integer configured = CART_BUTTON_SLOTS.get(menu.toLowerCase(Locale.ROOT));
        return configured != null && configured == slot;
    }

    public static void openCartMenu(Player p) {
        if (p == null || !p.isOnline()) return;
        AutoPix plugin = AutoPix.getInstance();
        if (plugin == null || !plugin.isEnabled()) return;

        String title = "§8§lCarrinho de compras";
        AutoPixInventoryHolder holder = new AutoPixInventoryHolder(
                AutoPixInventoryHolder.MenuType.CART, "carrinho", p.getName());
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        if (plugin.getConfig().getBoolean("menu.carrinho.preencher-fundo", true)) {
            fillEmptySlots(inv, createFillerPane("BLACK_STAINED_GLASS_PANE"));
        }

        List<CartItem> items = CartManager.getItems(p);
        if (items.isEmpty()) {
            inv.setItem(22, createSimpleItem(Material.BARRIER, "§cCarrinho vazio",
                    Arrays.asList("§7Volte à loja e clique nos", "§7produtos que deseja comprar.")));
        } else {
            int slot = 0;
            for (CartItem cartItem : items) {
                if (slot >= 45) break;
                ItemStack display = cartItem.getIcon();
                if (display == null) display = new ItemStack(Material.CHEST);
                display.setAmount(Math.max(1, Math.min(display.getMaxStackSize(), cartItem.getQuantity())));
                ItemMeta meta = display.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore()
                            ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    lore.add(" ");
                    lore.add("§7Quantidade: §f" + cartItem.getQuantity());
                    lore.add("§7Valor unitário: §fR$ " + formatPrice(cartItem.getUnitPrice()));
                    lore.add("§7Subtotal: §aR$ " + formatPrice(cartItem.getLineTotal()));
                    lore.add(" ");
                    lore.add("§aClique esquerdo: §f+1");
                    lore.add("§eClique direito: §f-1");
                    lore.add("§cShift + direito: §fRemover");
                    meta.setLore(lore);
                    display.setItemMeta(meta);
                }
                inv.setItem(slot, display);
                holder.setCartEntry(slot, cartItem.getKey());
                slot++;
            }
        }

        inv.setItem(45, createSimpleItem(Material.ARROW, "§eVoltar à loja",
                Collections.singletonList("§7Retornar aos produtos.")));
        inv.setItem(47, createSimpleItem(Material.NAME_TAG, "§bAplicar cupom",
                Collections.singletonList("§7Digite o cupom no chat.")));
        inv.setItem(49, createSimpleItem(Material.BARRIER, "§cLimpar carrinho",
                Collections.singletonList("§7Remove todos os itens.")));
        List<String> totalLore = new ArrayList<>();
        totalLore.add("§7Itens: §f" + CartManager.getItemCount(p));
        totalLore.add("§7Subtotal: §fR$ " + formatPrice(CartManager.getSubtotal(p).doubleValue()));
        if (CartManager.getDiscountPercent(p) > 0) {
            totalLore.add("§7Cupom: §a" + CartManager.getCouponName(p)
                    + " (-" + CartManager.getDiscountPercent(p) + "%)");
        }
        totalLore.add("§7Total: §a§lR$ " + formatPrice(CartManager.getTotal(p).doubleValue()));
        inv.setItem(50, createSimpleItem(Material.GOLD_INGOT, "§6§lResumo da compra", totalLore));
        inv.setItem(53, createSimpleItem(Material.EMERALD_BLOCK, "§a§lFinalizar compra",
                Arrays.asList("§7Gerar um único PIX para", "§7todos os itens do carrinho.", " ",
                        "§eClique para continuar.")));

        p.openInventory(inv);
        SoundManager.play(p, SoundManager.OPEN_MENU);
    }

    public static OrderProduct getOrderProduct(String menu, int slot) {
        if (menu == null) return null;
        Map<Integer, OrderProduct> map = PRODUCTS.get(menu.toLowerCase(Locale.ROOT));
        return map != null ? map.get(slot) : null;
    }

    public static OrderProduct getProductByOrder(Order order) {
        if (order == null || order.getProduct() == null) return null;
        for (Map<Integer, OrderProduct> map : PRODUCTS.values()) {
            for (OrderProduct op : map.values()) {
                if (op.getProduct().equalsIgnoreCase(order.getProduct())) {
                    return op;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static ItemStack loadItem(FileConfiguration fc, String path, float price) {
        String rawMat = fc.getString(path + ".material", "STONE");
        int data = fc.getInt(path + ".data", 0);
        Material material = resolveMaterial(rawMat, data);
        ItemStack item = new ItemStack(material);

        String formattedPrice = formatPrice(price);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String rawName = fc.getString(path + ".nome", "");
            meta.setDisplayName(MSG.colorize(rawName).replace("{preco}", formattedPrice));

            if (fc.isList(path + ".descricao")) {
                List<String> lore = new ArrayList<>();
                for (String line : fc.getStringList(path + ".descricao")) {
                    lore.add(MSG.colorize(line).replace("{preco}", formattedPrice));
                }
                meta.setLore(lore);
            }

            try {
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            } catch (IllegalArgumentException ignored) {
            }

            item.setItemMeta(meta);
        }

        if (material == Material.PLAYER_HEAD) {
            String texture = fc.getString(path + ".textura", null);
            if (texture != null && !texture.trim().isEmpty()) {
                applyCustomHead(item, texture.trim());
            }
        }

        return item;
    }

    public static Material resolveMaterial(String rawName, int legacyData) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return Material.STONE;
        }
        String upper = rawName.trim().toUpperCase(Locale.ROOT);

        if (upper.equals("WOOL")) {
            return resolveColoredMaterial(legacyData, "_WOOL", Material.WHITE_WOOL);
        }
        if (upper.equals("STAINED_GLASS_PANE") || upper.equals("THIN_GLASS")) {
            return resolveColoredMaterial(legacyData, "_STAINED_GLASS_PANE", Material.GRAY_STAINED_GLASS_PANE);
        }
        if (upper.equals("STAINED_CLAY") || upper.equals("HARD_CLAY")) {
            return resolveColoredMaterial(legacyData, "_TERRACOTTA", Material.TERRACOTTA);
        }
        if (upper.equals("SKULL_ITEM") || upper.equals("SKULL")) {
            return Material.PLAYER_HEAD;
        }
        if (upper.equals("BOOK_AND_QUILL")) {
            return Material.WRITABLE_BOOK;
        }

        Material direct = Material.getMaterial(upper);
        if (direct != null) return direct;

        Material matched = Material.matchMaterial(upper);
        return matched != null ? matched : Material.STONE;
    }

    private static Material resolveColoredMaterial(int data, String suffix, Material fallback) {
        String colorPrefix;
        switch (data) {
            case 0: colorPrefix = "WHITE"; break;
            case 1: colorPrefix = "ORANGE"; break;
            case 2: colorPrefix = "MAGENTA"; break;
            case 3: colorPrefix = "LIGHT_BLUE"; break;
            case 4: colorPrefix = "YELLOW"; break;
            case 5: colorPrefix = "LIME"; break;
            case 6: colorPrefix = "PINK"; break;
            case 7: colorPrefix = "GRAY"; break;
            case 8: colorPrefix = "LIGHT_GRAY"; break;
            case 9: colorPrefix = "CYAN"; break;
            case 10: colorPrefix = "PURPLE"; break;
            case 11: colorPrefix = "BLUE"; break;
            case 12: colorPrefix = "BROWN"; break;
            case 13: colorPrefix = "GREEN"; break;
            case 14: colorPrefix = "RED"; break;
            case 15: colorPrefix = "BLACK"; break;
            default: colorPrefix = "WHITE"; break;
        }
        Material resolved = Material.getMaterial(colorPrefix + suffix);
        return resolved != null ? resolved : fallback;
    }

    private static ItemStack createFillerPane(String materialName) {
        Material mat = resolveMaterial(materialName, 7);
        return createSimpleItem(mat, " ", Collections.emptyList());
    }

    private static ItemStack createSimpleItem(Material material, String displayName, List<String> lore) {
        ItemStack item = new ItemStack(material != null ? material : Material.STONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore);
            }
            try {
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            } catch (Exception ignored) {
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public static void removeUnpaidMaps(Player p) {
        if (p == null) return;
        Runnable task = () -> {
            if (!p.isOnline()) return;
            ItemStack[] contents = p.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                if (isPaymentMap(contents[i])) {
                    p.getInventory().setItem(i, null);
                }
            }
        };
        if (Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            AutoPix plugin = AutoPix.getInstance();
            if (plugin != null && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        }
    }

    public static int findPaymentMapSlot(Player p) {
        if (p == null) return -1;
        int heldSlot = p.getInventory().getHeldItemSlot();
        if (isPaymentMap(p.getInventory().getItem(heldSlot))) {
            return heldSlot;
        }
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isPaymentMap(contents[i])) {
                return i;
            }
        }
        return -1;
    }

    public static boolean deliverPaymentMapSafely(Player p, ItemStack mapItem) {
        if (p == null || mapItem == null) return false;
        int existingMapSlot = findPaymentMapSlot(p);
        if (existingMapSlot >= 0) {
            p.getInventory().setItem(existingMapSlot, mapItem);
            if (existingMapSlot < 9) {
                p.getInventory().setHeldItemSlot(existingMapSlot);
            }
            return true;
        }

        int heldSlot = p.getInventory().getHeldItemSlot();
        ItemStack inHand = p.getInventory().getItem(heldSlot);
        if (inHand == null || inHand.getType() == Material.AIR) {
            p.getInventory().setItem(heldSlot, mapItem);
            return true;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack slotItem = p.getInventory().getItem(i);
            if (slotItem == null || slotItem.getType() == Material.AIR) {
                p.getInventory().setItem(i, mapItem);
                p.getInventory().setHeldItemSlot(i);
                return true;
            }
        }

        int firstEmpty = p.getInventory().firstEmpty();
        if (firstEmpty >= 0) {
            p.getInventory().setItem(firstEmpty, mapItem);
            return true;
        }
        return false;
    }

    public static void restorePendingMapToPlayer(Player p, Order order, PixData pd) {
        if (p == null || order == null || pd == null || pd.getQrCode() == null) return;
        AutoPix plugin = AutoPix.getInstance();
        if (plugin == null) return;

        List<OrderItem> savedItems = OrderManager.getOrderItems(order.getId());
        OrderProduct op = savedItems.isEmpty()
                ? getProductByOrder(order)
                : new OrderProduct(order.getProduct(), order.getPrice(), null,
                        Collections.emptyList(), Collections.emptyList());
        if (op == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BufferedImage qr = ImageCreator.generateQR(pd.getQrCode());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) return;
                    ItemStack map = ImageCreator.generateMap(qr, p, op);
                    if (deliverPaymentMapSafely(p, map)) {
                        SoundManager.play(p, SoundManager.GENERATE_QR);
                    } else {
                        MSG.sendMessage(p, "mao-vazia");
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Erro ao restaurar mapa QR para " + p.getName() + ": " + e.getMessage());
            }
        });
    }

    public static boolean isPaymentMap(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                && item.getItemMeta().getDisplayName().equals(mapTitle);
    }

    public static void updateMapMeta(AutoPix ap, MapMeta meta, Player p, OrderProduct op) {
        meta.setDisplayName(mapTitle);

        List<String> lore = new ArrayList<>();
        if (op != null && ap != null) {
            String formattedDate = MAP_DATE_FORMAT.format(Instant.now());
            for (String line : ap.getConfig().getStringList("mapa.descricao")) {
                lore.add(MSG.colorize(line)
                        .replace("{jogador}", p.getName())
                        .replace("{produto}", op.getProduct())
                        .replace("{preco}", formatPrice(op.getPrice()))
                        .replace("{data}", formattedDate));
            }
        }

        meta.setLore(lore);
    }

    private static void fillEmptySlots(Inventory inv, ItemStack filler) {
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) {
                inv.setItem(i, filler.clone());
            }
        }
    }

    private static int normalizeInventorySize(int rawSize, int fallback) {
        if (rawSize < 9 || rawSize > 54 || rawSize % 9 != 0) {
            return fallback;
        }
        return rawSize;
    }

    private static int clampSlot(int slot, int maxSize, int fallback) {
        if (slot < 0 || slot >= maxSize) {
            return Math.min(fallback, maxSize - 1);
        }
        return slot;
    }

    private static String formatPrice(double price) {
        return String.format(Locale.ROOT, "%.2f", price).replace('.', ',');
    }

    private static void applyCustomHead(ItemStack head, String base64Texture) {
        String textureUrl = getTextureUrlFromBase64(base64Texture);
        if (textureUrl == null) return;

        PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return;
        PlayerTextures textures = profile.getTextures();

        try {
            textures.setSkin(URI.create(textureUrl).toURL());
        } catch (MalformedURLException | IllegalArgumentException e) {
            return;
        }

        profile.setTextures(textures);
        meta.setOwnerProfile(profile);
        head.setItemMeta(meta);
    }

    public static String getTextureUrlFromBase64(String base64) {
        try {
            String decoded = new String(Base64.getDecoder().decode(base64));
            JSONParser parser = new JSONParser();
            JSONObject json = (JSONObject) parser.parse(decoded);
            JSONObject textures = (JSONObject) json.get("textures");
            JSONObject skin = (JSONObject) textures.get("SKIN");
            return (String) skin.get("url");
        } catch (Exception e) {
            return null;
        }
    }
}
