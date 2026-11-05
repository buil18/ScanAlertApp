/**
 * Nhận log quét từ app PDA Error Beeper và ghi vào Google Sheet.
 * Dán toàn bộ file này vào: Google Sheet › Tiện ích mở rộng › Apps Script.
 */

// ĐỔI mã này thành một chuỗi bí mật của bạn và nhập y hệt vào app trên máy quét.
const TOKEN = 'doi-ma-bi-mat-nay-123';

const SHEET_NAME = 'ScanLog';
const HEADER = ['Thời gian quét', 'Máy / Người quét', 'Kết quả', 'Mã quét', 'Đơn hàng / Xe', 'Thông báo', 'Pallet ID', 'Số lượng', 'Đơn vị', 'Vị trí', 'Mã SP', 'Tên SP', 'Nhận lúc'];

function doPost(e) {
  let data;
  try {
    data = JSON.parse(e.postData.contents);
  } catch (err) {
    return reply('error: bad json');
  }
  if (data.token !== TOKEN) return reply('error: sai token');
  if (data.op === 'pdagr') return reply(pdaPutOrder(data.so, data.lines));
  const rows = (data.rows || []).filter(r => Array.isArray(r) && r.length >= 6);
  if (rows.length === 0) return reply('ok 0');

  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const sh = getSheet_();
    const now = new Date();
    const values = rows.map(r => {
      const v = r.slice(0, 12).map(x => String(x));
      while (v.length < 12) v.push('');
      return v.concat([now]);
    });
    sh.getRange(sh.getLastRow() + 1, 1, values.length, HEADER.length).setValues(values);
  } finally {
    lock.releaseLock();
  }
  return reply('ok ' + rows.length);
}

// Mở link Web App trên trình duyệt để kiểm tra đã triển khai đúng chưa.
function doGet(e) {
  const p = (e && e.parameter) || {};
  if (p.op === 'pda') {
    if (p.token !== TOKEN) return json_({ ok: false, reason: 'sai token' });
    return json_(pdaGetOrder_(String(p.so || '').trim()));
  }
  return reply('ok – Web App đang chạy. Sheet: ' + SHEET_NAME);
}

function getSheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sh = ss.getSheetByName(SHEET_NAME) || ss.insertSheet(SHEET_NAME);
  ensureHeader_(sh);
  return sh;
}

/** Nếu dòng 1 chưa phải tiêu đề -> chèn tiêu đề + định dạng (không mất dữ liệu cũ). */
function ensureHeader_(sh) {
  const first = sh.getRange(1, 1).getValue();
  if (first === HEADER[0]) return;
  if (sh.getLastRow() > 0) sh.insertRowBefore(1);
  sh.getRange(1, 1, 1, HEADER.length).setValues([HEADER])
    .setFontWeight('bold').setBackground('#1b5e20').setFontColor('#ffffff');
  sh.setFrozenRows(1);
  sh.getRange('D:D').setNumberFormat('@');            // giữ nguyên mã quét (số 0 ở đầu…)
  sh.getRange('M:M').setNumberFormat('dd/MM/yyyy HH:mm:ss');
  sh.setColumnWidths(1, HEADER.length, 150);
  sh.setColumnWidth(6, 320);

  // Tô đỏ dòng LOI, tô xanh dòng OK
  const all = sh.getRange('A2:M');
  sh.setConditionalFormatRules([
    SpreadsheetApp.newConditionalFormatRule()
      .whenFormulaSatisfied('=OR(LEFT($C2,3)="SAI",LEFT($C2,4)="THUA")')
      .setBackground('#e53935').setFontColor('#ffffff').setRanges([all]).build(),
    SpreadsheetApp.newConditionalFormatRule().whenFormulaSatisfied('=$C2="LOI"')
      .setBackground('#ffcdd2').setRanges([all]).build(),
    SpreadsheetApp.newConditionalFormatRule()
      .whenFormulaSatisfied('=OR(LEFT($C2,4)="LECH",$C2="TONG_KET_LECH",$C2="OK_CHUA_DOI_CHIEU")')
      .setBackground('#ffe0b2').setRanges([all]).build(),
    SpreadsheetApp.newConditionalFormatRule().whenFormulaSatisfied('=LEFT($C2,2)="OK"')
      .setBackground('#e8f5e9').setRanges([all]).build(),
    SpreadsheetApp.newConditionalFormatRule().whenFormulaSatisfied('=$C2="TONG_KET"')
      .setBackground('#a5d6a7').setFontWeight('bold').setRanges([all]).build(),
  ]);
}

/** Chạy tay 1 lần sau khi cập nhật script: áp lại màu cho các loại kết quả mới. */
function capNhatDinhDang() {
  const sh = getSheet_();
  sh.getRange(1, 1).setValue('');      // buộc ensureHeader_ chạy lại phần định dạng
  sh.deleteRow(1);
  ensureHeader_(sh);
}

/** Chạy tay 1 lần trong trình soạn thảo Apps Script để tạo tiêu đề ngay. */
function caiDatTieuDe() {
  getSheet_();
}

// =====================================================================
//  CỔNG DỮ LIỆU GR CHO PDA  (op=pda)
//  Tab PDA_GR : SO | Mã hàng | Tên hàng | Số lượng (thùng) | Ghi chú | Cập nhật lúc
//  Tab Master : cột A mã hàng, cột E số thùng / pallet  (giống Master của tool đối soát)
//    – Có thể để Master ở file khác: Cài đặt dự án › Thuộc tính tập lệnh › MASTER_SHEET_ID = <id file>
// =====================================================================

const PDA_TAB = 'PDA_GR';
const PDA_HEADER = ['SO', 'Mã hàng', 'Tên hàng', 'Số lượng (thùng)', 'Ghi chú', 'Cập nhật lúc'];
const MASTER_TAB = 'Master';

function pdaGetOrder_(so) {
  if (!so) return { ok: false, reason: 'thiếu số SO' };
  const sh = pdaSheet_();
  const values = sh.getLastRow() > 1 ? sh.getRange(2, 1, sh.getLastRow() - 1, 6).getValues() : [];
  const bySku = {};
  let updated = '';
  values.forEach(r => {
    if (String(r[0]).trim() !== so) return;          // khớp CHÍNH XÁC số đơn, không đoán
    const sku = String(r[1]).trim();
    if (!sku) return;
    const l = bySku[sku] || (bySku[sku] = { sku: sku, name: String(r[2] || ''), qty: 0 });
    l.qty += Number(r[3]) || 0;
    if (r[5]) updated = r[5] instanceof Date
      ? Utilities.formatDate(r[5], Session.getScriptTimeZone(), 'dd/MM HH:mm') : String(r[5]);
  });
  const lines = Object.keys(bySku).map(k => bySku[k]);       // {sku, name, qty} – so trực tiếp với số lượng trên PDA
  if (lines.length === 0) return { ok: false, reason: 'Chưa có dữ liệu GR cho SO ' + so };
  const total = lines.reduce((t, l) => t + l.qty, 0);
  return { ok: true, so: so, updated: updated, totalQty: total, lines: lines };
}

/** Ghi (thay thế) dữ liệu GR của 1 SO. Dùng cho bước 2: tool đối soát đẩy sang. */
function pdaPutOrder(so, lines) {
  so = String(so || '').trim();
  if (!so || !Array.isArray(lines)) return 'error: thiếu so/lines';
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const sh = pdaSheet_();
    const last = sh.getLastRow();
    if (last > 1) {
      const col = sh.getRange(2, 1, last - 1, 1).getValues();
      for (let i = col.length - 1; i >= 0; i--) if (String(col[i][0]).trim() === so) sh.deleteRow(i + 2);
    }
    const now = new Date();
    const rows = lines.filter(l => l && l.sku)
      .map(l => [so, String(l.sku), String(l.name || ''), Number(l.qty) || 0, String(l.note || ''), now]);
    if (rows.length) sh.getRange(sh.getLastRow() + 1, 1, rows.length, 6).setValues(rows);
    return 'ok ' + rows.length;
  } finally {
    lock.releaseLock();
  }
}

function pdaSheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(PDA_TAB);
  if (!sh) {
    sh = ss.insertSheet(PDA_TAB);
    sh.getRange(1, 1, 1, PDA_HEADER.length).setValues([PDA_HEADER])
      .setFontWeight('bold').setBackground('#0d47a1').setFontColor('#ffffff');
    sh.setFrozenRows(1);
    sh.getRange('A:B').setNumberFormat('@');
    sh.getRange('F:F').setNumberFormat('dd/MM/yyyy HH:mm');
  }
  return sh;
}

/** mã hàng -> số thùng / pallet (cache 10 phút). */
function masterMap_() {
  const cache = CacheService.getScriptCache();
  const hit = cache.get('master_map');
  if (hit) return JSON.parse(hit);
  const id = PropertiesService.getScriptProperties().getProperty('MASTER_SHEET_ID');
  const ss = id ? SpreadsheetApp.openById(id) : SpreadsheetApp.getActiveSpreadsheet();
  const sh = ss.getSheetByName(MASTER_TAB);
  const map = {};
  if (sh && sh.getLastRow() > 1) {
    sh.getRange(2, 1, sh.getLastRow() - 1, 5).getValues().forEach(r => {
      const k = String(r[0]).trim(), v = Number(r[4]);
      if (k && v > 0) map[k] = v;
    });
  }
  cache.put('master_map', JSON.stringify(map), 600);
  return map;
}

/** Chạy tay: tạo tab PDA_GR (nếu chưa có) để nhập thử dữ liệu. */
function taoTabPDA_GR() {
  pdaSheet_();
  CacheService.getScriptCache().remove('master_map');
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function reply(text) {
  return ContentService.createTextOutput(text).setMimeType(ContentService.MimeType.TEXT);
}
