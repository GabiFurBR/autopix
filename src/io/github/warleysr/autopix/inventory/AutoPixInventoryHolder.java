package io.github.warleysr.autopix.inventory;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class AutoPixInventoryHolder implements InventoryHolder {

    public enum MenuType {
        SHOP,
        CONFIRM,
        HISTORY
    }

    private final MenuType menuType;
    private final String menuName;
    private final String ownerName;
    private Inventory inventory;

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

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
