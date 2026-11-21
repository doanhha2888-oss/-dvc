package vn.roommonitor.android;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quản lý kết nối dữ liệu phòng, kiểm tra số dư liên tục mỗi 1s và cập nhật nhật ký.
 */
public class RoomConnection {
    private static final String BASE_URL = "https://gs2.qm7.playmidas552.net";
    private static final String GAME_ORIGIN = "https://cdn.playmidas552.net";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36";

    public interface Listener {
        void onUpdate();
    }

    public volatile boolean running = false;
    public boolean loadingRooms = false;
    public String status = "Sẵn sàng";
    public volatile BigDecimal balanceA = BigDecimal.ZERO;
    public volatile BigDecimal balanceB = BigDecimal.ZERO;
    public final int[] counts = new int[]{0, 0, 0, 0}; // 0: Player, 1: Banker, 2: Tie, 3: Khác
    public final List<String> journal = Collections.synchronizedList(new ArrayList<>());

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread balanceThread;
    private boolean isVisible = true;

    public RoomConnection(Listener listener) {
        this.listener = listener;
    }

    public void setVisible(boolean visible) {
        this.isVisible = visible;
    }

    public static boolean validToken(String token) {
        return token != null && token.trim().length() >= 8;
    }

    public static Set<String> parseRooms(String text) {
        Set<String> set = new LinkedHashSet<>();
        if (text == null) return set;
        String[] lines = text.split("[\r\n,;]+");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return set;
    }

    public static String outcomeLabel(int index) {
        switch (index) {
            case 0: return "Cửa A (Con / Player)";
            case 1: return "Cửa B (Cái / Banker)";
            case 2: return "Hòa (Tie)";
            default: return "Khác / Bỏ qua";
        }
    }

    public void start(String tokenA, String tokenB, Set<String> rooms, String playerId, String lobbyId) {
        running = true;
        status = "Đang theo dõi " + rooms.size() + " phòng";
        log("Bắt đầu phiên theo dõi. Số phòng: " + rooms.size());
        notifyUpdate();

        // Bắt đầu luồng kiểm tra số dư liên tục 1 giây / lần
        startBalanceLoop(tokenA, tokenB);

        mainHandler.postDelayed(() -> {
            if (!running) return;
            status = "Đang kiểm tra số dư (1s/lần)";
            notifyUpdate();
        }, 1000);
    }

    public void stop() {
        running = false;
        status = "Đã dừng";
        if (balanceThread != null) {
            balanceThread.interrupt();
            balanceThread = null;
        }
        log("Đã dừng phiên theo dõi.");
        notifyUpdate();
    }

    /**
     * Luồng chạy nền kiểm tra số dư liên tục mỗi 1 giây (1s).
     */
    private void startBalanceLoop(String tokenA, String tokenB) {
        if (balanceThread != null && balanceThread.isAlive()) {
            balanceThread.interrupt();
        }

        balanceThread = new Thread(() -> {
            log("Khởi động luồng check số dư liên tục 1s...");
            while (running) {
                boolean changed = false;

                BigDecimal balA = fetchBalance(tokenA);
                if (balA != null && !balA.equals(balanceA)) {
                    balanceA = balA;
                    changed = true;
                }

                BigDecimal balB = fetchBalance(tokenB);
                if (balB != null && !balB.equals(balanceB)) {
                    balanceB = balB;
                    changed = true;
                }

                if (changed) {
                    notifyUpdate();
                }

                try {
                    Thread.sleep(1000); // Lặp lại đúng 1 giây
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "BalanceMonitorThread_1s");

        balanceThread.setDaemon(true);
        balanceThread.start();
    }

    /**
     * Gọi API lấy số dư mới nhất từ plays endpoint
     */
    private BigDecimal fetchBalance(String token) {
        if (!validToken(token)) return null;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_URL + "/table/api/plays?q=%7B%22s%22:[%22created_at+DESC%22]%7D&page=1&per_page=1");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("x-authentication-token", token);
            conn.setRequestProperty("game-identifier", "baccarat-multi");
            conn.setRequestProperty("Origin", GAME_ORIGIN);
            conn.setRequestProperty("Referer", GAME_ORIGIN + "/");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                JSONArray plays = json.optJSONArray("plays");
                if (plays != null && plays.length() > 0) {
                    JSONObject firstPlay = plays.getJSONObject(0);
                    JSONObject player = firstPlay.optJSONObject("player");
                    if (player != null && player.has("balance")) {
                        return new BigDecimal(player.optString("balance", "0"));
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * Tải danh sách phòng trực tiếp từ máy chủ API
     */
    public void loadRooms(String tokenA, Consumer<List<String>> onSuccess, Consumer<String> onError) {
        loadingRooms = true;
        notifyUpdate();

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(BASE_URL + "/table/api/rounds");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("x-authentication-token", tokenA);
                conn.setRequestProperty("game-identifier", "baccarat-multi");
                conn.setRequestProperty("Origin", GAME_ORIGIN);
                conn.setRequestProperty("Referer", GAME_ORIGIN + "/");
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    Set<String> roomIds = new LinkedHashSet<>();
                    Pattern pattern = Pattern.compile("(?i)\"(?:roomId|room_id)\"\\s*:\\s*\"?([a-zA-Z0-9_-]{1,64})\"?");
                    Matcher matcher = pattern.matcher(sb.toString());
                    while (matcher.find()) {
                        roomIds.add(matcher.group(1));
                    }

                    List<String> result = new ArrayList<>(roomIds);
                    mainHandler.post(() -> {
                        loadingRooms = false;
                        notifyUpdate();
                        onSuccess.accept(result);
                    });
                } else {
                    mainHandler.post(() -> {
                        loadingRooms = false;
                        notifyUpdate();
                        onError.accept("Máy chủ phản hồi HTTP " + code);
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    loadingRooms = false;
                    notifyUpdate();
                    onError.accept("Lỗi: " + e.getMessage());
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    public void log(String message) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
        String timeStr = sdf.format(new java.util.Date());
        journal.add("[" + timeStr + "] " + message);
        if (journal.size() > 200) {
            journal.remove(0);
        }
    }

    public void destroy() {
        running = false;
        if (balanceThread != null) {
            balanceThread.interrupt();
            balanceThread = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void notifyUpdate() {
        if (listener != null) {
            mainHandler.post(listener::onUpdate);
        }
    }
}
