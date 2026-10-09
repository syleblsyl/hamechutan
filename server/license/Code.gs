/**
 * שרת הרישיונות של "המחותן" — Google Apps Script המחובר לגיליון Google Sheets.
 *
 * כל לקוח הוא שורה בגיליון "לקוחות". האפליקציה שולחת לכאן רק שם משתמש, סיסמה (בכניסה בלבד),
 * מזהה מכשיר ושם הדגם. נתוני החתונה לעולם לא נשלחים לשרת.
 *
 * כללים:
 *  - כל תשובה נחתמת דיגיטלית במפתח פרטי (PRIVATE_KEY בסוף הקובץ). האפליקציה מאמתת את החתימה עם המפתח
 *    הציבורי, ולכן אי אפשר לזייף "אישור" גם דרך סינון או רשת שמיירטת תעבורה.
 *  - כל משתמש פעיל בטלפון אחד בלבד. כניסה מטלפון אחר נחסמת עד ששחררו את הטלפון בגיליון
 *    (תפריט "רישיונות" ← "שחרור הטלפון של הלקוח המסומן").
 *  - הסיסמאות נשמרות רק כגיבוב עם מלח (עמודות מוסתרות). אי אפשר לשחזר סיסמה, רק לאפס.
 *  - אחרי 5 ניסיונות שגויים המשתמש נחסם לכניסה למשך 15 דקות.
 *
 * הוראות התקנה מלאות: server/license/README.md
 */

// ------------------------------------------------------------------ הגדרות

/** קישור ההורדה שיוצג בהודעה ללקוח חדש. */
var DOWNLOAD_URL = 'https://github.com/syleblsyl/hamechutan-releases/releases/latest';

var LICENSE_SHEET = 'לקוחות';
var HEADERS = ['שם משתמש', 'סטטוס', 'תוקף עד', 'טלפון מחובר', 'מזהה מכשיר', 'הופעל בתאריך', 'נראה לאחרונה', 'גרסה', 'הערות', 'hash', 'salt'];
var COL = { USER: 1, STATUS: 2, EXPIRES: 3, DEVICE_NAME: 4, DEVICE_ID: 5, ACTIVATED: 6, LAST_SEEN: 7, VERSION: 8, NOTES: 9, HASH: 10, SALT: 11 };
var NUM_COLS = HEADERS.length;
var STATUS_ACTIVE = 'פעיל';
var STATUS_BLOCKED = 'חסום';
var PASSWORD_DIGITS = 8;
var HASH_ITERATIONS = 1000;
var MAX_FAILS = 5;
var FAIL_WINDOW_SEC = 15 * 60;
var PROTOCOL = 1;

// ------------------------------------------------------------------ Web app

/** בדיקת חיים: פתיחת כתובת השרת בדפדפן מציגה {"ok":true,...}. */
function doGet() {
  return json_({ ok: true, service: 'hamechutan-license', protocol: PROTOCOL });
}

function doPost(e) {
  var req;
  try {
    req = JSON.parse((e && e.postData && e.postData.contents) || '');
  } catch (err) {
    return json_(fail_('bad_request'));
  }
  try {
    return json_(handleRequest_(req, new Date()));
  } catch (err) {
    console.error('license request failed: ' + (err && err.stack || err));
    return json_(fail_('server_error'));
  }
}

/**
 * action = "activate": שם משתמש + סיסמה + מזהה מכשיר. משייך את המכשיר אם המשתמש פנוי.
 * action = "check": שם משתמש + מזהה מכשיר. מאשר שהמכשיר עדיין הוא המכשיר המשויך.
 */
function handleRequest_(req, now) {
  if (!req || typeof req !== 'object') return fail_('bad_request');
  var ctx = {
    action: String(req.action || ''),
    username: normUser_(req.username),
    deviceId: String(req.deviceId || '').trim(),
    nonce: String(req.nonce || ''),
    now: now
  };
  var deviceName = String(req.deviceName || '').trim().substring(0, 100);
  var appVersion = String(req.appVersion || '').trim().substring(0, 30);
  if (ctx.action !== 'activate' && ctx.action !== 'check') return fail_('bad_request');
  if (!ctx.username || ctx.username.length > 100 || !ctx.deviceId || ctx.deviceId.length > 200) return fail_('bad_request');
  if (!/^[A-Za-z0-9_-]{16,100}$/.test(ctx.nonce)) return fail_('bad_request');
  if (!PRIVATE_KEY) return fail_('not_configured');

  var cache = CacheService.getScriptCache();
  if (ctx.action === 'activate' && failCount_(cache, ctx.username) >= MAX_FAILS) return deny_(ctx, 'rate_limited');

  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    var sheet = sheet_();
    var row = findRow_(sheet, ctx.username);
    if (ctx.action === 'activate') {
      var password = String(req.password || '');
      if (!row || !checkPassword_(password, row.values[COL.SALT - 1], row.values[COL.HASH - 1])) {
        recordFail_(cache, ctx.username);
        return deny_(ctx, 'bad_credentials');
      }
      cache.remove(failKey_(ctx.username));
    } else if (!row) {
      return deny_(ctx, 'unknown_user');
    }

    var v = row.values;
    if (String(v[COL.STATUS - 1]).trim() === STATUS_BLOCKED) return deny_(ctx, 'blocked');
    if (isExpired_(v[COL.EXPIRES - 1], now)) return deny_(ctx, 'expired');

    var bound = String(v[COL.DEVICE_ID - 1] || '').trim();
    if (ctx.action === 'activate') {
      if (bound && bound !== ctx.deviceId) return deny_(ctx, 'other_device');
      if (!bound) sheet.getRange(row.index, COL.DEVICE_NAME, 1, 3).setValues([[deviceName, ctx.deviceId, now]]);
    } else {
      if (!bound) return deny_(ctx, 'not_activated');
      if (bound !== ctx.deviceId) return deny_(ctx, 'other_device');
    }
    sheet.getRange(row.index, COL.LAST_SEEN, 1, 2).setValues([[now, appVersion]]);
    return sign_(ctx, { ok: true, code: '', username: ctx.username, expires: formatDate_(v[COL.EXPIRES - 1]) });
  } finally {
    lock.releaseLock();
  }
}

/**
 * חתימה על התשובה. השורות החתומות קשורות לבקשה (nonce אקראי שהאפליקציה שולחת), ולכן אי אפשר
 * גם "לשדר שוב" תשובה ישנה.
 */
function sign_(ctx, res) {
  res.serverTime = ctx.now.getTime();
  res.protocol = PROTOCOL;
  var payload = [PROTOCOL, ctx.nonce, ctx.action, ctx.deviceId, res.ok ? '1' : '0', res.code, res.username, res.expires, String(res.serverTime)].join('\n');
  res.sig = Utilities.base64Encode(Utilities.computeRsaSha256Signature(payload, PRIVATE_KEY));
  return res;
}

function deny_(ctx, code) {
  return sign_(ctx, { ok: false, code: code, username: ctx.username, expires: '' });
}

// ------------------------------------------------------------------ תפריט בגיליון

function onOpen() {
  SpreadsheetApp.getUi().createMenu('רישיונות')
    .addItem('הוספת לקוח', 'menuAddCustomer')
    .addItem('איפוס סיסמה ללקוח המסומן', 'menuResetPassword')
    .addItem('שחרור הטלפון של הלקוח המסומן', 'menuReleaseDevice')
    .addItem('חסימה / ביטול חסימה של הלקוח המסומן', 'menuToggleBlock')
    .addSeparator()
    .addItem('הכנת הגיליון', 'menuSetup')
    .addToUi();
}

function menuSetup() {
  setupSheet_();
  SpreadsheetApp.getUi().alert('הגיליון "' + LICENSE_SHEET + '" מוכן.');
}

function menuAddCustomer() {
  var ui = SpreadsheetApp.getUi();
  var r1 = ui.prompt('הוספת לקוח', 'שם משתמש (מומלץ: מספר הטלפון של הלקוח)', ui.ButtonSet.OK_CANCEL);
  if (r1.getSelectedButton() !== ui.Button.OK) return;
  var r2 = ui.prompt('הוספת לקוח', 'הערות (שם הלקוח, טלפון וכו׳). אפשר להשאיר ריק.', ui.ButtonSet.OK_CANCEL);
  if (r2.getSelectedButton() !== ui.Button.OK) return;
  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  var res;
  try {
    res = createCustomer_(sheet_(), r1.getResponseText(), r2.getResponseText(), new Date());
  } catch (err) {
    ui.alert('לא נוסף', String(err.message || err), ui.ButtonSet.OK);
    return;
  } finally {
    lock.releaseLock();
  }
  ui.alert('הלקוח נוסף', customerMessage_(res.username, res.password) +
    '\n\n— הסיסמה לא תוצג שוב. אם תאבד, אפשר לאפס אותה מהתפריט.', ui.ButtonSet.OK);
}

function menuResetPassword() {
  withSelectedCustomer_(function (sheet, rowIndex, username, ui) {
    if (ui.alert('איפוס סיסמה', 'ליצור סיסמה חדשה ל-' + username + '?\nהסיסמה הישנה תפסיק לעבוד.', ui.ButtonSet.YES_NO) !== ui.Button.YES) return;
    var password = resetPassword_(sheet, rowIndex);
    ui.alert('סיסמה חדשה', customerMessage_(username, password), ui.ButtonSet.OK);
  });
}

function menuReleaseDevice() {
  withSelectedCustomer_(function (sheet, rowIndex, username, ui) {
    var deviceName = sheet.getRange(rowIndex, COL.DEVICE_NAME).getValue();
    if (!sheet.getRange(rowIndex, COL.DEVICE_ID).getValue()) { ui.alert('ללקוח ' + username + ' אין טלפון מחובר.'); return; }
    if (ui.alert('שחרור טלפון', 'לשחרר את הטלפון "' + deviceName + '" של ' + username + '?\n\n' +
      'הטלפון הנוכחי יתנתק בבדיקה הבאה שלו, והלקוח יוכל להתחבר מטלפון אחר.', ui.ButtonSet.YES_NO) !== ui.Button.YES) return;
    releaseDevice_(sheet, rowIndex);
    ui.alert('הטלפון שוחרר. הלקוח יכול להתחבר עכשיו מטלפון אחר.');
  });
}

function menuToggleBlock() {
  withSelectedCustomer_(function (sheet, rowIndex, username, ui) {
    var cell = sheet.getRange(rowIndex, COL.STATUS);
    var blocked = String(cell.getValue()).trim() === STATUS_BLOCKED;
    cell.setValue(blocked ? STATUS_ACTIVE : STATUS_BLOCKED);
    ui.alert(blocked ? 'החסימה של ' + username + ' בוטלה.' : username + ' נחסם. האפליקציה שלו תינעל בבדיקה הבאה.');
  });
}

// ------------------------------------------------------------------ פעולות על הגיליון

function sheet_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  return ss.getSheetByName(LICENSE_SHEET) || setupSheet_();
}

function setupSheet_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName(LICENSE_SHEET) || ss.insertSheet(LICENSE_SHEET);
  sheet.getRange(1, 1, 1, NUM_COLS).setValues([HEADERS]).setFontWeight('bold').setBackground('#efe7d6');
  sheet.setFrozenRows(1);
  sheet.setRightToLeft(true);
  var rule = SpreadsheetApp.newDataValidation().requireValueInList([STATUS_ACTIVE, STATUS_BLOCKED], true).build();
  sheet.getRange(2, COL.STATUS, sheet.getMaxRows() - 1, 1).setDataValidation(rule);
  sheet.getRange(2, COL.EXPIRES, sheet.getMaxRows() - 1, 1).setNumberFormat('dd/mm/yyyy');
  sheet.getRange(2, COL.ACTIVATED, sheet.getMaxRows() - 1, 2).setNumberFormat('dd/mm/yyyy hh:mm');
  sheet.getRange(2, COL.USER, sheet.getMaxRows() - 1, 1).setNumberFormat('@'); // שמירת אפס מוביל במספרי טלפון
  sheet.setColumnWidth(COL.USER, 140);
  sheet.setColumnWidth(COL.DEVICE_NAME, 170);
  sheet.setColumnWidth(COL.NOTES, 220);
  sheet.hideColumns(COL.DEVICE_ID);
  sheet.hideColumns(COL.HASH, 2);
  return sheet;
}

/** מוסיף לקוח ומחזיר {username, password}. זורק שגיאה עם הודעה בעברית אם השם לא תקין או תפוס. */
function createCustomer_(sheet, rawUsername, notes, now) {
  var username = normUser_(rawUsername);
  if (!username) throw new Error('יש להזין שם משתמש.');
  if (username.length > 100) throw new Error('שם המשתמש ארוך מדי.');
  if (findRow_(sheet, username)) throw new Error('שם המשתמש ' + username + ' כבר קיים.');
  var password = newPassword_();
  var salt = newSalt_();
  var row = [];
  for (var i = 0; i < NUM_COLS; i++) row.push('');
  row[COL.USER - 1] = username;
  row[COL.STATUS - 1] = STATUS_ACTIVE;
  row[COL.NOTES - 1] = String(notes || '').trim();
  row[COL.HASH - 1] = hashPassword_(password, salt);
  row[COL.SALT - 1] = salt;
  sheet.appendRow(row);
  return { username: username, password: password };
}

function resetPassword_(sheet, rowIndex) {
  var password = newPassword_();
  var salt = newSalt_();
  sheet.getRange(rowIndex, COL.HASH, 1, 2).setValues([[hashPassword_(password, salt), salt]]);
  return password;
}

function releaseDevice_(sheet, rowIndex) {
  sheet.getRange(rowIndex, COL.DEVICE_NAME, 1, 3).setValues([['', '', '']]);
}

function withSelectedCustomer_(fn) {
  var ui = SpreadsheetApp.getUi();
  var sheet = SpreadsheetApp.getActiveSheet();
  var rowIndex = sheet.getActiveRange() ? sheet.getActiveRange().getRow() : 0;
  if (sheet.getName() !== LICENSE_SHEET || rowIndex < 2 || rowIndex > sheet.getLastRow()) {
    ui.alert('סמנו קודם תא בשורה של הלקוח בגיליון "' + LICENSE_SHEET + '".');
    return;
  }
  var username = String(sheet.getRange(rowIndex, COL.USER).getValue());
  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try { fn(sheet, rowIndex, username, ui); } finally { lock.releaseLock(); }
}

function customerMessage_(username, password) {
  return 'פרטי כניסה לאפליקציית "המחותן":\n' +
    'שם משתמש: ' + username + '\n' +
    'סיסמה: ' + password + '\n' +
    'הורדה: ' + DOWNLOAD_URL + '\n' +
    'החשבון פעיל בטלפון אחד בלבד.';
}

// ------------------------------------------------------------------ עזרים

function findRow_(sheet, username) {
  var last = sheet.getLastRow();
  if (last < 2) return null;
  var data = sheet.getRange(2, 1, last - 1, NUM_COLS).getValues();
  for (var i = 0; i < data.length; i++) {
    if (normUser_(data[i][COL.USER - 1]) === username) return { index: i + 2, values: data[i] };
  }
  return null;
}

/** אותיות קטנות, בלי רווחים ומקפים: "050-123 4567" ו-"0501234567" הם אותו משתמש. */
function normUser_(u) {
  return String(u == null ? '' : u).toLowerCase().replace(/[\s\-‏‎]/g, '');
}

function isExpired_(cell, now) {
  var d = toDate_(cell);
  if (!d) return false;
  var endOfDay = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1).getTime();
  return now.getTime() >= endOfDay;
}

function toDate_(cell) {
  if (cell instanceof Date) return isNaN(cell.getTime()) ? null : cell;
  var s = String(cell == null ? '' : cell).trim();
  var m = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(s);
  if (m) return new Date(+m[1], +m[2] - 1, +m[3]);
  m = /^(\d{1,2})[\/.](\d{1,2})[\/.](\d{4})$/.exec(s);
  if (m) return new Date(+m[3], +m[2] - 1, +m[1]);
  return null;
}

function formatDate_(cell) {
  var d = toDate_(cell);
  if (!d) return '';
  return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
}

function hashPassword_(password, salt) {
  var bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, salt + ':' + password, Utilities.Charset.UTF_8);
  for (var i = 1; i < HASH_ITERATIONS; i++) bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, bytes);
  return toHex_(bytes);
}

function checkPassword_(password, salt, hash) {
  if (!password || !salt || !hash) return false;
  return hashPassword_(password, String(salt)) === String(hash);
}

function toHex_(bytes) {
  var s = '';
  for (var i = 0; i < bytes.length; i++) s += ('0' + ((bytes[i] + 256) % 256).toString(16)).slice(-2);
  return s;
}

/** ספרות אקראיות מ-UUID (מקור אקראיות מאובטח של Apps Script). */
function newPassword_() {
  var digits = '';
  while (digits.length < PASSWORD_DIGITS) {
    var hex = Utilities.getUuid().replace(/-/g, '');
    for (var i = 0; i + 1 < hex.length && digits.length < PASSWORD_DIGITS; i += 2) {
      var b = parseInt(hex.substr(i, 2), 16);
      if (b < 250) digits += String(b % 10); // דחיית 250..255 כדי שכל ספרה תהיה שווה בהסתברות
    }
  }
  return digits;
}

function newSalt_() {
  return Utilities.getUuid().replace(/-/g, '');
}

function failKey_(username) { return 'fail:' + username; }

function failCount_(cache, username) {
  return parseInt(cache.get(failKey_(username)) || '0', 10);
}

function recordFail_(cache, username) {
  cache.put(failKey_(username), String(failCount_(cache, username) + 1), FAIL_WINDOW_SEC);
}

/** תשובה לא חתומה — רק לבקשה פגומה או לשרת שלא הוגדר. האפליקציה לא מסתמכת עליה. */
function fail_(code) {
  return { ok: false, code: code, protocol: PROTOCOL };
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

// ------------------------------------------------------------------ מפתח החתימה

/**
 * המפתח הפרטי לחתימת התשובות (PKCS#8 PEM). הכלי tools/license-keys.sh ממלא אותו בעותק האישי
 * של הקובץ. אסור לפרסם אותו ואסור להכניס אותו למאגר הקוד.
 */
var PRIVATE_KEY = '';
