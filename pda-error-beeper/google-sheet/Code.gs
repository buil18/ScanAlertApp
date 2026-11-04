/**
 * Nhận log quét từ app PDA Error Beeper và ghi vào Google Sheet.
 * Dán toàn bộ file này vào: Google Sheet › Tiện ích mở rộng › Apps Script.
 */

// ĐỔI mã này thành một chuỗi bí mật của bạn và nhập y hệt vào app trên máy quét.
const TOKEN = 'doi-ma-bi-mat-nay-123';

const SHEET_NAME = 'ScanLog';
const HEADER = ['Thời gian quét', 'Máy / Người quét', 'Kết quả', 'Mã quét', 'Ô nhập', 'Thông báo', 'Nhận lúc'];

function doPost(e) {
  let data;
  try {
    data = JSON.parse(e.postData.contents);
  } catch (err) {
    return reply('error: bad json');
  }
  if (data.token !== TOKEN) return reply('error: sai token');
  const rows = (data.rows || []).filter(r => Array.isArray(r) && r.length >= 6);
  if (rows.length === 0) return reply('ok 0');

  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const sh = getSheet_();
    const now = new Date();
    const values = rows.map(r => r.slice(0, 6).map(v => String(v)).concat([now]));
    sh.getRange(sh.getLastRow() + 1, 1, values.length, HEADER.length).setValues(values);
  } finally {
    lock.releaseLock();
  }
  return reply('ok ' + rows.length);
}

// Mở link Web App trên trình duyệt để kiểm tra đã triển khai đúng chưa.
function doGet() {
  return reply('ok – Web App đang chạy. Sheet: ' + SHEET_NAME);
}

function getSheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(SHEET_NAME);
  if (sh) return sh;

  sh = ss.insertSheet(SHEET_NAME);
  sh.getRange(1, 1, 1, HEADER.length).setValues([HEADER])
    .setFontWeight('bold').setBackground('#1b5e20').setFontColor('#ffffff');
  sh.setFrozenRows(1);
  sh.getRange('D:D').setNumberFormat('@');            // giữ nguyên mã quét (số 0 ở đầu…)
  sh.getRange('G:G').setNumberFormat('dd/MM/yyyy HH:mm:ss');
  sh.setColumnWidths(1, HEADER.length, 150);
  sh.setColumnWidth(6, 320);

  // Tô đỏ dòng LOI, tô xanh dòng OK
  const all = sh.getRange('A2:G');
  sh.setConditionalFormatRules([
    SpreadsheetApp.newConditionalFormatRule().whenFormulaSatisfied('=$C2="LOI"')
      .setBackground('#ffcdd2').setRanges([all]).build(),
    SpreadsheetApp.newConditionalFormatRule().whenFormulaSatisfied('=$C2="OK"')
      .setBackground('#e8f5e9').setRanges([all]).build(),
  ]);
  return sh;
}

function reply(text) {
  return ContentService.createTextOutput(text).setMimeType(ContentService.MimeType.TEXT);
}
