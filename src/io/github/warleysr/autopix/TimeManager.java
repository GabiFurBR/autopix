package io.github.warleysr.autopix;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.bukkit.entity.Player;

public class TimeManager {

    private static final Map<String, Map<String, Long>> DELAY = new ConcurrentHashMap<>();

    public static boolean canExecute(AutoPix ap, Player p, String command) {
        if (ap == null || p == null || command == null) return true;

        int delay;
        switch (command) {
            case "create":
                delay = ap.getConfig().getInt("tempos.criar-pedido", 300);
                break;
            case "list":
                delay = ap.getConfig().getInt("tempos.lista", 30);
                break;
            case "validate":
                delay = ap.getConfig().getInt("tempos.validar", 60);
                break;
            case "cancel":
                delay = ap.getConfig().getInt("tempos.cancelar", 30);
                break;
            case "top":
                delay = ap.getConfig().getInt("tempos.top", 30);
                break;
            default:
                return true;
        }

        if (delay <= 0) return true;

        long now = System.currentTimeMillis();
        String playerKey = p.getName().toLowerCase(Locale.ROOT);
        Map<String, Long> map = DELAY.computeIfAbsent(playerKey, k -> new ConcurrentHashMap<>());

        long lastTime = map.getOrDefault(command, 0L);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(now - lastTime);

        if (seconds >= delay) {
            map.put(command, now);
            return true;
        }

        long remaining = Math.max(1L, delay - seconds);
        MSG.sendMessage(p, "aguarde", "{tempo}", Long.toString(remaining));
        return false;
    }

    public static void resetDelay(String playerName, String command) {
        if (playerName == null || command == null) return;
        Map<String, Long> map = DELAY.get(playerName.toLowerCase(Locale.ROOT));
        if (map != null) {
            map.remove(command);
        }
    }
}
