package ru.lbs.kml;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Чат группы через свой сервер. Всё шифруется на телефоне (AES-256-GCM) ключом группы;
 * сервер видит только номер комнаты и непрозрачные блоки.
 */
final class ChatClient {

    static final class Msg {
        long seq, t;
        String kind = "msg", from = "", dev = "", text = "", ptName = "";
        double lat, lon;
        boolean mine;

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("k", kind);
            o.put("f", from);
            o.put("d", dev);
            o.put("t", t);
            if (!text.isEmpty()) o.put("x", text);
            if (kind.equals("pt")) {
                o.put("n", ptName);
                o.put("la", lat);
                o.put("lo", lon);
            }
            return o;
        }

        static Msg of(JSONObject o) {
            Msg m = new Msg();
            m.kind = o.optString("k", "msg");
            m.from = o.optString("f");
            m.dev = o.optString("d");
            m.t = o.optLong("t");
            m.text = o.optString("x", "");
            m.ptName = o.optString("n", "");
            m.lat = o.optDouble("la", 0);
            m.lon = o.optDouble("lo", 0);
            return m;
        }
    }

    /** Участник на карте: последняя присланная им метка. */
    static final class Member {
        String dev = "", name = "";
        double lat, lon;
        float acc, heading = Float.NaN;
        long t;       // когда снята координата (часы телефона участника)
        long recv;    // когда сервер её получил
        boolean off;  // участник выключил показ
    }

    interface Listener {
        void onMessages(List<Msg> added);

        default void onMembers() {}

        void onStatus(String status, boolean ok);
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Msg> history = new ArrayList<>();
    private volatile boolean running;
    private volatile int gen;
    private Thread poller;
    private Listener listener;
    private byte[] key;
    private String room = "", server = "";
    private long lastSeq;
    private final java.util.Map<String, Member> members = new java.util.concurrent.ConcurrentHashMap<>();

    /** Участники с меткой на карте (кроме меня и выключивших показ). */
    List<Member> members() {
        List<Member> out = new ArrayList<>();
        for (Member m : members.values()) if (!m.off && !m.dev.equals(deviceId())) out.add(m);
        return out;
    }

    /** Отправить свою метку (lat/lon) или сообщить, что показ выключен (off=true). */
    void sendLoc(double lat, double lon, float acc, float heading, boolean off) {
        if (!configured()) return;
        final String srv = server, rm = room, dev = deviceId();
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("k", "loc");
                o.put("f", callsign());
                o.put("d", dev);
                o.put("t", System.currentTimeMillis());
                if (off) o.put("off", true);
                else {
                    o.put("la", Math.round(lat * 1e6) / 1e6);
                    o.put("lo", Math.round(lon * 1e6) / 1e6);
                    o.put("ac", Math.round(acc));
                    if (!Float.isNaN(heading)) o.put("hd", Math.round(heading));
                }
                http("POST", srv + "/api/loc?room=" + rm + "&dev=" + dev, encrypt(o.toString()), 15000);
            } catch (Exception ignored) {
            }
        }).start();
    }

    ChatClient(Context c) {
        app = c.getApplicationContext();
        prefs = LbsCore.prefs(app);
        load();
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean configured() {
        return key != null && !server.isEmpty();
    }

    String groupName() {
        return prefs.getString("chatGroup", "Группа");
    }

    String callsign() {
        return prefs.getString("chatCall", "");
    }

    void setCallsign(String c) {
        prefs.edit().putString("chatCall", c.trim()).apply();
    }

    String server() {
        return server;
    }

    String deviceId() {
        String d = prefs.getString("chatDev", "");
        if (d.isEmpty()) {
            d = Long.toString(new SecureRandom().nextLong() & Long.MAX_VALUE, 36);
            prefs.edit().putString("chatDev", d).apply();
        }
        return d;
    }

    List<Msg> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    // ---------- группа ----------

    static String normServer(String s) {
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        return s;
    }

    /** Новая группа: случайный ключ 256 бит. */
    void create(String srv, String name) {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        apply(normServer(srv), k, name);
    }

    /** Приглашение: rubezh://join?s=…&k=…&n=… */
    String invite() {
        try {
            return "rubezh://join?s=" + URLEncoder.encode(server, "UTF-8")
                    + "&k=" + Base64.encodeToString(key, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING)
                    + "&n=" + URLEncoder.encode(groupName(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    static boolean isInvite(String s) {
        return s != null && s.trim().startsWith("rubezh://join?");
    }

    /** Вступить по приглашению. */
    void join(String inv) throws Exception {
        String q = inv.trim().substring("rubezh://join?".length());
        String s = null, k = null, n = "Группа";
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            String a = part.substring(0, i), v = URLDecoder.decode(part.substring(i + 1), "UTF-8");
            if (a.equals("s")) s = v;
            else if (a.equals("k")) k = v;
            else if (a.equals("n")) n = v;
        }
        if (s == null || k == null) throw new Exception("неполное приглашение");
        byte[] kb = Base64.decode(k, Base64.URL_SAFE);
        if (kb.length != 32) throw new Exception("неверный ключ");
        apply(normServer(s), kb, n);
    }

    void leave() {
        stop();
        prefs.edit().remove("chatServer").remove("chatKey").remove("chatGroup").remove("chatSeq").apply();
        File f = store();
        if (f != null) f.delete();
        key = null;
        server = "";
        room = "";
        members.clear();
        synchronized (history) {
            history.clear();
        }
    }

    private void apply(String srv, byte[] k, String name) {
        stop();
        members.clear();
        prefs.edit().putString("chatServer", srv)
                .putString("chatKey", Base64.encodeToString(k, Base64.NO_WRAP))
                .putString("chatGroup", name == null || name.trim().isEmpty() ? "Группа" : name.trim())
                .putLong("chatSeq", 0).apply();
        load();
    }

    private void load() {
        server = prefs.getString("chatServer", "");
        String k = prefs.getString("chatKey", "");
        key = k.isEmpty() ? null : Base64.decode(k, Base64.NO_WRAP);
        room = key == null ? "" : hex(sha("rubezh-room|", key)).substring(0, 32);
        lastSeq = prefs.getLong("chatSeq", 0);
        synchronized (history) {
            history.clear();
            File f = store();
            if (f != null && f.exists()) {
                try {
                    for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
                        if (line.isEmpty()) continue;
                        JSONObject o = new JSONObject(line);
                        Msg m = Msg.of(o);
                        m.seq = o.optLong("s");
                        m.mine = m.dev.equals(deviceId());
                        history.add(m);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private File store() {
        return room.isEmpty() ? null : new File(app.getFilesDir(), "chat_" + room + ".jsonl");
    }

    private void persist(List<Msg> added) {
        File f = store();
        if (f == null) return;
        try {
            synchronized (history) {
                if (history.size() > 1500) { // перезаписать, оставив последние 1000
                    List<Msg> keep = new ArrayList<>(history.subList(history.size() - 1000, history.size()));
                    history.clear();
                    history.addAll(keep);
                    StringBuilder sb = new StringBuilder();
                    for (Msg m : history) sb.append(m.json().put("s", m.seq)).append('\n');
                    java.nio.file.Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            try (FileOutputStream os = new FileOutputStream(f, true)) {
                for (Msg m : added) os.write((m.json().put("s", m.seq) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- шифрование ----------

    private static byte[] sha(String prefix, byte[] k) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prefix.getBytes(StandardCharsets.UTF_8));
            md.update(k);
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private String encrypt(String plain) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sha("rubezh-enc|", key), "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(room.getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return Base64.encodeToString(out, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private String decrypt(String b64) throws Exception {
        byte[] in = Base64.decode(b64, Base64.URL_SAFE);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sha("rubezh-enc|", key), "AES"), new GCMParameterSpec(128, in, 0, 12));
        c.updateAAD(room.getBytes(StandardCharsets.UTF_8));
        return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
    }

    // ---------- сеть ----------

    private String http(String method, String url, String body, int readTimeout) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod(method);
        con.setConnectTimeout(10000);
        con.setReadTimeout(readTimeout);
        con.setRequestProperty("User-Agent", "Rubezh");
        if (body != null) {
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "text/plain");
            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = con.getResponseCode();
        InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[16384];
            int k;
            while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
            in.close();
        }
        if (code != 200) throw new Exception("сервер ответил " + code);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Проверка адреса сервера (в фоне). */
    static void ping(String srv, java.util.function.Consumer<String> done) {
        new Thread(() -> {
            String res;
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(normServer(srv) + "/api/ping").openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                res = con.getResponseCode() == 200 ? null : "сервер ответил " + con.getResponseCode();
            } catch (Exception e) {
                res = e.getClass().getSimpleName().replace("Exception", "") + ": " + e.getMessage();
            }
            final String r = res;
            new Handler(Looper.getMainLooper()).post(() -> done.accept(r));
        }).start();
    }

    void send(Msg m, Runnable ok, java.util.function.Consumer<String> fail) {
        m.from = callsign();
        m.dev = deviceId();
        m.t = System.currentTimeMillis();
        final String srv = server, rm = room;
        new Thread(() -> {
            try {
                String blob = encrypt(m.json().toString());
                http("POST", srv + "/api/send?room=" + rm, blob, 15000);
                ui.post(ok);
            } catch (Exception e) {
                ui.post(() -> fail.accept(e.getMessage()));
            }
        }).start();
    }

    void start() {
        if (running || !configured()) return;
        running = true;
        final int my = ++gen;
        final String srv = server, rm = room;
        poller = new Thread(() -> {
            int backoff = 2;
            boolean wasOk = false;
            boolean firstLocs = true;
            while (running && gen == my) {
                try {
                    String r = http("GET", srv + "/api/poll?room=" + rm + "&since=" + lastSeq + (firstLocs ? "&lsince=0&wait=0" : "&wait=25"), null, 35000);
                    if (gen != my) break;
                    firstLocs = false;
                    JSONObject o = new JSONObject(r);
                    JSONArray arr = o.getJSONArray("msgs");
                    List<Msg> added = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject e = arr.getJSONObject(i);
                        long s = e.getLong("s");
                        if (s <= lastSeq) continue;
                        lastSeq = s;
                        try {
                            Msg m = Msg.of(new JSONObject(decrypt(e.getString("b"))));
                            m.seq = s;
                            m.mine = m.dev.equals(deviceId());
                            added.add(m);
                        } catch (Exception bad) {
                            // чужой или повреждённый блок — пропускаем
                        }
                    }
                    JSONArray la = o.optJSONArray("locs");
                    boolean memChanged = false;
                    if (la != null) {
                        for (int i = 0; i < la.length(); i++) {
                            JSONObject e = la.getJSONObject(i);
                            lastSeq = Math.max(lastSeq, e.optLong("s"));
                            try {
                                JSONObject p = new JSONObject(decrypt(e.getString("b")));
                                Member m = new Member();
                                m.dev = p.optString("d", e.optString("d"));
                                m.name = p.optString("f");
                                m.off = p.optBoolean("off", false);
                                m.lat = p.optDouble("la", 0);
                                m.lon = p.optDouble("lo", 0);
                                m.acc = (float) p.optDouble("ac", 0);
                                m.heading = p.has("hd") ? (float) p.optDouble("hd") : Float.NaN;
                                m.t = p.optLong("t");
                                m.recv = e.optLong("t");
                                members.put(m.dev, m);
                                memChanged = true;
                            } catch (Exception bad) {
                                // не наш ключ — пропускаем
                            }
                        }
                    }
                    if (memChanged) ui.post(() -> { if (listener != null) listener.onMembers(); });
                    if (o.optLong("last") < lastSeq) lastSeq = o.optLong("last"); // сервер очищен
                    prefs.edit().putLong("chatSeq", lastSeq).apply();
                    if (!added.isEmpty()) {
                        synchronized (history) {
                            history.addAll(added);
                        }
                        persist(added);
                        ui.post(() -> { if (listener != null) listener.onMessages(added); });
                    }
                    if (!wasOk) {
                        wasOk = true;
                        ui.post(() -> { if (listener != null) listener.onStatus("на связи", true); });
                    }
                    backoff = 2;
                } catch (Exception e) {
                    if (!running) break;
                    wasOk = false;
                    final String msg = e.getMessage();
                    ui.post(() -> { if (listener != null) listener.onStatus("нет связи с сервером" + (msg == null ? "" : " (" + msg + ")"), false); });
                    try {
                        Thread.sleep(backoff * 1000L);
                    } catch (InterruptedException ie) {
                        break;
                    }
                    backoff = Math.min(30, backoff * 2);
                }
            }
        });
        poller.start();
    }

    void stop() {
        running = false;
        gen++;
        if (poller != null) poller.interrupt();
        poller = null;
    }
}
