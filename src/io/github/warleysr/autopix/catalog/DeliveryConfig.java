package io.github.warleysr.autopix.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DeliveryConfig {

    private final DeliveryType type;
    private final List<String> preCommands;
    private final List<String> commands;
    private final String handler;

    public DeliveryConfig(DeliveryType type, List<String> commands, String handler) {
        this(type, Collections.emptyList(), commands, handler);
    }

    public DeliveryConfig(DeliveryType type, List<String> preCommands, List<String> commands, String handler) {
        this.type = type != null ? type : DeliveryType.AUTOPIX;
        this.preCommands = preCommands != null ? Collections.unmodifiableList(new ArrayList<>(preCommands)) : Collections.emptyList();
        this.commands = commands != null ? Collections.unmodifiableList(new ArrayList<>(commands)) : Collections.emptyList();
        this.handler = handler != null && !handler.trim().isEmpty() ? handler.trim() : null;
    }

    public DeliveryType getType() {
        return type;
    }

    public List<String> getPreCommands() {
        return preCommands;
    }

    public List<String> getCommands() {
        return commands;
    }

    public String getHandler() {
        return handler;
    }
}
