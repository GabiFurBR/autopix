package io.github.warleysr.autopix.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class Product {

    private final String id;
    private final String name;
    private final String description;
    private final String category;
    private final boolean enabled;
    private final BigDecimal price;
    private final Map<String, Offer> offers;
    private final DeliveryConfig delivery;

    public Product(String id, String name, String category, boolean enabled,
                   BigDecimal price, Collection<Offer> offers, DeliveryConfig delivery) {
        this(id, name, null, category, enabled, price, offers, delivery);
    }

    public Product(String id, String name, String description, String category, boolean enabled,
                   BigDecimal price, Collection<Offer> offers, DeliveryConfig delivery) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Product id cannot be null or empty.");
        }
        this.id = id.trim();
        this.name = name != null && !name.trim().isEmpty() ? name.trim() : this.id;
        this.description = description != null && !description.trim().isEmpty() ? description.trim() : null;
        this.category = category != null && !category.trim().isEmpty() ? category.trim() : "general";
        this.enabled = enabled;
        this.price = price != null ? price.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        Map<String, Offer> map = new LinkedHashMap<>();
        if (offers != null) {
            for (Offer offer : offers) {
                if (offer != null) {
                    map.put(offer.getId().toLowerCase(Locale.ROOT).trim(), offer);
                }
            }
        }
        this.offers = Collections.unmodifiableMap(map);
        this.delivery = delivery != null ? delivery : new DeliveryConfig(DeliveryType.AUTOPIX, Collections.emptyList(), null);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean hasOffers() {
        return !offers.isEmpty();
    }

    /**
     * Returns the direct product price if positive, or the minimum positive price
     * among enabled offers for display in category menus.
     */
    public BigDecimal getDisplayPrice() {
        if (price.compareTo(BigDecimal.ZERO) > 0) {
            return price;
        }
        BigDecimal minOfferPrice = null;
        for (Offer offer : offers.values()) {
            if (offer != null && offer.isEnabled() && offer.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                if (minOfferPrice == null || offer.getPrice().compareTo(minOfferPrice) < 0) {
                    minOfferPrice = offer.getPrice();
                }
            }
        }
        return minOfferPrice != null ? minOfferPrice : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    public Collection<Offer> getOffers() {
        return offers.values();
    }

    public Offer getOffer(String offerId) {
        if (offerId == null || offerId.trim().isEmpty()) return null;
        return offers.get(offerId.toLowerCase(Locale.ROOT).trim());
    }

    public DeliveryType getDeliveryType() {
        return delivery.getType();
    }

    public DeliveryConfig getDelivery() {
        return delivery;
    }
}
