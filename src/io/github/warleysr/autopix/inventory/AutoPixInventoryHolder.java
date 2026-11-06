package io.github.warleysr.autopix.inventory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class AutoPixInventoryHolder implements InventoryHolder {

    public enum MenuType {
        SHOP,
        CART,
        CONFIRM,
        HISTORY
    }

    private final MenuType menuType;
    private final String menuName;
    private final String ownerName;
    private Inventory inventory;
    private final Map<Integer, String> cartEntries = new ConcurrentHashMap<>();

    public AutoPixInventoryHolder(MenuType menuType, String menuName, String ownerName) {
        this.menuType = menuType;
        this.menuName = menuName;
        this.ownerName = ownerName;
    }

    public MenuType getMenuType() {
        return menuType;
    }

    public String getMenuName() {
        return menuName;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setCartEntry(int slot, String key) {
        if (key != null) cartEntries.put(slot, key);
    }

    public String getCartEntry(int slot) {
        return cartEntries.get(slot);
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
