package io.github.warleysr.autopix.expansion;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.OfflinePlayer;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.MSG;
import io.github.warleysr.autopix.domain.DonorInfo;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class AutoPixExpansion extends PlaceholderExpansion {

    public static final class PlayerStats {
        private final int approvedOrders;
        private final double totalSpent;

        public PlayerStats(int approvedOrders, double totalSpent) {
            this.approvedOrders = approvedOrders;
            this.totalSpent = totalSpent;
        }

        public int getApprovedOrders() {
            return approvedOrders;
        }

        public double getTotalSpent() {
            return totalSpent;
        }
    }

    private static volatile List<DonorInfo> topDonors = Collections.emptyList();
    private static volatile double totalApprovedRevenue = 0.0;
    private static volatile int approvedOrdersCount = 0;
    private static volatile int pendingOrdersCount = 0;
    private static final Map<String, PlayerStats> PLAYER_STATS_CACHE = new ConcurrentHashMap<>();

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String getAuthor() {
        AutoPix plugin = AutoPix.getInstance();
        if (plugin != null && plugin.getDescription() != null && !plugin.getDescription().getAuthors().isEmpty()) {
            return String.join(", ", plugin.getDescription().getAuthors());
        }
        return "warleysr";
    }

    @Override
    public String getIdentifier() {
        return "autopix";
    }

    @Override
    public String getVersion() {
        AutoPix plugin = AutoPix.getInstance();
        if (plugin != null && plugin.getDescription() != null) {
            return plugin.getDescription().getVersion();
        }
        return "1.1.0-neon";
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) return "";
        String key = params.trim().toLowerCase(Locale.ROOT);

        switch (key) {
            case "total_arrecadado":
            case "total_revenue":
                return formatMoney(totalApprovedRevenue);
            case "total_arrecadado_formatado":
            case "total_revenue_formatted":
                return "R$ " + formatMoney(totalApprovedRevenue);
            case "pagamentos_aprovados":
            case "approved_count":
                return Integer.toString(approvedOrdersCount);
            case "pedidos_pendentes":
            case "pending_count":
                return Integer.toString(pendingOrdersCount);
            case "jogador_gasto":
            case "player_spent":
                return formatMoney(getPlayerStats(player).getTotalSpent());
            case "jogador_gasto_formatado":
            case "player_spent_formatted":
                return "R$ " + formatMoney(getPlayerStats(player).getTotalSpent());
            case "jogador_compras":
            case "player_orders":
                return Integer.toString(getPlayerStats(player).getApprovedOrders());
            default:
                break;
        }

        if (key.startsWith("top_name_")) {
            return resolveTopField(key.substring("top_name_".length()), "nome");
        }
        if (key.startsWith("top_value_formatted_")) {
            return resolveTopField(key.substring("top_value_formatted_".length()), "valor_formatado");
        }
        if (key.startsWith("top_value_")) {
            return resolveTopField(key.substring("top_value_".length()), "valor");
        }
        if (key.startsWith("top_orders_")) {
            return resolveTopField(key.substring("top_orders_".length()), "compras");
        }

        if (key.startsWith("top_")) {
            String suffix = key.substring(4);
            int underscoreIdx = suffix.indexOf('_');
            if (underscoreIdx > 0) {
                String posPart = suffix.substring(0, underscoreIdx);
                String fieldPart = suffix.substring(underscoreIdx + 1);
                return resolveTopField(posPart, fieldPart);
            }
            return resolveTopFullLine(suffix);
        }

        return super.onRequest(player, params);
    }

    private static String resolveTopFullLine(String posStr) {
        DonorInfo info = getTopDonorAt(posStr);
        if (info == null) return "";
        String template = MSG.getMessage("corpo-top");
        if (template == null || template.isEmpty()) {
            template = "\u00a7a{doador} \u00a7f- Total: \u00a7cR${total}";
        }
        return template
                .replace("{doador}", info.getDonor())
                .replace("{total}", formatMoney(info.getTotal()));
    }

    private static String resolveTopField(String posStr, String field) {
        DonorInfo info = getTopDonorAt(posStr);
        switch (field) {
            case "nome":
            case "name":
                return info != null ? info.getDonor() : "---";
            case "valor":
            case "value":
                return info != null ? formatMoney(info.getTotal()) : "0,00";
            case "valor_formatado":
            case "value_formatted":
                return "R$ " + (info != null ? formatMoney(info.getTotal()) : "0,00");
            case "compras":
            case "orders":
                return info != null ? Integer.toString(info.getOrdersCount()) : "0";
            default:
                return "";
        }
    }

    private static DonorInfo getTopDonorAt(String posStr) {
        try {
            int top = Integer.parseInt(posStr);
            List<DonorInfo> current = topDonors;
            if (current == null || top < 1 || top > current.size()) {
                return null;
            }
            return current.get(top - 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static PlayerStats getPlayerStats(OfflinePlayer player) {
        if (player == null || player.getName() == null) {
            return new PlayerStats(0, 0.0);
        }
        return PLAYER_STATS_CACHE.getOrDefault(
                player.getName().toLowerCase(Locale.ROOT),
                new PlayerStats(0, 0.0));
    }

    private static String formatMoney(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replace('.', ',');
    }

    public static void updateTopDonorsCache(List<DonorInfo> donors) {
        topDonors = donors != null ? List.copyOf(donors) : Collections.emptyList();
    }

    public static void updateGlobalStatsCache(double revenue, int approvedCount, int pendingCount) {
        totalApprovedRevenue = Math.max(0.0, revenue);
        approvedOrdersCount = Math.max(0, approvedCount);
        pendingOrdersCount = Math.max(0, pendingCount);
    }

    public static void updatePlayerStatsCache(Map<String, PlayerStats> statsMap) {
        PLAYER_STATS_CACHE.clear();
        if (statsMap != null) {
            PLAYER_STATS_CACHE.putAll(statsMap);
        }
    }

    public static void updateSinglePlayerStats(String playerName, int approvedOrders, double totalSpent) {
        if (playerName == null) return;
        PLAYER_STATS_CACHE.put(
                playerName.toLowerCase(Locale.ROOT),
                new PlayerStats(Math.max(0, approvedOrders), Math.max(0.0, totalSpent)));
    }
}
