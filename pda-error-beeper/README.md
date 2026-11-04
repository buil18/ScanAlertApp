# PDA Error Beeper (Honeywell CK65 / Android 13)

App chạy ngầm bằng **Dịch vụ Trợ năng (Accessibility Service)**. Khi ứng dụng
`com.heineken.cmobilepda` hiện hộp thoại hoặc Toast có chữ chứa từ khóa lỗi
(mặc định: `khong ton tai`, `not exist`, `not found`), app phát **bíp lớn trên luồng ALARM**
và rung. Hộp thoại đóng lại thì tiếng bíp dừng.

- Không sửa phần mềm Heineken PDA, không cần quyền root, không gửi dữ liệu ra ngoài (không có quyền INTERNET).
- So khớp từ khóa **không phân biệt hoa/thường và dấu tiếng Việt** ("Pallet KHÔNG tồn tại" khớp với `khong ton tai`).
- Từ khóa, tên gói, thời gian bíp sửa ngay trên máy, không cần build lại.
- **Chế độ dò**: ghi lại chữ mà app đọc được trên màn hình Heineken PDA, để chọn từ khóa chính xác.

## 1. Build APK (không cần cài gì trên laptop)
1. Tạo repo mới trên github.com, tải toàn bộ thư mục này lên (*Add file → Upload files*, hoặc dùng Codespaces/`git push`).
2. Tab **Actions** → workflow *Build APK* tự chạy (~3–5 phút).
3. Tạo bản phát hành để máy quét tải trực tiếp: tab **Releases → Draft a new release → tag `v1.0`** → Publish
   (hoặc `git tag v1.0 && git push --tags`). Workflow sẽ đính kèm `pda-error-beeper.apk`.
4. Link tải cố định: `https://github.com/<user>/<repo>/releases/latest/download/pda-error-beeper.apk`
   (repo **public** mới tải được mà không cần đăng nhập; repo private thì phải đăng nhập GitHub trên máy quét).

## 2. Cài trên CK65 (Android 13)
1. Mở link trên bằng Chrome trên CK65 → tải → cài (cho phép Chrome *cài ứng dụng không rõ nguồn gốc* nếu được hỏi).
2. Mở app **PDA Error Beeper** → bấm **1. Mở cài đặt Trợ năng** → chọn *PDA Error Beeper*.
   Nếu công tắc bị mờ / báo "Cài đặt bị hạn chế":
   - Quay lại app → bấm **Mở Thông tin ứng dụng** → menu **⋮** góc trên phải → **Cho phép cài đặt bị hạn chế** (nhập PIN nếu hỏi).
   - Vào lại Trợ năng → bật *PDA Error Beeper* → Cho phép.
3. Trong app bấm **Thử tiếng bíp** để kiểm tra loa.
4. Tắt tối ưu pin cho app (Cài đặt → Ứng dụng → PDA Error Beeper → Pin → *Không hạn chế*) để hệ thống không tắt dịch vụ.

## 3. Hiệu chỉnh từ khóa (nên làm lần đầu)
1. Tick **Chế độ dò** → Lưu.
2. Mở Heineken PDA, cố tình quét một pallet sai để hiện lỗi.
3. Quay lại app → **Làm mới nhật ký** → xem dòng `[dò] SCREEN ...` / `[dò] TOAST ...` để biết chính xác chữ lỗi.
4. Nhập từ khóa phù hợp (mỗi dòng một từ), bỏ tick Chế độ dò → Lưu.
   Nếu thấy dòng `BÍP ← ...` nghĩa là đã bắt được.

> Nếu nhật ký dò **không có chữ nào** của màn hình lỗi, có thể Heineken PDA vẽ giao diện bằng
> WebView/Canvas không lộ chữ cho Trợ năng – xem mục "Rủi ro" trong tài liệu review.

## Cấu trúc
```
app/src/main/java/com/tools/pdabeeper/
  ErrorWatcherService.java  – dịch vụ Trợ năng: quét cửa sổ + Toast của gói mục tiêu
  Alarm.java                – bíp ToneGenerator trên STREAM_ALARM, tạm đặt âm lượng tối đa rồi trả lại
  Prefs.java                – cấu hình, chuẩn hóa tiếng Việt, nhật ký
  MainActivity.java         – màn hình cấu hình/kiểm tra
app/src/main/res/xml/accessibility_config.xml
.github/workflows/build-apk.yml
app/signing.keystore        – khóa ký cố định (để cài bản mới đè bản cũ). Chỉ dùng nội bộ.
```
