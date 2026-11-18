package com.tools.pdabeeper;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Số thứ tự (STT) pallet trong từng đơn (SO hoặc Work Order): pallet -> STT.
 * Pallet quét lần đầu lấy STT = bộ đếm "Đã quét" của PDA lúc đó; pallet bị xóa thì trả STT lại, pallet mới quét sau lấy lại số đó.
 */
final class Seq {
    private final SharedPreferences sp;

    Seq(Context c) { sp = c.getApplicationContext().getSharedPreferences("seq", Context.MODE_PRIVATE); }

    private JSONObject load(String key) {
        try { return new JSONObject(sp.getString(key, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private void save(String key, JSONObject o) {
        SharedPreferences.Editor ed = sp.edit().putString(key, o.toString());
        Map<String, ?> all = sp.getAll();                        // giữ tối đa ~30 đơn gần nhất
        if (all.size() > 30) for (String k : all.keySet()) if (!k.equals(key)) { ed.remove(k); break; }
        ed.apply();
    }

    boolean has(String key, String pid) { return !key.isEmpty() && load(key).has(pid); }

    /** STT của pallet, 0 nếu chưa có. */
    int get(String key, String pid) { return key.isEmpty() ? 0 : load(key).optInt(pid, 0); }

    /** Cấp STT cho pallet mới (đã có thì giữ nguyên). hint = số mong muốn (<=0: lấy số trống nhỏ nhất). */
    int assign(String key, String pid, int hint) {
        if (key.isEmpty() || pid.isEmpty()) return 0;
        JSONObject o = load(key);
        int cur = o.optInt(pid, 0);
        if (cur > 0) return cur;
        Set<Integer> used = new HashSet<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) used.add(o.optInt(it.next()));
        int n = hint;
        if (n <= 0 || used.contains(n)) { n = 1; while (used.contains(n)) n++; }
        try { o.put(pid, n); } catch (Exception ignored) { }
        save(key, o);
        return n;
    }

    void free(String key, String pid) {
        if (key.isEmpty()) return;
        JSONObject o = load(key);
        if (o.has(pid)) { o.remove(pid); save(key, o); }
    }

    /** Xóa dữ liệu của các đơn giả lập (số bắt đầu bằng 9900). */
    void clearSim() {
        SharedPreferences.Editor ed = sp.edit();
        for (String k : sp.getAll().keySet()) if (k.contains("9900")) ed.remove(k);
        ed.apply();
    }
}
