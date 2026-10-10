// Unit tests of the real Code.gs against in-memory Apps Script fakes.  Run: node --test server/license/test/
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('vm');
const crypto = require('crypto');
const { loadServer } = require('./fake-gas');

const plain = (o) => JSON.parse(JSON.stringify(o));
const nonce = () => crypto.randomBytes(18).toString('base64url');

/** Verifies a reply the way the app does: same fields, same order, RSA-SHA256 with the public key. */
function verifySig(env, req, res) {
  const payload = [1, req.nonce, req.action, req.deviceId, res.ok ? '1' : '0', res.code, res.username, res.expires, String(res.serverTime)].join('\n');
  return crypto.verify('sha256', Buffer.from(payload, 'utf8'), env.keys.publicKey, Buffer.from(res.sig || '', 'base64'));
}

function setup() {
  let clock = Date.UTC(2026, 9, 9, 12, 0, 0);
  const env = loadServer({ clock: () => clock });
  const { gs } = env;
  const sheet = gs.setupSheet_();
  const add = (user, notes = '') => gs.createCustomer_(sheet, user, notes, new Date(clock));
  const req = (body) => {
    const full = body && typeof body === 'object' && !('nonce' in body) ? { ...body, nonce: nonce() } : body;
    const res = plain(gs.handleRequest_(full, new Date(clock)));
    if (res.sig) assert.ok(verifySig(env, full, res), `signature must verify: ${JSON.stringify(res)}`);
    else assert.ok(['bad_request', 'not_configured'].includes(res.code), `only malformed requests are unsigned: ${JSON.stringify(res)}`);
    return res;
  };
  const activate = (username, password, deviceId = 'dev-A', extra = {}) =>
    req({ action: 'activate', username, password, deviceId, deviceName: 'Samsung A54', appVersion: '1.2.0', ...extra });
  const check = (username, deviceId = 'dev-A') => req({ action: 'check', username, deviceId, appVersion: '1.2.0' });
  const rowOf = (username) => gs.findRow_(sheet, gs.normUser_(username));
  const date = (y, m, d) => vm.runInContext(`new Date(${y}, ${m - 1}, ${d})`, gs);
  return { env, gs, sheet, add, req, activate, check, rowOf, date, advance: (ms) => { clock += ms; }, now: () => clock };
}

test('setup writes the header row', () => {
  const { sheet, gs } = setup();
  assert.deepEqual(plain(sheet.getRange(1, 1, 1, gs.NUM_COLS).getValues()[0]), plain(gs.HEADERS));
});

test('new customer: 8-digit password, only a salted hash is stored', () => {
  const { add, sheet, gs } = setup();
  const c = add('050-123 4567', 'משפחת כהן');
  assert.equal(c.username, '0501234567');
  assert.match(c.password, /^\d{8}$/);
  const row = sheet.getRange(2, 1, 1, gs.NUM_COLS).getValues()[0];
  assert.ok(!row.some((v) => String(v).includes(c.password)), 'password must not be stored in plain text');
  assert.match(String(row[gs.COL.HASH - 1]), /^[0-9a-f]{64}$/);
  assert.equal(row[gs.COL.STATUS - 1], 'פעיל');
  assert.equal(row[gs.COL.NOTES - 1], 'משפחת כהן');
  assert.throws(() => add('0501234567'), /כבר קיים/);
  assert.throws(() => add('  '), /שם משתמש/);
  const other = add('0529999999');
  assert.notEqual(other.password, c.password);
});

test('activation binds the first device; the same device can log in again', () => {
  const t = setup();
  const c = t.add('0501234567');
  const r = t.activate('050-1234567', c.password);
  assert.equal(r.ok, true);
  assert.equal(r.username, '0501234567');
  assert.equal(r.expires, '');
  const v = t.rowOf('0501234567').values;
  assert.equal(v[t.gs.COL.DEVICE_ID - 1], 'dev-A');
  assert.equal(v[t.gs.COL.DEVICE_NAME - 1], 'Samsung A54');
  assert.equal(v[t.gs.COL.VERSION - 1], '1.2.0');
  assert.ok(v[t.gs.COL.ACTIVATED - 1] && v[t.gs.COL.LAST_SEEN - 1]);
  assert.equal(t.activate('0501234567', c.password).ok, true, 'reinstall on the same phone works');
  assert.equal(t.check('0501234567').ok, true);
});

test('a second phone is refused until the seller releases the first one', () => {
  const t = setup();
  const c = t.add('0501234567');
  assert.equal(t.activate('0501234567', c.password, 'dev-A').ok, true);
  const refused = t.activate('0501234567', c.password, 'dev-B');
  assert.equal(refused.ok, false);
  assert.equal(refused.code, 'other_device');
  assert.equal(t.check('0501234567', 'dev-B').code, 'other_device');
  assert.equal(t.check('0501234567', 'dev-A').ok, true, 'first phone keeps working');

  t.gs.releaseDevice_(t.sheet, t.rowOf('0501234567').index);
  assert.equal(t.check('0501234567', 'dev-A').code, 'not_activated', 'released phone is told on its next check');
  assert.equal(t.activate('0501234567', c.password, 'dev-B').ok, true, 'new phone can log in after release');
  assert.equal(t.check('0501234567', 'dev-A').code, 'other_device');
  assert.equal(t.check('0501234567', 'dev-B').ok, true);
});

test('wrong or unknown credentials get the same answer; 5 failures lock login for 15 minutes', () => {
  const t = setup();
  const c = t.add('0501234567');
  assert.equal(t.activate('0501234567', '00000000').code, 'bad_credentials');
  assert.equal(t.activate('nobody', c.password).code, 'bad_credentials');
  assert.equal(t.activate('0501234567', '').code, 'bad_credentials');
  for (let i = 0; i < 4; i++) t.activate('0501234567', '11111111');
  assert.equal(t.activate('0501234567', c.password).code, 'rate_limited', 'locked even with the right password');
  t.advance(14 * 60 * 1000);
  assert.equal(t.activate('0501234567', c.password).code, 'rate_limited');
  t.advance(2 * 60 * 1000);
  assert.equal(t.activate('0501234567', c.password).ok, true);
  // success clears the counter
  for (let i = 0; i < 4; i++) t.activate('0501234567', '11111111');
  assert.equal(t.activate('0501234567', c.password).ok, true);
});

test('password reset invalidates the old password', () => {
  const t = setup();
  const c = t.add('0501234567');
  const fresh = t.gs.resetPassword_(t.sheet, t.rowOf('0501234567').index);
  assert.match(fresh, /^\d{8}$/);
  assert.equal(t.activate('0501234567', c.password).code, 'bad_credentials');
  assert.equal(t.activate('0501234567', fresh).ok, true);
});

test('blocked customers are refused on login and on check', () => {
  const t = setup();
  const c = t.add('0501234567');
  assert.equal(t.activate('0501234567', c.password).ok, true);
  t.sheet.getRange(t.rowOf('0501234567').index, t.gs.COL.STATUS).setValue('חסום');
  assert.equal(t.check('0501234567').code, 'blocked');
  assert.equal(t.activate('0501234567', c.password).code, 'blocked');
  t.sheet.getRange(t.rowOf('0501234567').index, t.gs.COL.STATUS).setValue('פעיל');
  assert.equal(t.check('0501234567').ok, true);
});

test('expiry date: valid through the whole day, refused afterwards', () => {
  const t = setup();
  const c = t.add('0501234567');
  const idx = t.rowOf('0501234567').index;
  // clock is 2026-10-09
  t.sheet.getRange(idx, t.gs.COL.EXPIRES).setValue(t.date(2026, 10, 9));
  const ok = t.activate('0501234567', c.password);
  assert.equal(ok.ok, true);
  assert.equal(ok.expires, '2026-10-09');
  t.sheet.getRange(idx, t.gs.COL.EXPIRES).setValue(t.date(2026, 10, 8));
  assert.equal(t.check('0501234567').code, 'expired');
  t.sheet.getRange(idx, t.gs.COL.EXPIRES).setValue('31/12/2026'); // typed as text
  assert.equal(t.check('0501234567').ok, true);
  assert.equal(t.check('0501234567').expires, '2026-12-31');
});

test('check: unknown user and missing device', () => {
  const t = setup();
  t.add('0501234567');
  assert.equal(t.check('someone-else').code, 'unknown_user');
  assert.equal(t.check('0501234567').code, 'not_activated');
});

test('malformed requests', () => {
  const t = setup();
  t.add('0501234567');
  assert.equal(t.req({ action: 'delete', username: 'x', deviceId: 'd' }).code, 'bad_request');
  assert.equal(t.req({ action: 'check', username: '0501234567' }).code, 'bad_request');
  assert.equal(t.req({ action: 'check', deviceId: 'd' }).code, 'bad_request');
  assert.equal(t.req(null).code, 'bad_request');
  const viaPost = (body) => JSON.parse(t.gs.doPost({ postData: { contents: body } }).getContent());
  assert.equal(viaPost('not json').code, 'bad_request');
  assert.equal(viaPost('').code, 'bad_request');
  assert.equal(viaPost(JSON.stringify({ action: 'check', username: '0501234567', deviceId: 'zz' })).code, 'bad_request', 'nonce is required');
  assert.equal(viaPost(JSON.stringify({ action: 'check', username: '0501234567', deviceId: 'zz', nonce: 'short' })).code, 'bad_request');
  assert.equal(viaPost(JSON.stringify({ action: 'check', username: '0501234567', deviceId: 'zz', nonce: nonce() })).code, 'not_activated');
  assert.equal(JSON.parse(t.gs.doGet().getContent()).ok, true);
});

test('lock is always released, even after a refusal', () => {
  const t = setup();
  t.add('0501234567');
  t.check('nobody');
  assert.equal(t.env.lockState.held, false);
  t.activate('0501234567', 'bad');
  assert.equal(t.env.lockState.held, false);
});

test('customer message contains the login details and the download link', () => {
  const t = setup();
  const m = t.gs.customerMessage_('0501234567', '12345678');
  assert.match(m, /0501234567/);
  assert.match(m, /12345678/);
  assert.match(m, /hamechutan-releases\/releases\/latest/);
});

test('passwords are uniformly random digits', () => {
  const t = setup();
  const counts = Array(10).fill(0);
  for (let i = 0; i < 500; i++) for (const ch of t.gs.newPassword_()) counts[+ch]++;
  // 4000 digits, 400 expected each; a biased generator would fall far outside this range
  for (const n of counts) assert.ok(n > 300 && n < 500, `digit count ${n}`);
});

test('signatures bind the reply to the request: a tampered or replayed reply does not verify', () => {
  const t = setup();
  const c = t.add('0501234567');
  const req = { action: 'activate', username: '0501234567', password: c.password, deviceId: 'dev-A', nonce: nonce() };
  const res = plain(t.gs.handleRequest_(req, new Date(t.now())));
  assert.ok(verifySig(t.env, req, res));
  assert.ok(!verifySig(t.env, { ...req, nonce: nonce() }, res), 'replay for another request');
  assert.ok(!verifySig(t.env, { ...req, deviceId: 'dev-B' }, res), 'other device');
  assert.ok(!verifySig(t.env, req, { ...res, expires: '2099-01-01' }), 'tampered expiry');
  const denied = plain(t.gs.handleRequest_({ ...req, password: 'x', nonce: nonce() }, new Date(t.now())));
  assert.ok(!verifySig(t.env, req, { ...denied, ok: true, code: '' }), 'a denial cannot be turned into an approval');
});

test('server without a private key refuses to answer', () => {
  const env = loadServer({ keys: null });
  env.gs.setupSheet_();
  const r = plain(env.gs.handleRequest_({ action: 'check', username: 'x', deviceId: 'd', nonce: nonce() }, new Date()));
  assert.equal(r.code, 'not_configured');
});

test('usernames with a leading zero survive Google Sheets, also rows typed by hand', () => {
  const t = setup();
  const c = t.add('058-327 1424');
  const v = t.rowOf('0583271424').values;
  assert.equal(v[t.gs.COL.USER - 1], '0583271424', 'stored as text, zero kept');
  assert.equal(typeof v[t.gs.COL.HASH - 1], 'string');
  assert.equal(t.activate('0583271424', c.password).ok, true);
  assert.equal(t.rowOf('0583271424').values[t.gs.COL.DEVICE_ID - 1], 'dev-A');
  assert.equal(t.rowOf('0583271424').values[t.gs.COL.VERSION - 1], '1.2.0', 'version kept as text');

  // A row whose username Sheets already turned into a number (written before this fix)
  t.sheet.appendRow(['548562860', 'פעיל', '', '', '', '', '', '', '', '', '']);
  assert.equal(typeof t.sheet.getRange(t.sheet.getLastRow(), 1).getValue(), 'number');
  const idx = t.rowOf('0548562860').index;
  assert.equal(idx, t.sheet.getLastRow(), 'found with or without the leading zero');
  const fresh = t.gs.resetPassword_(t.sheet, idx);
  assert.equal(t.activate('0548562860', fresh, 'dev-X').ok, true);
  assert.equal(t.activate('548562860', fresh, 'dev-X').ok, true);
  assert.throws(() => t.add('0548562860'), /כבר קיים/, 'no duplicate for the same number');
  // names that are not only digits are not affected
  const a = t.add('abc');
  assert.equal(t.activate('0abc', a.password).code, 'bad_credentials');
});
