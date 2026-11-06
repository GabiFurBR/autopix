package io.github.warleysr.autopix.qrcode;

import java.awt.image.BufferedImage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;

import io.github.warleysr.autopix.AutoPix;
import io.github.warleysr.autopix.domain.OrderProduct;
import io.github.warleysr.autopix.inventory.InventoryManager;

public class ImageCreator {

    public static BufferedImage generateQR(String data)
            throws UnsupportedEncodingException, WriterException {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Payload QR Code vazio.");
        }
        String utf8Data = new String(data.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        BitMatrix matrix = new MultiFormatWriter()
                .encode(utf8Data, BarcodeFormat.QR_CODE, 128, 128);

        return MatrixToImageWriter.toBufferedImage(matrix);
    }

    @SuppressWarnings("deprecation")
    public static ItemStack generateMap(final BufferedImage image, Player p, OrderProduct op) {
        String mapMaterial = Material.getMaterial("FILLED_MAP") != null ? "FILLED_MAP" : "MAP";

        ItemStack itemStack = new ItemStack(Material.getMaterial(mapMaterial));
        MapMeta mapMeta = (MapMeta) itemStack.getItemMeta();
        MapView mapView = Bukkit.createMap(p.getWorld());
        mapView.setScale(MapView.Scale.CLOSEST);
        try {
            MapView.class.getMethod("setUnlimitedTracking", boolean.class);
            mapView.setUnlimitedTracking(true);
        } catch (NoSuchMethodException e) {
        }

        mapView.getRenderers().clear();
        mapView.addRenderer(new MapRenderer() {
            private boolean rendered = false;

            @Override
            public void render(MapView mapView, MapCanvas mapCanvas, Player player) {
                if (!rendered && image != null) {
                    mapCanvas.drawImage(0, 0, image);
                    rendered = true;
                }
            }
        });

        try {
            MapMeta.class.getMethod("setMapView", MapView.class);
            mapMeta.setMapView(mapView);
        } catch (NoSuchMethodException e) {
            itemStack.setDurability(getMapID(mapView));
        }

        InventoryManager.updateMapMeta(AutoPix.getInstance(), mapMeta, p, op);

        itemStack.setItemMeta(mapMeta);

        return itemStack;
    }

    public static Class<?> getMapNMS(String name) {
        try {
            return Class.forName("org.bukkit.map." + name);
        } catch (Exception e) {
            return null;
        }
    }

    public static short getMapID(MapView view) {
        try {
            return (short) view.getId();
        } catch (NoSuchMethodError e) {
            try {
                Class<?> mapViewClass = getMapNMS("MapView");
                Object mapID = mapViewClass.getMethod("getId").invoke(view);
                return (short) mapID;
            } catch (Exception ex) {
                return 1;
            }
        }
    }
}
