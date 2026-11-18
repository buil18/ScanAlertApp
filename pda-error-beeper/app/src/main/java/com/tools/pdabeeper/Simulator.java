package com.tools.pdabeeper;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Giả lập màn hình Heineken PDA để thử các tình huống mà không cần quét thật.
 * Mỗi kịch bản = dữ liệu GR giả + chuỗi "màn hình" cho tới khi xong; sau đó so sự kiện app đã phát ra với kết quả mong đợi.
 * Đơn giả lập có số bắt đầu bằng 9900… (không đụng dữ liệu thật, không gọi mạng, không ghi Google Sheet).
 */
final class Simulator {
    interface Check { String verify(List<String> ev); }      // null = đạt, ngược lại là lý do

    static final class Scenario {
        String name, expect, so;
        OrderCheck.Expect exp;
        final List<List<String>> screens = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();       // chờ SAU mỗi màn hình
        Check check;
    }

    interface Listener {
        void onResult(int index, Scenario s, String failReason, List<String> events);
        void onAll(int pass, int total);
    }

    // ---------- mô tả pallet trên màn hình ----------
    private static final class Pl { String id; int qty; String sku; boolean full; }

    private static String name(String sku) {
        switch (sku) {
            case "10000001": return "BIA LON A";
            case "10000002": return "BIA CHAI B";
            case "10000003": return "NUOC NGOT C";
            default: return "HANG LA";
        }
    }

    /** Dựng 1 kịch bản theo kiểu: .total().add().step()... */
    private static final class B {
        final Scenario s = new Scenario();
        final List<Pl> shown = new ArrayList<>();
        int total; String err; boolean sx;
        int seq = 0;
        B(int idx, String name, String expect) {
            s.name = name; s.expect = expect; s.so = String.format(Locale.ROOT, "9900%04d", idx);
        }
        B line(String sku, int qty, int full, int per) {
            if (s.exp == null) s.exp = new OrderCheck.Expect();
            OrderCheck.Line l = new OrderCheck.Line();
            l.sku = sku; l.name = name(sku); l.qty = qty; l.fullQty = full; l.perPallet = per;
            l.maxPallets = per > 0 ? full / per : -1;
            s.exp.lines.put(sku, l);
            return this;
        }
        B total(int t) { total = t; return this; }
        B add(String sku, int qty) { Pl p = new Pl(); p.id = String.format(Locale.ROOT, "88%06d%06d", s.so.hashCode() & 0xFFFF, ++seq);
            p.sku = sku; p.qty = qty; p.full = true; shown.add(p); return this; }
        /** Pallet vừa quét nhưng PDA chưa hiện mã hàng/số lượng. */
        B addPartial(String sku, int qty) { add(sku, qty); shown.get(shown.size() - 1).full = false; return this; }
        B complete() { for (Pl p : shown) p.full = true; return this; }
        B removeLast() { shown.remove(shown.size() - 1); return this; }
        B error(String e) { err = e; return this; }
        /** PDA báo "đã được ghi nhận" cho pallet thứ n đang hiện (quét trùng). */
        B errorDup(int n) { err = "Pallet ID " + shown.get(n - 1).id + " đã được ghi nhận"; return this; }
        /** Chuyển loại màn hình: true = NHẬP SẢN XUẤT, false = XUẤT HÀNG (xóa danh sách pallet đang hiện). */
        B screen(boolean sxMode) { sx = sxMode; shown.clear(); err = null; return this; }
        B step(long waitMs) {
            List<String> t = new ArrayList<>();
            if (sx) {
                t.add("NHẬP SẢN XUẤT");
                t.add("Work Order:"); t.add(s.so);
                t.add("Sản phẩm:"); t.add("10136265 - BIA VIET RB Crate 20x35,5cl 2 VN");
                t.add("Tổng SL Pallet"); t.add("đã quét:"); t.add(String.valueOf(shown.size()));
                t.add("Vị trí:"); t.add("HMF30");
                if (err != null) t.add(err);
                for (int i = shown.size() - 1; i >= 0; i--) {
                    Pl p = shown.get(i);
                    t.add("89" + p.id);
                    t.add("Số lượng: " + p.qty + " Crate");
                    t.add("Lot No.: L6322704E0");
                }
                s.screens.add(t); s.delays.add(waitMs);
                return this;
            }
            t.add("Sales Order: " + s.so);
            t.add("Đã quét: " + shown.size());
            t.add("Tổng SL Pallet: " + total);
            if (err != null) t.add(err);
            for (int i = shown.size() - 1; i >= 0; i--) {       // pallet mới nhất nằm trên cùng
                Pl p = shown.get(i);
                t.add(p.id);
                if (p.full) {
                    t.add("/" + p.qty + " Tray");
                    t.add("Vị trí: A-01");
                    t.add("Sản phẩm: " + p.sku + " - " + name(p.sku));
                }
            }
            s.screens.add(t); s.delays.add(waitMs);
            return this;
        }
        B check(Check c) { s.check = c; return this; }
        Scenario done() { return s; }
    }

    // ---------- kiểm tra ----------
    private static int n(List<String> ev, String x) { int c = 0; for (String e : ev) if (e.equals(x)) c++; return c; }
    private static boolean has(List<String> ev, String x) { return n(ev, x) > 0; }
    private static boolean anyBeep(List<String> ev) { for (String e : ev) if (e.startsWith("BIP:")) return true; return false; }

    private static String join(String a, String b) { return a == null ? b : b == null ? a : a + "; " + b; }

    static List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<>();
        final String A = "10000001", Bk = "10000002", C = "10000003", X = "10000099";

        out.add(new B(1, "1. Quét đúng, đủ đơn", "Không bíp, ra ✅ KHỚP GR")
                .line(A, 320, 320, 160).total(2).step(150)
                .add(A, 160).step(200).add(A, 160).step(400)
                .check(ev -> {
                    String r = null;
                    if (anyBeep(ev)) r = join(r, "đã bíp nhầm");
                    if (!has(ev, "REC:TONG_KET")) r = join(r, "không ra tổng kết KHỚP");
                    if (has(ev, "REC:TONG_KET_LECH")) r = join(r, "báo lệch sai");
                    return r;
                }).done());

        out.add(new B(2, "2. Sai mã hàng", "Bíp ĐỎ (SAI), ghi SAI_HANG")
                .line(A, 320, 320, 160).total(2).step(150)
                .add(X, 160).step(500)
                .check(ev -> has(ev, "BIP:SAI") && has(ev, "REC:SAI_HANG") ? null : "không bíp đỏ / không ghi SAI_HANG").done());

        out.add(new B(3, "3. Thừa số lượng", "Pallet thứ 3 bíp ĐỎ (THỪA)")
                .line(A, 320, 320, 160).total(3).step(150)
                .add(A, 160).step(200).add(A, 160).step(200).add(A, 160).step(500)
                .check(ev -> has(ev, "BIP:SAI") && has(ev, "REC:THUA_SL") ? null : "không báo THỪA").done());

        out.add(new B(4, "4. Pallet không tồn tại (lỗi từ PDA)", "Bíp ĐỎ (LỖI), ghi LOI")
                .line(A, 320, 320, 160).total(2).step(150)
                .error("Pallet ID 88999999999999 không tồn tại").step(600)
                .check(ev -> has(ev, "BIP:LOI") && has(ev, "REC:LOI") ? null
                        : "không bíp – kiểm tra Từ khóa lỗi có dòng: khong ton tai").done());

        out.add(new B(5, "5. Đơn chưa có dữ liệu GR", "Bíp CAM 1 lần cho cả đơn, không bíp đỏ")
                .total(2).step(150)
                .add(A, 160).step(300).add(A, 160).step(500)
                .check(ev -> {
                    if (has(ev, "BIP:SAI")) return "bíp đỏ nhầm";
                    if (n(ev, "BIP:CANH_BAO") != 1) return "bíp cam " + n(ev, "BIP:CANH_BAO") + " lần (cần 1)";
                    return null;
                }).done());

        out.add(new B(6, "6. Hàng lẻ (GR 400, chẵn pallet 320)", "Quét 2 pallet là ĐỦ, phần lẻ không tính thiếu")
                .line(A, 400, 320, 160).total(2).step(150)
                .add(A, 160).step(200).add(A, 160).step(500)
                .check(ev -> {
                    if (anyBeep(ev)) return "đã bíp nhầm";
                    return has(ev, "REC:TONG_KET") && !has(ev, "REC:TONG_KET_LECH") ? null : "đáng lẽ KHỚP, nhưng báo lệch/không tổng kết";
                }).done());

        out.add(new B(7, "7. Đủ số pallet nhưng thiếu số lượng", "Không bíp đỏ; tổng kết LỆCH GR")
                .line(A, 480, 480, 160).total(3).step(150)
                .add(A, 160).step(200).add(A, 160).step(200).add(A, 100).step(500)
                .check(ev -> has(ev, "REC:TONG_KET_LECH") && !has(ev, "BIP:SAI") ? null : "không báo lệch GR (hoặc bíp đỏ nhầm)").done());

        out.add(new B(8, "8. Quét nhanh 2 pallet/lần", "Không bíp, cuối cùng KHỚP (không báo thiếu sai)")
                .line(A, 640, 640, 160).total(4).step(150)
                .add(A, 160).addPartial(A, 160).step(120).complete().step(300)
                .add(A, 160).addPartial(A, 160).step(120).complete().step(600)
                .check(ev -> {
                    if (anyBeep(ev)) return "đã bíp nhầm";
                    return has(ev, "REC:TONG_KET") && !has(ev, "REC:TONG_KET_LECH") && !has(ev, "REC:TONG_KET_CHUA_DU")
                            ? null : "không ra tổng kết KHỚP";
                }).done());

        out.add(new B(9, "9. Pallet hiện chậm rồi mới rõ", "Không bíp, đọc bù thành công")
                .line(A, 480, 480, 160).total(3).step(150)
                .addPartial(A, 160).step(400).complete().step(300)
                .add(A, 160).step(200).add(A, 160).step(500)
                .check(ev -> {
                    if (anyBeep(ev)) return "đã bíp nhầm";
                    return has(ev, "REC:TONG_KET") ? null : "không ra tổng kết KHỚP";
                }).done());

        out.add(new B(10, "10. Pallet không bao giờ hiện rõ", "Sau ~2,5 giây bíp CAM")
                .line(A, 480, 480, 160).total(3).step(150)
                .addPartial(A, 160).step(3400)
                .check(ev -> has(ev, "BIP:CANH_BAO") && has(ev, "REC:OK_CHUA_DOI_CHIEU") ? null : "không bíp cam").done());

        out.add(new B(11, "11. Xóa pallet sai trên PDA", "Bíp ĐỎ rồi tự tắt khi xóa")
                .line(A, 320, 320, 160).total(2).step(150)
                .add(X, 160).step(500).removeLast().step(500)
                .check(ev -> has(ev, "BIP:SAI") && has(ev, "REC:XOA") && has(ev, "STOP") ? null : "không bíp đỏ hoặc không tắt khi xóa").done());

        B big = new B(12, "12. Đơn 22 pallet (14+5+3) quét nhanh", "Không bíp, cuối cùng KHỚP 22/22")
                .line(A, 1400, 1400, 100).line(Bk, 500, 500, 100).line(C, 300, 300, 100).total(22).step(150);
        for (int i = 1; i <= 22; i += 2) {
            String sk1 = i <= 14 ? A : i <= 19 ? Bk : C;
            String sk2 = (i + 1) <= 14 ? A : (i + 1) <= 19 ? Bk : C;
            big.add(sk1, 100);
            if (i + 1 < 22) { big.addPartial(sk2, 100).step(120).complete().step(150); }
            else { big.add(sk2, 100).step(500); }
        }
        out.add(big.check(ev -> {
            if (anyBeep(ev)) return "đã bíp nhầm";
            return has(ev, "REC:TONG_KET") && !has(ev, "REC:TONG_KET_LECH") && !has(ev, "REC:TONG_KET_CHUA_DU")
                    ? null : "không ra tổng kết KHỚP 22/22";
        }).done());

        out.add(new B(13, "13. NHẬP SX: quét pallet bất kỳ", "Không bíp, mọi pallet được ghi nhận, không đối chiếu GR")
                .screen(true).step(150)
                .add(A, 70).step(200).add(X, 70).step(200).add(C, 35).step(500)
                .check(ev -> {
                    if (anyBeep(ev)) return "đã bíp nhầm";
                    if (n(ev, "REC:OK") != 3) return "ghi nhận " + n(ev, "REC:OK") + "/3 pallet";
                    return has(ev, "REC:SAI_HANG") || has(ev, "REC:THUA_SL") || has(ev, "REC:TONG_KET") ? "vẫn đối chiếu GR" : null;
                }).done());

        out.add(new B(14, "14. NHẬP SX: pallet không tồn tại", "Bíp ĐỎ (LỖI)")
                .screen(true).step(150).add(A, 70).step(200)
                .error("Pallet ID 88999999999999 không tồn tại").step(600)
                .check(ev -> has(ev, "BIP:LOI") && has(ev, "REC:LOI") ? null : "không bíp đỏ").done());

        out.add(new B(15, "15. NHẬP SX: bỏ pallet ra", "Ghi XÓA, không bíp")
                .screen(true).step(150).add(A, 70).step(200).add(A, 70).step(300).removeLast().step(500)
                .check(ev -> !anyBeep(ev) && has(ev, "REC:XOA") ? null : "không ghi XÓA hoặc bíp nhầm").done());

        out.add(new B(16, "16. Máy đổi từ NHẬP SX sang XUẤT HÀNG", "SX không bíp; sang xuất hàng sai mã thì bíp ĐỎ")
                .line(A, 320, 320, 160).screen(true).step(150).add(A, 70).step(200).add(X, 70).step(400)
                .screen(false).total(2).step(300).add(X, 160).step(600)
                .check(ev -> has(ev, "BIP:SAI") && has(ev, "REC:SAI_HANG") && n(ev, "REC:OK") == 2
                        ? null : "đổi chế độ sai (không bíp đỏ khi xuất hàng sai mã, hoặc SX ghi thiếu)").done());

        out.add(new B(17, "17. Quét trùng do bóp cò (trong 3 giây)", "Không bíp, ghi TRÙNG, giữ lần đầu")
                .line(A, 320, 320, 160).total(2).step(150)
                .add(A, 160).step(150).errorDup(1).step(500)
                .check(ev -> {
                    if (anyBeep(ev)) return "đã bíp (đáng lẽ im lặng)";
                    if (!has(ev, "REC:TRUNG")) return "không ghi TRÙNG – kiểm tra Từ khóa lỗi có dòng: da duoc ghi nhan";
                    return n(ev, "REC:OK") == 1 && !has(ev, "REC:LOI") ? null : "pallet bị tính lỗi / tính 2 lần";
                }).done());

        out.add(new B(18, "18. Quét lại pallet sau vài giây", "Bíp CAM nhẹ + ghi TRÙNG")
                .line(A, 320, 320, 160).total(2).step(150)
                .add(A, 160).step(3500).errorDup(1).step(600)
                .check(ev -> has(ev, "BIP:CANH_BAO") && has(ev, "REC:TRUNG") && !has(ev, "BIP:LOI") && !has(ev, "BIP:SAI") ? null
                        : "không bíp cam / không ghi TRÙNG").done());

        out.add(new B(19, "19. STT: pallet lỗi chiếm vị trí kế tiếp", "OK = 1,2,3; dòng LỖI mang STT 4")
                .screen(true).step(150).add(A, 70).step(200).add(A, 70).step(200).add(A, 70).step(300)
                .error("Pallet ID 88999999999999 không tồn tại").step(600)
                .check(ev -> has(ev, "STT:OK=1") && has(ev, "STT:OK=2") && has(ev, "STT:OK=3") && has(ev, "STT:LOI=4") ? null
                        : "STT sai: " + ev).done());

        out.add(new B(20, "20. STT: bỏ pallet ra rồi quét lại", "Pallet mới lấy lại STT cũ")
                .line(A, 640, 640, 160).total(4).step(150)
                .add(A, 160).step(200).add(A, 160).step(200).add(A, 160).step(300)
                .removeLast().step(300).add(A, 160).step(500)
                .check(ev -> has(ev, "STT:XOA=3") && n(ev, "STT:OK=3") == 2 ? null : "STT sai: " + ev).done());
        return out;
    }

    // ---------- chạy ----------
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean cancelled;

    void cancel() { cancelled = true; }

    void run(ErrorWatcherService svc, boolean sound, Listener l) {
        List<Scenario> all = scenarios();
        int[] pass = {0};
        runOne(svc, sound, all, 0, pass, l);
    }

    private void runOne(ErrorWatcherService svc, boolean sound, List<Scenario> all, int idx, int[] pass, Listener l) {
        if (cancelled) return;
        if (idx >= all.size()) { l.onAll(pass[0], all.size()); return; }
        Scenario s = all.get(idx);
        svc.simBegin(s.so, s.exp, sound);
        playStep(svc, s, 0, () -> {
            List<String> ev = svc.simEnd(s.so);
            String why = s.check.verify(ev);
            if (why == null) pass[0]++;
            l.onResult(idx, s, why, ev);
            h.postDelayed(() -> runOne(svc, sound, all, idx + 1, pass, l), 250);
        });
    }

    private void playStep(ErrorWatcherService svc, Scenario s, int i, Runnable done) {
        if (cancelled) return;
        if (i >= s.screens.size()) { done.run(); return; }
        svc.simScreen(s.screens.get(i));
        h.postDelayed(() -> playStep(svc, s, i + 1, done), s.delays.get(i));
    }
}
