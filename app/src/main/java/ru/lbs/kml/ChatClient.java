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
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Группа v2 на своём сервере.
 * • У каждого участника свой пропуск (id + токен) и пара ключей ECDH P-256.
 * • Ключ группы (AES-256) по эпохам; новичку и при смене ключа администратор кладёт на сервер
 *   «конверт» — ключ, зашифрованный лично для участника. Сервер ключа не знает.
 * • Удалённого сервер больше не пускает, а группа переходит на новый ключ.
 */
final class ChatClient {

    /**
     * Сообщение. kind: msg — текст, pt — точка, st — статус, sos — тревога, voice — голос, photo — фото,
     * obj — объект общей карты (op put/del, oid, obj — JSON объекта), snap — снимок всей общей карты.
     */
    static final class Msg {
        long seq, t, exp, ms;
        String kind = "msg", from = "", dev = "", text = "", ptName = "", media = "", op = "", oid = "", obj = "";
        double lat, lon;
        boolean mine, hasLoc;
        transient String data; // base64 голоса/фото при отправке и приёме

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("k", kind);
            o.put("f", from);
            o.put("d", dev);
            o.put("t", t);
            if (exp > 0) o.put("exp", exp);
            if (!text.isEmpty()) o.put("x", text);
            if (kind.equals("pt") || hasLoc) {
                o.put("n", ptName);
                o.put("la", lat);
                o.put("lo", lon);
                o.put("hl", true);
            }
            if (ms > 0) o.put("ms", ms);
            if (!media.isEmpty()) o.put("media", media);
            if (!op.isEmpty()) o.put("op", op);
            if (!oid.isEmpty()) o.put("oid", oid);
            if (!obj.isEmpty()) o.put("obj", obj);
            return o;
        }

        /** Для отправки: с данными голоса/фото, без локального пути. */
        JSONObject wire() throws Exception {
            JSONObject o = json();
            o.remove("media");
            if (data != null) o.put("a", data);
            return o;
        }

        static Msg of(JSONObject o) {
            Msg m = new Msg();
            m.kind = o.optString("k", "msg");
            m.from = o.optString("f");
            m.dev = o.optString("d");
            m.t = o.optLong("t");
            m.exp = o.optLong("exp");
            m.text = o.optString("x", "");
            m.ptName = o.optString("n", "");
            m.lat = o.optDouble("la", 0);
            m.lon = o.optDouble("lo", 0);
            m.hasLoc = o.optBoolean("hl", m.kind.equals("pt"));
            m.ms = o.optLong("ms");
            m.media = o.optString("media", "");
            m.op = o.optString("op", "");
            m.oid = o.optString("oid", "");
            m.obj = o.optString("obj", "");
            m.data = o.has("a") ? o.optString("a") : null;
            return m;
        }
    }

    /** Участник на карте: последняя присланная им метка. */
    static final class Member {
        String dev = "", name = "";
        double lat, lon;
        float acc, heading = Float.NaN;
        long t, recv;
        boolean off;
        List<double[]> tail = new ArrayList<>(); // след за последний час (если участник его показывает)
    }

    /** Участник группы (по данным сервера). */
    static final class Person {
        String id = "", role = "", callsign = "", pub = "";
        long joined, seen, changed;

        boolean admin() { return role.equals("owner") || role.equals("admin"); }

        boolean active() { return admin() || role.equals("member"); }

        String roleName() {
            switch (role) {
                case "owner": return "создатель";
                case "admin": return "администратор";
                case "member": return "участник";
                case "pending": return "ждёт подтверждения";
                case "removed": return "удалён";
                case "left": return "вышел";
                default: return role;
            }
        }
    }

    static final class Invite {
        String code, by;
        long expires, created;
        int uses, used;
    }

    interface Listener {
        void onMessages(List<Msg> added);

        default void onMembers() {}

        default void onPeople(int pendingCount) {}

        default void onRole(String role) {}

        default void onReads() {}

        default void onObjects() {}

        void onStatus(String status, boolean ok);
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Msg> history = new ArrayList<>();
    private final Map<String, Member> members = new ConcurrentHashMap<>();
    private final Map<String, Person> people = new ConcurrentHashMap<>();
    private final Map<Integer, byte[]> keys = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile int gen;
    private Thread poller;
    private String server = "", room = "", me = "", token = "";
    private volatile String role = "";
    private volatile int epoch;
    private long lastSeq, mver = -1, rver = -1;
    private final Map<String, Long> reads = new ConcurrentHashMap<>();
    private final Map<String, GObj> objs = new ConcurrentHashMap<>();
    private long lastReadSent;

    // ---------- прочитано ----------

    /** Сколько других активных участников прочитали сообщение seq; total — сколько их всего. */
    int[] readBy(long seq) {
        int n = 0, total = 0;
        for (Person p : people.values()) {
            if (!p.active() || p.id.equals(me)) continue;
            total++;
            Long r = reads.get(p.id);
            if (r != null && r >= seq) n++;
        }
        return new int[]{n, total};
    }

    /** Отметить, что я прочитал всё до последнего сообщения (вызывается, когда чат открыт). */
    void markRead() {
        final long s = lastSeq;
        if (!active() || s <= lastReadSent) return;
        lastReadSent = s;
        bg(() -> {
            try {
                http("POST", "/api/v2/read?seq=" + s, "{}", 10000);
            } catch (Exception ignored) {
            }
        });
    }

    // ---------- медиа ----------

    private String saveMedia(long seq, String ext, String b64) {
        try {
            File d = new File(app.getFilesDir(), "chatmedia");
            if (!d.exists()) d.mkdirs();
            File f = new File(d, room + "_" + seq + "." + ext);
            java.nio.file.Files.write(f.toPath(), unb64(b64));
            return f.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- общая карта группы ----------

    /** Объект общей карты: точка, маршрут или зона. */
    static final class GObj {
        String id = "", type = "pt", name = "", icon = "", author = "", authorName = "", note = "";
        int color;
        long t;
        final List<double[]> pts = new ArrayList<>();

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject().put("id", id).put("ty", type).put("n", name).put("c", color).put("ic", icon)
                    .put("au", author).put("an", authorName).put("t", t).put("no", note);
            JSONArray a = new JSONArray();
            for (double[] p : pts) a.put(new JSONArray().put(p[0]).put(p[1]));
            return o.put("p", a);
        }

        static GObj of(JSONObject o) throws Exception {
            GObj g = new GObj();
            g.id = o.optString("id");
            g.type = o.optString("ty", "pt");
            g.name = o.optString("n");
            g.color = o.optInt("c");
            g.icon = o.optString("ic");
            g.author = o.optString("au");
            g.authorName = o.optString("an");
            g.t = o.optLong("t");
            g.note = o.optString("no");
            JSONArray a = o.getJSONArray("p");
            for (int i = 0; i < a.length(); i++) g.pts.add(new double[]{a.getJSONArray(i).getDouble(0), a.getJSONArray(i).getDouble(1)});
            return g;
        }
    }

    List<GObj> objects() {
        return new ArrayList<>(objs.values());
    }

    GObj object(String id) {
        return objs.get(id);
    }

    /** Может ли участник dev менять объект (автор или администратор). */
    boolean canEdit(GObj g, String dev) {
        if (g.author.equals(dev)) return true;
        Person p = people.get(dev);
        return p != null && p.admin();
    }

    private boolean applyObj(Msg m) {
        try {
            if (m.kind.equals("snap")) {
                Person p = people.get(m.dev);
                if (p != null && !p.admin()) return false; // снимок карты рассылают только администраторы
                JSONArray a = new JSONArray(m.obj);
                for (int i = 0; i < a.length(); i++) {
                    GObj g = GObj.of(a.getJSONObject(i));
                    GObj cur = objs.get(g.id);
                    if (cur == null || cur.t < g.t) objs.put(g.id, g);
                }
                return true;
            }
            if (m.op.equals("del")) {
                GObj cur = objs.get(m.oid);
                if (cur == null || !canEdit(cur, m.dev)) return false;
                objs.remove(m.oid);
                return true;
            }
            GObj g = GObj.of(new JSONObject(m.obj));
            GObj cur = objs.get(g.id);
            if (cur != null && !canEdit(cur, m.dev)) return false;
            if (cur == null) g.author = m.dev; // автор — тот, кого подтвердил сервер
            else g.author = cur.author;
            g.authorName = nameOf(g.author);
            objs.put(g.id, g);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveObjs() {
        try {
            JSONArray a = new JSONArray();
            for (GObj g : objs.values()) a.put(g.json());
            prefs.edit().putString("c2Objs", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void loadObjs() {
        objs.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString("c2Objs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                GObj g = GObj.of(a.getJSONObject(i));
                objs.put(g.id, g);
            }
        } catch (Exception ignored) {
        }
    }

    /** Добавить / изменить объект общей карты. */
    void putObj(GObj g, Runnable done, Consumer<String> fail) {
        try {
            if (g.id.isEmpty()) g.id = rnd(6);
            g.t = System.currentTimeMillis();
            Msg m = new Msg();
            m.kind = "obj";
            m.op = "put";
            m.oid = g.id;
            m.obj = g.json().toString();
            send(m, done, fail);
        } catch (Exception e) {
            err(fail, e);
        }
    }

    void delObj(GObj g, Runnable done, Consumer<String> fail) {
        Msg m = new Msg();
        m.kind = "obj";
        m.op = "del";
        m.oid = g.id;
        m.text = g.name;
        send(m, done, fail);
    }

    /** Снимок всей общей карты — чтобы новичок (или после смены ключа) увидел всё, что отмечено раньше. */
    private void sendSnapshot() {
        try {
            if (objs.isEmpty() || !isAdmin()) return;
            JSONArray a = new JSONArray();
            for (GObj g : objs.values()) a.put(g.json());
            Msg m = new Msg();
            m.kind = "snap";
            m.obj = a.toString();
            m.from = callsign();
            m.dev = me;
            m.t = System.currentTimeMillis();
            http("POST", "/api/v2/send", encrypt(m.wire().toString()), 30000);
        } catch (Exception ignored) {
        }
    }

    // ---------- исчезающие сообщения ----------

    /** Убрать из ленты истёкшие сообщения (и их файлы). true — что-то удалено. */
    boolean purgeExpired() {
        long t = System.currentTimeMillis();
        boolean any = false;
        synchronized (history) {
            for (java.util.Iterator<Msg> it = history.iterator(); it.hasNext(); ) {
                Msg m = it.next();
                if (m.exp > 0 && m.exp < t) {
                    if (!m.media.isEmpty()) new File(m.media).delete();
                    it.remove();
                    any = true;
                }
            }
            if (any) {
                File f = store();
                try {
                    StringBuilder sb = new StringBuilder();
                    for (Msg m : history) sb.append(m.json().put("s", m.seq)).append('\n');
                    if (f != null) java.nio.file.Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            }
        }
        return any;
    }

    private static ChatClient instance;

    /** Один клиент на приложение — общий для карты и фонового сервиса. */
    static synchronized ChatClient get(Context c) {
        if (instance == null) instance = new ChatClient(c);
        return instance;
    }

    private ChatClient(Context c) {
        app = c.getApplicationContext();
        prefs = LbsCore.prefs(app);
        load();
    }

    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    void setListener(Listener l) { addListener(l); }

    private volatile String lastStatus = "подключаюсь…";
    private volatile boolean lastOk;

    String lastStatus() { return lastStatus; }

    boolean lastOk() { return lastOk; }

    private void status(String st, boolean ok) {
        lastStatus = st;
        lastOk = ok;
        each(l -> l.onStatus(st, ok));
    }

    private void each(Consumer<Listener> f) {
        ui.post(() -> { for (Listener l : listeners) f.accept(l); });
    }

    boolean configured() { return !server.isEmpty() && !room.isEmpty() && !me.isEmpty(); }

    /** Старая группа (до версии 3.17) — без управления участниками. */
    boolean legacy() { return !configured() && !prefs.getString("chatKey", "").isEmpty(); }

    void dropLegacy() {
        prefs.edit().remove("chatServer").remove("chatKey").remove("chatGroup").remove("chatSeq").apply();
    }

    String groupName() { return prefs.getString("c2Group", "Группа"); }

    String callsign() { return prefs.getString("chatCall", ""); }

    String server() { return server; }

    String deviceId() { return me; }

    String role() { return role; }

    boolean isAdmin() { return role.equals("owner") || role.equals("admin"); }

    boolean pending() { return role.equals("pending"); }

    boolean active() { return isAdmin() || role.equals("member"); }

    int epoch() { return epoch; }

    List<Msg> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /** Участники с меткой на карте (кроме меня, выключивших показ и удалённых). */
    List<Member> members() {
        List<Member> out = new ArrayList<>();
        for (Member m : members.values()) {
            Person p = people.get(m.dev);
            if (m.off || m.dev.equals(me) || (p != null && !p.active())) continue;
            if (p != null && !p.callsign.isEmpty()) m.name = p.callsign;
            out.add(m);
        }
        return out;
    }

    List<Person> people() {
        List<Person> out = new ArrayList<>(people.values());
        Collections.sort(out, (a, b) -> {
            int ra = rank(a.role), rb = rank(b.role);
            return ra != rb ? Integer.compare(ra, rb) : a.callsign.compareToIgnoreCase(b.callsign);
        });
        return out;
    }

    private static int rank(String r) {
        switch (r) {
            case "pending": return 0;
            case "owner": return 1;
            case "admin": return 2;
            case "member": return 3;
            default: return 4;
        }
    }

    String nameOf(String id) {
        Person p = people.get(id);
        return p == null ? "" : p.callsign;
    }

    // ---------- хранение ----------

    /** Перечитать настройки (после восстановления из резервной копии). */
    void reload() {
        stop();
        load();
    }

    private void load() {
        server = prefs.getString("c2Server", "");
        room = prefs.getString("c2Room", "");
        me = prefs.getString("c2Me", "");
        token = prefs.getString("c2Token", "");
        role = prefs.getString("c2Role", "");
        lastSeq = prefs.getLong("c2Seq", 0);
        keys.clear();
        try {
            JSONObject k = new JSONObject(prefs.getString("c2Keys", "{}"));
            for (java.util.Iterator<String> it = k.keys(); it.hasNext(); ) {
                String e = it.next();
                keys.put(Integer.parseInt(e), Base64.decode(k.getString(e), Base64.NO_WRAP));
            }
        } catch (Exception ignored) {
        }
        epoch = 0;
        for (int e : keys.keySet()) epoch = Math.max(epoch, e);
        loadObjs();
        people.clear();
        try {
            parsePeople(new JSONArray(prefs.getString("c2People", "[]")));
        } catch (Exception ignored) {
        }
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
                        m.mine = m.dev.equals(me);
                        history.add(m);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void saveKeys() {
        try {
            JSONObject k = new JSONObject();
            for (Map.Entry<Integer, byte[]> e : keys.entrySet())
                k.put(Integer.toString(e.getKey()), Base64.encodeToString(e.getValue(), Base64.NO_WRAP));
            prefs.edit().putString("c2Keys", k.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private File store() {
        return room.isEmpty() ? null : new File(app.getFilesDir(), "chat2_" + room + ".jsonl");
    }

    private void persist(List<Msg> added) {
        File f = store();
        if (f == null) return;
        try {
            synchronized (history) {
                if (history.size() > 1500) {
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

    private void wipeLocal() {
        File f = store();
        if (f != null) f.delete();
        prefs.edit().remove("c2Server").remove("c2Room").remove("c2Me").remove("c2Token").remove("c2Role")
                .remove("c2Seq").remove("c2Keys").remove("c2People").remove("c2Group").remove("c2Priv").remove("c2Pub")
                .remove("c2MVer").remove("c2Objs").remove("c2RekeyAt").remove("c2PendSeen").apply();
        File md = new File(app.getFilesDir(), "chatmedia");
        File[] fs = md.listFiles();
        if (fs != null) for (File x : fs) x.delete();
        objs.clear();
        reads.clear();
        server = room = me = token = role = "";
        epoch = 0;
        lastSeq = 0;
        mver = -1;
        keys.clear();
        people.clear();
        members.clear();
        synchronized (history) {
            history.clear();
        }
    }

    // ---------- криптография ----------

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

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] unb64(String s) {
        return Base64.decode(s, Base64.URL_SAFE);
    }

    private static String rnd(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] gcm(boolean enc, byte[] key, byte[] aad, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        if (enc) {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            if (aad != null) c.updateAAD(aad);
            byte[] ct = c.doFinal(data);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        }
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, data, 0, 12));
        if (aad != null) c.updateAAD(aad);
        return c.doFinal(data, 12, data.length - 12);
    }

    /** Своя пара ключей (создаётся один раз на группу). */
    private KeyPair myPair() throws Exception {
        String pv = prefs.getString("c2Priv", ""), pb = prefs.getString("c2Pub", "");
        KeyFactory kf = KeyFactory.getInstance("EC");
        if (!pv.isEmpty() && !pb.isEmpty()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(unb64(pb))), kf.generatePrivate(new PKCS8EncodedKeySpec(unb64(pv))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        prefs.edit().putString("c2Priv", b64(kp.getPrivate().getEncoded())).putString("c2Pub", b64(kp.getPublic().getEncoded())).apply();
        return kp;
    }

    private static byte[] ecdh(PrivateKey mine, String otherPub) throws Exception {
        PublicKey p = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(unb64(otherPub)));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(p, true);
        return sha("rubezh-box|", ka.generateSecret());
    }

    /** Конверт с ключом эпохи для участника с открытым ключом pub. */
    private String box(int e, byte[] k, String pub) throws Exception {
        KeyPair kp = myPair();
        JSONObject o = new JSONObject();
        o.put("e", e);
        o.put("k", b64(k));
        byte[] ct = gcm(true, ecdh(kp.getPrivate(), pub), ("box|" + room + "|" + e).getBytes(StandardCharsets.UTF_8),
                o.toString().getBytes(StandardCharsets.UTF_8));
        return b64(kp.getPublic().getEncoded()) + "." + b64(ct);
    }

    private void unbox(int e, String box) throws Exception {
        int dot = box.indexOf('.');
        byte[] plain = gcm(false, ecdh(myPair().getPrivate(), box.substring(0, dot)),
                ("box|" + room + "|" + e).getBytes(StandardCharsets.UTF_8), unb64(box.substring(dot + 1)));
        JSONObject o = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        if (o.getInt("e") != e) throw new Exception("эпоха");
        keys.put(e, unb64(o.getString("k")));
    }

    private String encrypt(String plain) throws Exception {
        byte[] k = keys.get(epoch);
        if (k == null) throw new Exception("нет ключа группы");
        byte[] ct = gcm(true, sha("rubezh-enc|", k), (room + "|" + epoch).getBytes(StandardCharsets.UTF_8),
                plain.getBytes(StandardCharsets.UTF_8));
        return epoch + "." + b64(ct);
    }

    private String decrypt(String blob) throws Exception {
        int dot = blob.indexOf('.');
        int e = Integer.parseInt(blob.substring(0, dot));
        byte[] k = keys.get(e);
        if (k == null) throw new Exception("нет ключа эпохи " + e);
        return new String(gcm(false, sha("rubezh-enc|", k), (room + "|" + e).getBytes(StandardCharsets.UTF_8),
                unb64(blob.substring(dot + 1))), StandardCharsets.UTF_8);
    }

    // ---------- сеть ----------

    static String normServer(String s) {
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        return s;
    }

    private String http(String method, String path, String body, int readTimeout) throws Exception {
        return http(server, room, me, token, method, path, body, readTimeout);
    }

    private static String http(String srv, String rm, String id, String tok, String method, String path, String body, int readTimeout) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(srv + path + (path.contains("?") ? "&" : "?") + "room=" + rm).openConnection();
        con.setRequestMethod(method);
        con.setConnectTimeout(10000);
        con.setReadTimeout(readTimeout);
        con.setRequestProperty("User-Agent", "Rubezh");
        if (!id.isEmpty()) con.setRequestProperty("Authorization", "Bearer " + id + ":" + tok);
        if (body != null) {
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
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
        String res = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        if (code != 200) {
            String err = "сервер ответил " + code;
            try {
                err = new JSONObject(res).optString("error", err);
            } catch (Exception ignored) {
            }
            if (code == 404 && err.contains("page")) err = "сервер старой версии — обновите его командой установки";
            throw new ServerError(code, err);
        }
        return res;
    }

    static final class ServerError extends Exception {
        final int code;

        ServerError(int code, String m) {
            super(m);
            this.code = code;
        }
    }

    private String profileJson() throws Exception {
        JSONObject p = new JSONObject();
        p.put("c", callsign());
        p.put("pk", b64(myPair().getPublic().getEncoded()));
        return p.toString();
    }

    private void bg(Runnable r) {
        new Thread(r).start();
    }

    private void ok(Runnable r) {
        if (r != null) ui.post(r);
    }

    private void err(Consumer<String> f, Exception e) {
        if (f != null) ui.post(() -> f.accept(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }

    static void ping(String srv, Consumer<String> done) {
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

    // ---------- группа ----------

    void setCallsign(String c) {
        prefs.edit().putString("chatCall", c.trim()).apply();
        if (configured()) bg(() -> {
            try {
                http("POST", "/api/v2/profile", new JSONObject().put("profile", profileJson()).toString(), 15000);
            } catch (Exception ignored) {
            }
        });
    }

    /** Создать группу: я — создатель, ключ первой эпохи — у меня. */
    void create(String srv, String name, Runnable done, Consumer<String> fail) {
        bg(() -> {
            try {
                wipeLocal();
                String s = normServer(srv), rm = rnd(16), id = rnd(8), tok = rnd(16);
                prefs.edit().putString("c2Server", s).putString("c2Room", rm).putString("c2Me", id).putString("c2Token", tok)
                        .putString("c2Group", name == null || name.trim().isEmpty() ? "Группа" : name.trim()).apply();
                load();
                JSONObject j = new JSONObject().put("member", id).put("token", tok).put("profile", profileJson());
                http(s, rm, "", "", "POST", "/api/v2/create", j.toString(), 15000);
                byte[] k = new byte[32];
                new SecureRandom().nextBytes(k);
                keys.put(1, k);
                epoch = 1;
                saveKeys();
                role = "owner";
                prefs.edit().putString("c2Role", role).apply();
                ok(done);
            } catch (Exception e) {
                wipeLocal();
                err(fail, e);
            }
        });
    }

    static boolean isInvite(String s) {
        return s != null && (s.trim().startsWith("rubezh2://join?") || s.trim().startsWith("rubezh://join?"));
    }

    /** Вступить по приглашению: заявка уходит администратору. */
    void join(String inv, Runnable done, Consumer<String> fail) {
        bg(() -> {
            try {
                String t = inv.trim();
                if (t.startsWith("rubezh://")) throw new Exception("это приглашение в старую группу — попросите новое");
                String q = t.substring("rubezh2://join?".length());
                String s = null, r = null, c = null, n = "Группа";
                for (String part : q.split("&")) {
                    int i = part.indexOf('=');
                    if (i < 0) continue;
                    String a = part.substring(0, i), v = URLDecoder.decode(part.substring(i + 1), "UTF-8");
                    if (a.equals("s")) s = v;
                    else if (a.equals("r")) r = v;
                    else if (a.equals("c")) c = v;
                    else if (a.equals("n")) n = v;
                }
                if (s == null || r == null || c == null) throw new Exception("неполное приглашение");
                wipeLocal();
                String id = rnd(8), tok = rnd(16);
                prefs.edit().putString("c2Server", s).putString("c2Room", r).putString("c2Me", id).putString("c2Token", tok)
                        .putString("c2Group", n).apply();
                load();
                JSONObject j = new JSONObject().put("code", c).put("member", id).put("token", tok).put("profile", profileJson());
                http(s, r, "", "", "POST", "/api/v2/join", j.toString(), 15000);
                role = "pending";
                prefs.edit().putString("c2Role", role).apply();
                ok(done);
            } catch (Exception e) {
                wipeLocal();
                err(fail, e);
            }
        });
    }

    void leave(Runnable done) {
        stop();
        final String s = server, r = room, id = me, tok = token;
        bg(() -> {
            try {
                http(s, r, id, tok, "POST", "/api/v2/leave", "{}", 10000);
            } catch (Exception ignored) {
            }
        });
        wipeLocal();
        if (done != null) done.run();
    }

    /** Новое приглашение: срок в часах, число вступлений (0 — без ограничения). */
    void makeInvite(int hours, int uses, Consumer<String> done, Consumer<String> fail) {
        bg(() -> {
            try {
                JSONObject o = new JSONObject(http("POST", "/api/v2/invite", new JSONObject().put("hours", hours).put("uses", uses).toString(), 15000));
                String inv = "rubezh2://join?s=" + URLEncoder.encode(server, "UTF-8") + "&r=" + room
                        + "&c=" + o.getString("code") + "&n=" + URLEncoder.encode(groupName(), "UTF-8");
                ui.post(() -> done.accept(inv));
            } catch (Exception e) {
                err(fail, e);
            }
        });
    }

    void invites(String revoke, Consumer<List<Invite>> done, Consumer<String> fail) {
        bg(() -> {
            try {
                String body = revoke == null ? null : new JSONObject().put("code", revoke).toString();
                JSONArray a = new JSONObject(http(revoke == null ? "GET" : "POST", "/api/v2/invites", body, 15000)).getJSONArray("invites");
                List<Invite> out = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Invite v = new Invite();
                    v.code = o.optString("code");
                    v.by = o.optString("by");
                    v.expires = o.optLong("expires");
                    v.created = o.optLong("created");
                    v.uses = o.optInt("uses");
                    v.used = o.optInt("used");
                    out.add(v);
                }
                ui.post(() -> done.accept(out));
            } catch (Exception e) {
                err(fail, e);
            }
        });
    }

    /** approve / reject / remove / admin / member. При удалении — сразу новый ключ группы. */
    void manage(String action, Person p, Runnable done, Consumer<String> fail) {
        bg(() -> {
            try {
                JSONObject j = new JSONObject().put("action", action).put("member", p.id);
                if (action.equals("approve")) {
                    if (p.pub.isEmpty()) throw new Exception("у участника нет ключа");
                    j.put("epoch", epoch).put("box", box(epoch, keys.get(epoch), p.pub));
                }
                http("POST", "/api/v2/manage", j.toString(), 15000);
                refreshPeople();
                if (action.equals("remove")) rekey();
                if (action.equals("approve")) sendSnapshot();
                ok(done);
            } catch (Exception e) {
                err(fail, e);
            }
        });
    }

    /** Новый ключ группы для всех активных участников (после удаления или выхода). */
    private synchronized void rekey() throws Exception {
        refreshPeople();
        int ne = epoch + 1;
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        JSONObject boxes = new JSONObject();
        for (Person p : people.values()) {
            if (!p.active()) continue;
            if (p.pub.isEmpty()) continue;
            boxes.put(p.id, box(ne, k, p.pub));
        }
        try {
            http("POST", "/api/v2/rekey", new JSONObject().put("epoch", ne).put("boxes", boxes).toString(), 20000);
            keys.put(ne, k);
            epoch = ne;
            saveKeys();
            prefs.edit().putLong("c2RekeyAt", System.currentTimeMillis()).apply();
            sendSnapshot();
        } catch (ServerError e) {
            if (e.code == 409) fetchKeys(); // кто-то из администраторов уже сменил ключ
            else throw e;
        }
    }

    private void refreshPeople() throws Exception {
        JSONObject o = new JSONObject(http("GET", "/api/v2/members", null, 15000));
        JSONArray a = o.getJSONArray("members");
        parsePeople(a);
        prefs.edit().putString("c2People", a.toString()).apply();
        Person mine = people.get(me);
        if (mine != null && !mine.role.equals(role)) {
            role = mine.role;
            prefs.edit().putString("c2Role", role).apply();
        }
    }

    private void parsePeople(JSONArray a) throws Exception {
        people.clear();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Person p = new Person();
            p.id = o.optString("id");
            p.role = o.optString("role");
            p.joined = o.optLong("joined");
            p.seen = o.optLong("seen");
            p.changed = o.optLong("changed");
            try {
                JSONObject pr = new JSONObject(o.optString("profile", "{}"));
                p.callsign = pr.optString("c");
                p.pub = pr.optString("pk");
            } catch (Exception ignored) {
            }
            people.put(p.id, p);
        }
    }

    private void fetchKeys() throws Exception {
        JSONObject o = new JSONObject(http("GET", "/api/v2/keys", null, 15000));
        JSONObject b = o.getJSONObject("boxes");
        for (java.util.Iterator<String> it = b.keys(); it.hasNext(); ) {
            String e = it.next();
            int ei = Integer.parseInt(e);
            if (keys.containsKey(ei)) continue;
            try {
                unbox(ei, b.getString(e));
            } catch (Exception ignored) {
            }
        }
        epoch = 0;
        for (int e : keys.keySet()) epoch = Math.max(epoch, e);
        saveKeys();
    }

    // ---------- сообщения и метки ----------

    /** Срок жизни новых сообщений, секунд (0 — без срока). */
    int ttl() { return prefs.getInt("c2Ttl", 0); }

    void setTtl(int sec) { prefs.edit().putInt("c2Ttl", sec).apply(); }

    void send(Msg m, Runnable okR, Consumer<String> fail) {
        m.from = callsign();
        m.dev = me;
        m.t = System.currentTimeMillis();
        final int tt = m.kind.equals("obj") || m.kind.equals("snap") ? 0 : ttl();
        if (tt > 0) m.exp = m.t + tt * 1000L;
        bg(() -> {
            try {
                if (!active()) throw new Exception(pending() ? "ждём подтверждения администратора" : "вы не в группе");
                http("POST", "/api/v2/send" + (tt > 0 ? "?ttl=" + tt : ""), encrypt(m.wire().toString()), 60000);
                ok(okR);
            } catch (Exception e) {
                err(fail, e);
            }
        });
    }

    void sendLoc(double lat, double lon, float acc, float heading, boolean off) {
        sendLoc(lat, lon, acc, heading, off, null);
    }

    void sendLoc(double lat, double lon, float acc, float heading, boolean off, List<double[]> tail) {
        if (!configured() || !active()) return;
        bg(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("k", "loc");
                o.put("t", System.currentTimeMillis());
                if (off) o.put("off", true);
                else {
                    o.put("la", Math.round(lat * 1e6) / 1e6);
                    o.put("lo", Math.round(lon * 1e6) / 1e6);
                    o.put("ac", Math.round(acc));
                    if (!Float.isNaN(heading)) o.put("hd", Math.round(heading));
                    if (tail != null && tail.size() > 1) {
                        JSONArray tr = new JSONArray();
                        for (double[] p : tail) tr.put(new JSONArray().put(Math.round(p[0] * 1e5) / 1e5).put(Math.round(p[1] * 1e5) / 1e5));
                        o.put("tr", tr);
                    }
                }
                http("POST", "/api/v2/loc", encrypt(o.toString()), 15000);
            } catch (Exception ignored) {
            }
        });
    }

    void start() {
        if (running || !configured()) return;
        running = true;
        final int my = ++gen;
        poller = new Thread(() -> {
            int backoff = 2;
            boolean wasOk = false, firstLocs = true;
            while (running && gen == my) {
                try {
                    String r = http("GET", "/api/v2/poll?since=" + lastSeq + "&mver=" + mver + "&rver=" + rver
                            + (firstLocs ? "&lsince=0&wait=0" : "&wait=25"), null, 35000);
                    if (gen != my) break;
                    firstLocs = false;
                    JSONObject o = new JSONObject(r);
                    String newRole = o.optString("role", role);
                    if (newRole.equals("removed") || newRole.equals("left")) {
                        running = false;
                        final String rr = newRole;
                        wipeLocal();
                        each(l -> l.onRole(rr));
                        break;
                    }
                    boolean roleChanged = !newRole.equals(role);
                    if (roleChanged) {
                        role = newRole;
                        prefs.edit().putString("c2Role", role).apply();
                    }
                    long mv = o.optLong("mver", mver);
                    if (mv != mver && active()) {
                        refreshPeople();
                        mver = mv;
                        int pend = 0;
                        for (Person p : people.values()) if (p.role.equals("pending")) pend++;
                        final int pc = pend;
                        each(l -> { l.onPeople(pc); l.onMembers(); });
                        // администратор: кто-то вышел или удалён после последней смены ключа → новый ключ
                        if (isAdmin()) {
                            long at = prefs.getLong("c2RekeyAt", 0);
                            for (Person p : people.values())
                                if ((p.role.equals("left") || p.role.equals("removed")) && p.changed > at && at > 0) {
                                    rekey();
                                    break;
                                }
                            if (at == 0) prefs.edit().putLong("c2RekeyAt", System.currentTimeMillis()).apply();
                        }
                    } else if (mv != mver) {
                        mver = mv;
                    }
                    if (roleChanged) { final String nr = role; each(l -> l.onRole(nr)); }
                    if (active() && o.optInt("epoch", 0) > epoch) fetchKeys();

                    if (active()) {
                        JSONArray arr = o.getJSONArray("msgs");
                        List<Msg> added = new ArrayList<>();
                        boolean objChanged = false;
                        if (o.has("rver")) rver = o.optLong("rver");
                        JSONObject rd = o.optJSONObject("reads");
                        if (rd != null) {
                            for (java.util.Iterator<String> it = rd.keys(); it.hasNext(); ) {
                                String k = it.next();
                                reads.put(k, rd.optLong(k));
                            }
                            each(Listener::onReads);
                        }
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject e = arr.getJSONObject(i);
                            long s = e.getLong("s");
                            if (s <= lastSeq) continue;
                            lastSeq = s;
                            try {
                                Msg m = Msg.of(new JSONObject(decrypt(e.getString("b"))));
                                m.seq = s;
                                m.dev = e.optString("m", m.dev); // отправителя подтверждает сервер
                                if (e.optLong("x") > 0) m.exp = e.optLong("x");
                                if (e.optLong("t") > 0) m.t = e.optLong("t"); // время сервера: у всех одинаковое
                                String nm = nameOf(m.dev);
                                if (!nm.isEmpty()) m.from = nm;
                                m.mine = m.dev.equals(me);
                                if (m.data != null) {
                                    m.media = saveMedia(m.seq, m.kind.equals("photo") ? "jpg" : "m4a", m.data);
                                    m.data = null;
                                }
                                if (m.kind.equals("obj") || m.kind.equals("snap")) {
                                    if (applyObj(m)) objChanged = true;
                                    if (m.kind.equals("snap")) continue; // в ленте не показываем
                                }
                                added.add(m);
                            } catch (Exception bad) {
                                // нет ключа этой эпохи (сообщения до вступления) — пропускаем
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
                                    m.dev = e.optString("m");
                                    m.name = nameOf(m.dev);
                                    m.off = p.optBoolean("off", false);
                                    m.lat = p.optDouble("la", 0);
                                    m.lon = p.optDouble("lo", 0);
                                    m.acc = (float) p.optDouble("ac", 0);
                                    m.heading = p.has("hd") ? (float) p.optDouble("hd") : Float.NaN;
                                    m.t = p.optLong("t");
                                    m.recv = e.optLong("t");
                                    JSONArray tr = p.optJSONArray("tr");
                                    if (tr != null)
                                        for (int q = 0; q < tr.length(); q++)
                                            m.tail.add(new double[]{tr.getJSONArray(q).getDouble(0), tr.getJSONArray(q).getDouble(1)});
                                    members.put(m.dev, m);
                                    memChanged = true;
                                } catch (Exception bad) {
                                    // старый ключ / чужой блок
                                }
                            }
                        }
                        if (memChanged) each(Listener::onMembers);
                        if (o.optLong("last") < lastSeq) lastSeq = o.optLong("last");
                        prefs.edit().putLong("c2Seq", lastSeq).apply();
                        if (objChanged) {
                            saveObjs();
                            each(Listener::onObjects);
                        }
                        if (!added.isEmpty()) {
                            synchronized (history) {
                                history.addAll(added);
                            }
                            persist(added);
                            each(l -> l.onMessages(added));
                        }
                    } else {
                        Thread.sleep(pending() ? 8000 : 2000); // ожидающий опрашивает реже
                    }
                    if (!wasOk) {
                        wasOk = true;
                        status(pending() ? "ждём подтверждения" : "на связи", true);
                    }
                    backoff = 2;
                } catch (Exception e) {
                    if (!running) break;
                    wasOk = false;
                    final String msg = e.getMessage();
                    status("нет связи с сервером" + (msg == null ? "" : " (" + msg + ")"), false);
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
