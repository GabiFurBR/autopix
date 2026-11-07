package io.github.warleysr.autopix.catalog;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import io.github.warleysr.autopix.AutoPix;

public final class CatalogManager {

    public static final List<String> REQUIRED_CATEGORIES = List.of(
            "donations",
            "vips",
            "money",
            "gems",
            "event_coins",
            "stars"
    );

    public static final class CatalogSnapshot {
        private final int version;
        private final Map<String, Category> categories;
        private final Map<String, Product> products;
        private final CustomDonationConfig customDonation;

        public CatalogSnapshot(int version, Map<String, Category> categories,
                               Map<String, Product> products, CustomDonationConfig customDonation) {
            this.version = version;
            this.categories = Collections.unmodifiableMap(new LinkedHashMap<>(categories));
            this.products = Collections.unmodifiableMap(new LinkedHashMap<>(products));
            this.customDonation = customDonation != null
                    ? customDonation
                    : new CustomDonationConfig(false, new BigDecimal("1.00"), new BigDecimal("1000.00"), true, null);
        }

        public int getVersion() {
            return version;
        }

        public Map<String, Category> getCategories() {
            return categories;
        }

        public Map<String, Product> getProducts() {
            return products;
        }

        public CustomDonationConfig getCustomDonation() {
            return customDonation;
        }
    }

    private static volatile CatalogSnapshot currentSnapshot =
            new CatalogSnapshot(1, Collections.emptyMap(), Collections.emptyMap(), null);

    private CatalogManager() {
    }

    public static synchronized boolean load(AutoPix plugin) {
        if (plugin == null) return false;

        File catalogFile = new File(plugin.getDataFolder(), "catalog.yml");
        if (!catalogFile.exists()) {
            try {
                plugin.saveResource("catalog.yml", false);
            } catch (Exception ignored) {
            }
        }

        FileConfiguration config;
        if (catalogFile.exists()) {
            config = YamlConfiguration.loadConfiguration(catalogFile);
        } else {
            config = plugin.getConfig();
        }

        ConfigurationSection root = config.isConfigurationSection("catalog")
                ? config.getConfigurationSection("catalog")
                : config;

        try {
            CatalogSnapshot candidate = parseAndValidate(root, plugin.getConfig());
            currentSnapshot = candidate;
            plugin.getLogger().info("Catalogo oficial carregado: "
                    + candidate.getProducts().size() + " produtos em "
                    + candidate.getCategories().size() + " categorias.");
            return true;
        } catch (CatalogValidationException e) {
            plugin.getLogger().severe("Erro na validacao do catalogo YAML: " + e.getMessage());
            plugin.getLogger().severe("O catalogo anterior foi mantido ativo por seguranca.");
            return false;
        } catch (Exception e) {
            plugin.getLogger().severe("Falha inesperada ao ler catalogo YAML: " + e.getMessage());
            return false;
        }
    }

    public static CatalogSnapshot parseAndValidate(ConfigurationSection catalogSection, FileConfiguration fallbackConfig)
            throws CatalogValidationException {
        return parseAndValidate(catalogSection, fallbackConfig, true);
    }

    public static CatalogSnapshot parseAndValidate(ConfigurationSection catalogSection,
                                                   FileConfiguration fallbackConfig,
                                                   boolean enforceRequiredCategories)
            throws CatalogValidationException {
        int version = catalogSection != null ? catalogSection.getInt("version", 1) : 1;

        Map<String, Category> categories = new LinkedHashMap<>();
        Map<String, Product> products = new LinkedHashMap<>();

        ConfigurationSection catSection = catalogSection != null
                ? catalogSection.getConfigurationSection("categories")
                : null;
        if (catSection != null) {
            for (String catKey : catSection.getKeys(false)) {
                String catId = catKey.toLowerCase(Locale.ROOT).trim();
                if (categories.containsKey(catId)) {
                    throw new CatalogValidationException("Categoria duplicada encontrada: " + catId);
                }
                ConfigurationSection cs = catSection.getConfigurationSection(catKey);
                boolean enabled = cs == null || cs.getBoolean("enabled", true);
                String name = cs != null ? cs.getString("name", catKey) : catKey;
                List<String> productList = cs != null ? cs.getStringList("products") : Collections.emptyList();
                categories.put(catId, new Category(catId, name, enabled, productList));
            }

            if (enforceRequiredCategories) {
                for (String reqCat : REQUIRED_CATEGORIES) {
                    if (!categories.containsKey(reqCat)) {
                        throw new CatalogValidationException("Categoria obrigatoria ausente no catalogo: " + reqCat);
                    }
                }
            }
        }

        ConfigurationSection prodSection = catalogSection != null
                ? catalogSection.getConfigurationSection("products")
                : null;
        if (prodSection != null) {
            for (String prodKey : prodSection.getKeys(false)) {
                String prodId = prodKey.toLowerCase(Locale.ROOT).trim();
                if (products.containsKey(prodId)) {
                    throw new CatalogValidationException("Produto com ID duplicado no catalogo: " + prodId);
                }
                ConfigurationSection ps = prodSection.getConfigurationSection(prodKey);
                if (ps == null) continue;

                boolean enabled = ps.getBoolean("enabled", true);
                String name = ps.getString("name", prodKey);
                String description = ps.getString("description", null);
                String categoryId = ps.getString("category", "general").toLowerCase(Locale.ROOT).trim();

                if (!categories.isEmpty() && !categories.containsKey(categoryId)) {
                    throw new CatalogValidationException("Produto '" + prodId
                            + "' referencia categoria inexistente: '" + categoryId + "'.");
                }

                BigDecimal defaultPrice = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                if (ps.isSet("price")) {
                    String rawPrice = ps.getString("price");
                    BigDecimal parsedPrice;
                    try {
                        parsedPrice = rawPrice != null
                                ? new BigDecimal(rawPrice.trim()).setScale(2, RoundingMode.HALF_UP)
                                : BigDecimal.valueOf(ps.getDouble("price")).setScale(2, RoundingMode.HALF_UP);
                    } catch (NumberFormatException ex) {
                        throw new CatalogValidationException("Preco em formato invalido no produto " + prodId + ": " + rawPrice);
                    }
                    if (parsedPrice.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new CatalogValidationException("Preco deve ser positivo no produto " + prodId + ": " + parsedPrice);
                    }
                    defaultPrice = parsedPrice;
                }

                Map<String, Offer> offers = new LinkedHashMap<>();
                ConfigurationSection offersSection = ps.getConfigurationSection("offers");
                if (offersSection != null) {
                    for (String offerKey : offersSection.getKeys(false)) {
                        String offerId = offerKey.toLowerCase(Locale.ROOT).trim();
                        if (offers.containsKey(offerId)) {
                            throw new CatalogValidationException("Oferta duplicada '" + offerId + "' no produto " + prodId);
                        }
                        ConfigurationSection os = offersSection.getConfigurationSection(offerKey);
                        if (os == null) continue;

                        String rawType = os.getString("type", "one_time").toUpperCase(Locale.ROOT).trim();
                        OfferType type;
                        try {
                            type = OfferType.valueOf(rawType);
                        } catch (IllegalArgumentException e) {
                            throw new CatalogValidationException("Tipo de oferta invalido no produto "
                                    + prodId + ", oferta " + offerId + ": " + rawType);
                        }

                        String rawOfferPrice = os.getString("price");
                        BigDecimal bdPrice;
                        try {
                            bdPrice = rawOfferPrice != null
                                    ? new BigDecimal(rawOfferPrice.trim()).setScale(2, RoundingMode.HALF_UP)
                                    : BigDecimal.valueOf(os.getDouble("price", -1.0)).setScale(2, RoundingMode.HALF_UP);
                        } catch (NumberFormatException ex) {
                            throw new CatalogValidationException("Preco em formato invalido na oferta "
                                    + offerId + " do produto " + prodId + ": " + rawOfferPrice);
                        }
                        if (bdPrice.compareTo(BigDecimal.ZERO) <= 0) {
                            throw new CatalogValidationException("Preco invalido na oferta "
                                    + offerId + " do produto " + prodId + ": " + bdPrice);
                        }

                        String duration = os.getString("duration", null);
                        boolean offerEnabled = os.getBoolean("enabled", true);
                        Offer offer = new Offer(offerId, prodId, type, bdPrice, duration, offerEnabled);

                        if (duration != null && !duration.trim().isEmpty()) {
                            if (offer.getDuration() == null || offer.getDuration().isZero() || offer.getDuration().isNegative()) {
                                throw new CatalogValidationException("Duracao invalida na oferta "
                                        + offerId + " do produto " + prodId + ": " + duration);
                            }
                        } else if (type == OfferType.SUBSCRIPTION) {
                            throw new CatalogValidationException("Oferta de assinatura '" + offerId
                                    + "' no produto '" + prodId + "' exige duracao configurada.");
                        }

                        offers.put(offerId, offer);
                    }
                }

                if (enabled && offers.isEmpty() && defaultPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new CatalogValidationException("Produto ativo '" + prodId
                            + "' nao possui preco positivo nem ofertas configuradas.");
                }

                DeliveryConfig delivery = parseDeliveryConfig(prodId, ps.getConfigurationSection("delivery"));
                products.put(prodId, new Product(prodId, name, description, categoryId, enabled, defaultPrice, offers.values(), delivery));
            }
        }

        // Validate category -> product references
        for (Category category : categories.values()) {
            for (String refProd : category.getProductIds()) {
                String normProd = refProd != null ? refProd.toLowerCase(Locale.ROOT).trim() : "";
                Product p = products.get(normProd);
                if (p == null) {
                    throw new CatalogValidationException("A categoria '" + category.getId()
                            + "' referencia um produto inexistente: '" + refProd + "'.");
                }
            }
        }

        // Legacy fallback if catalog has no products
        if (products.isEmpty() && fallbackConfig != null && fallbackConfig.isConfigurationSection("menu")) {
            ConfigurationSection menuRoot = fallbackConfig.getConfigurationSection("menu");
            for (String menuKey : menuRoot.getKeys(false)) {
                ConfigurationSection menuSec = menuRoot.getConfigurationSection(menuKey);
                if (menuSec == null || !menuSec.isConfigurationSection("produtos")) continue;

                ConfigurationSection pSec = menuSec.getConfigurationSection("produtos");
                for (String pKey : pSec.getKeys(false)) {
                    String prodId = pKey.toLowerCase(Locale.ROOT).trim();
                    if (products.containsKey(prodId)) continue;

                    ConfigurationSection pData = pSec.getConfigurationSection(pKey);
                    double price = pData != null ? pData.getDouble("preco", 0.0) : 0.0;
                    if (price <= 0.0) continue;
                    List<String> preCmds = pData != null ? pData.getStringList("pre-comandos") : Collections.emptyList();
                    List<String> cmds = pData != null ? pData.getStringList("comandos") : Collections.emptyList();

                    DeliveryConfig legacyDel = new DeliveryConfig(DeliveryType.AUTOPIX, preCmds, cmds, null);
                    BigDecimal bdPrice = BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);

                    Product legacyProd = new Product(prodId, pKey, null, menuKey, true, bdPrice, Collections.emptyList(), legacyDel);
                    products.put(prodId, legacyProd);
                }
            }
        }

        CustomDonationConfig donationConfig = parseCustomDonation(catalogSection != null
                ? catalogSection.getConfigurationSection("custom_donation")
                : (fallbackConfig != null ? fallbackConfig.getConfigurationSection("custom_donation") : null));

        return new CatalogSnapshot(version, categories, products, donationConfig);
    }

    private static DeliveryConfig parseDeliveryConfig(String prodId, ConfigurationSection sec) throws CatalogValidationException {
        if (sec == null) {
            throw new CatalogValidationException("Configuracao 'delivery' ausente no produto: " + prodId);
        }
        String typeStr = sec.getString("type", "autopix").toUpperCase(Locale.ROOT).trim();
        DeliveryType type;
        try {
            type = DeliveryType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            throw new CatalogValidationException("Tipo de entrega desconhecido no produto " + prodId + ": " + typeStr);
        }

        List<String> preCommands = new ArrayList<>(sec.getStringList("pre_commands"));
        List<String> commands = new ArrayList<>();
        String handler = null;

        if (type == DeliveryType.AUTOPIX) {
            commands.addAll(sec.getStringList("commands"));
            if (commands.isEmpty()) {
                throw new CatalogValidationException("Delivery do tipo AUTOPIX no produto '" + prodId
                        + "' exige pelo menos um comando em 'commands'.");
            }
        } else if (type == DeliveryType.SKRIPT) {
            handler = sec.getString("handler");
            if (handler == null || handler.trim().isEmpty()) {
                throw new CatalogValidationException("Delivery do tipo SKRIPT no produto '" + prodId
                        + "' exige 'handler' configurado.");
            }
        } else if (type == DeliveryType.HYBRID) {
            if (sec.isConfigurationSection("autopix")) {
                commands.addAll(sec.getStringList("autopix.commands"));
            } else {
                commands.addAll(sec.getStringList("commands"));
            }
            if (sec.isConfigurationSection("skript")) {
                handler = sec.getString("skript.handler");
            } else {
                handler = sec.getString("handler");
            }
            if (commands.isEmpty()) {
                throw new CatalogValidationException("Delivery do tipo HYBRID no produto '" + prodId
                        + "' exige comandos configurados em 'autopix.commands' ou 'commands'.");
            }
            if (handler == null || handler.trim().isEmpty()) {
                throw new CatalogValidationException("Delivery do tipo HYBRID no produto '" + prodId
                        + "' exige 'skript.handler' ou 'handler'.");
            }
        }

        return new DeliveryConfig(type, preCommands, commands, handler);
    }

    private static CustomDonationConfig parseCustomDonation(ConfigurationSection sec) throws CatalogValidationException {
        if (sec == null) {
            return new CustomDonationConfig(false, new BigDecimal("1.00"), new BigDecimal("1000.00"), true, null);
        }
        boolean enabled = sec.getBoolean("enabled", false);
        BigDecimal min = BigDecimal.valueOf(sec.getDouble("minimum", 1.00)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal max = BigDecimal.valueOf(sec.getDouble("maximum", 1000.00)).setScale(2, RoundingMode.HALF_UP);
        if (min.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CatalogValidationException("O valor minimo de custom_donation deve ser maior que zero: " + min);
        }
        if (max.compareTo(min) < 0) {
            throw new CatalogValidationException("O valor maximo de custom_donation (" + max
                    + ") nao pode ser menor que o minimo (" + min + ").");
        }
        boolean allowDecimal = sec.getBoolean("allow_decimal", true);
        String reward = sec.getString("reward", null);
        return new CustomDonationConfig(enabled, min, max, allowDecimal, reward);
    }

    public static Product getProduct(String id) {
        if (id == null) return null;
        return currentSnapshot.getProducts().get(id.toLowerCase(Locale.ROOT).trim());
    }

    public static Collection<Product> getProducts() {
        return currentSnapshot.getProducts().values();
    }

    public static List<Product> getProductsByCategory(String categoryId) {
        if (categoryId == null || categoryId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String normCat = categoryId.toLowerCase(Locale.ROOT).trim();
        CatalogSnapshot snap = currentSnapshot;
        Category cat = snap.getCategories().get(normCat);

        Set<String> seen = new LinkedHashSet<>();
        List<Product> result = new ArrayList<>();

        if (cat != null) {
            for (String prodId : cat.getProductIds()) {
                if (prodId == null) continue;
                String normProd = prodId.toLowerCase(Locale.ROOT).trim();
                Product p = snap.getProducts().get(normProd);
                if (p != null && seen.add(p.getId().toLowerCase(Locale.ROOT))) {
                    result.add(p);
                }
            }
        }

        for (Product p : snap.getProducts().values()) {
            if (normCat.equalsIgnoreCase(p.getCategory()) && seen.add(p.getId().toLowerCase(Locale.ROOT))) {
                result.add(p);
            }
        }

        return Collections.unmodifiableList(result);
    }

    public static Category getCategory(String id) {
        if (id == null) return null;
        return currentSnapshot.getCategories().get(id.toLowerCase(Locale.ROOT).trim());
    }

    public static Collection<Category> getCategories() {
        return currentSnapshot.getCategories().values();
    }

    public static CustomDonationConfig getCustomDonationConfig() {
        return currentSnapshot.getCustomDonation();
    }

    public static CatalogSnapshot getSnapshot() {
        return currentSnapshot;
    }

    public static void setSnapshotForTesting(CatalogSnapshot snapshot) {
        currentSnapshot = snapshot != null
                ? snapshot
                : new CatalogSnapshot(1, Collections.emptyMap(), Collections.emptyMap(), null);
    }

    public static void registerProductForTesting(Product product) {
        if (product == null) return;
        Map<String, Product> newProducts = new LinkedHashMap<>(currentSnapshot.getProducts());
        newProducts.put(product.getId().toLowerCase(Locale.ROOT).trim(), product);
        currentSnapshot = new CatalogSnapshot(
                currentSnapshot.getVersion(),
                currentSnapshot.getCategories(),
                newProducts,
                currentSnapshot.getCustomDonation());
    }

    public static final class CatalogValidationException extends Exception {
        public CatalogValidationException(String message) {
            super(message);
        }
    }
}
