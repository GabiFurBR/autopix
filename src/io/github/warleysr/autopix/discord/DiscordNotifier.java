package io.github.warleysr.autopix.discord;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.domain.Order;

public final class DiscordNotifier {

    private DiscordNotifier() {
    }

    public static void notifyPaymentApproved(AutoPix plugin, Order order) {
        if (plugin == null || order == null) return;

        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("discord.webhook.ativado", false)) {
            return;
        }

        String webhookUrl = cfg.getString("discord.webhook.url", "").trim();
        if (webhookUrl.isEmpty() || !webhookUrl.startsWith("https://")) {
            return;
        }

        String formattedPrice = String.format(Locale.ROOT, "%.2f", order.getPrice()).replace('.', ',');
        String username = cfg.getString("discord.webhook.username", "AutoPix");
        String title = applyPlaceholders(
                cfg.getString("discord.webhook.titulo", "Pagamento PIX Aprovado!"),
                order, formattedPrice);
        String description = applyPlaceholders(
                cfg.getString("discord.webhook.descricao",
                        "O jogador **{player}** adquiriu **{produto}** por **R$ {valor}** (Pedido `#{pedido}`)."),
                order, formattedPrice);
        int color = cfg.getInt("discord.webhook.cor", 3066993);

        Runnable task = () -> sendWebhookPayload(plugin, webhookUrl, username, title, description, color);
        if (Bukkit.getServer() != null && plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }
    }

    @SuppressWarnings("unchecked")
    public static String buildWebhookJson(String username, String title, String description, int color) {
        JSONObject embed = new JSONObject();
        embed.put("title", title);
        embed.put("description", description);
        embed.put("color", color);
        embed.put("timestamp", Instant.now().toString());

        JSONArray embeds = new JSONArray();
        embeds.add(embed);

        JSONObject root = new JSONObject();
        if (username != null && !username.trim().isEmpty()) {
            root.put("username", username.trim());
        }
        root.put("embeds", embeds);
        return root.toJSONString();
    }

    private static void sendWebhookPayload(AutoPix plugin, String webhookUrl, String username,
                                           String title, String description, int color) {
        HttpURLConnection connection = null;
        try {
            String payload = buildWebhookJson(username, title, description, color);
            connection = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("User-Agent", "AutoPix-Neon/1.1");

            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
                out.flush();
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                plugin.getLogger().warning("Webhook Discord retornou HTTP " + status + " ao notificar pedido #" + title);
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Nao foi possivel enviar notificacao ao Discord Webhook: " + exception.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String applyPlaceholders(String template, Order order, String formattedPrice) {
        if (template == null) return "";
        return template
                .replace("{player}", order.getPlayer())
                .replace("{jogador}", order.getPlayer())
                .replace("{produto}", order.getProduct())
                .replace("{valor}", formattedPrice)
                .replace("{preco}", formattedPrice)
                .replace("{pedido}", Integer.toString(order.getId()))
                .replace("{id}", Integer.toString(order.getId()));
    }
}
