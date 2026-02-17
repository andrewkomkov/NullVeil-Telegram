package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class NullVeilProxy {

    private static final String GUEST_NODE_URL = "https://api.null-veil.com/api/v1/vpn/guest-node";
    private static final String TAG = "NullVeilProxy";

    public static void init(Context context) {
        Thread thread = new Thread(() -> {
            try {
                startProxy(context);
            } catch (Exception e) {
                FileLog.e(TAG + ": failed to start proxy: " + e.getMessage());
            }
        }, "nullveil-proxy-init");
        thread.setDaemon(true);
        thread.start();
    }

    private static void startProxy(Context context) throws Exception {
        // Fetch guest node config from API
        String responseBody = fetchGuestNodeConfig();

        JSONObject json = new JSONObject(responseBody);
        JSONObject config = json.getJSONObject("config");
        String auth = json.getString("auth");

        String hostname = config.getString("hostname");
        int port = config.getInt("port");
        String obfsPassword = config.optString("obfs_password", "");
        String sni = config.getString("sni");

        // Build server address in host:port format expected by the Go library
        String serverAddr = hostname + ":" + port;

        // Start Hysteria2 client; returns the local SOCKS5 port
        long socksPort = hysteria.Hysteria.start(serverAddr, auth, obfsPassword, sni);
        if (socksPort <= 0) {
            FileLog.e(TAG + ": Hysteria start returned invalid port: " + socksPort);
            return;
        }

        int localPort = (int) socksPort;
        FileLog.d(TAG + ": Hysteria2 running, local SOCKS5 port = " + localPort);

        applyProxy(context, "127.0.0.1", localPort);
    }

    private static void applyProxy(Context context, String address, int port) {
        // Persist settings to mainconfig so they survive across ConnectionsManager.init()
        SharedPreferences preferences = context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("proxy_ip", address);
        editor.putInt("proxy_port", port);
        editor.putString("proxy_user", "");
        editor.putString("proxy_pass", "");
        editor.putString("proxy_secret", "");
        editor.putBoolean("proxy_enabled", true);
        editor.putBoolean("proxy_enabled_calls", true);
        editor.apply();

        // Update SharedConfig in-memory state
        SharedConfig.currentProxy = new SharedConfig.ProxyInfo(address, port, "", "", "");
        SharedConfig.addProxy(SharedConfig.currentProxy);

        // Apply to active connections
        ConnectionsManager.setProxySettings(true, address, port, "", "", "");

        FileLog.d(TAG + ": proxy applied at " + address + ":" + port);
    }

    private static String fetchGuestNodeConfig() throws Exception {
        URL url = new URL(GUEST_NODE_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/json");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP " + responseCode + " from guest-node endpoint");
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            conn.disconnect();
        }

        return sb.toString();
    }
}
