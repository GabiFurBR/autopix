package io.github.warleysr.autopix.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Category {

    private final String id;
    private final String name;
    private final boolean enabled;
    private final List<String> productIds;

    public Category(String id, String name, boolean enabled, List<String> productIds) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Category id cannot be null or empty.");
        }
        this.id = id.trim();
        this.name = name != null && !name.trim().isEmpty() ? name.trim() : id;
        this.enabled = enabled;
        this.productIds = productIds != null ? Collections.unmodifiableList(new ArrayList<>(productIds)) : Collections.emptyList();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> getProductIds() {
        return productIds;
    }
}
