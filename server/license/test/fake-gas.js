// In-memory fakes of the Google Apps Script services used by Code.gs, so the real server code
// can be tested with Node (unit tests) and served over HTTP (end-to-end test with the Kotlin client).
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');

/** Returns an object whose unknown methods are chainable no-ops (formatting calls etc.). */
function chainable(target) {
  const proxy = new Proxy(target, {
    get(t, prop) {
      if (prop in t) return t[prop];
      if (typeof prop === 'symbol') return undefined;
      return () => proxy;
    },
  });
  return proxy;
}

/**
 * Google Sheets interprets written strings like typed input: "0583271424" becomes the number 583271424,
 * and a leading apostrophe keeps the rest as text. The fake does the same, so tests catch lost zeros.
 */
function asEntered(v) {
  if (typeof v !== 'string') return v;
  if (v.startsWith("'")) return v.slice(1);
  if (/^-?\d+(\.\d+)?$/.test(v.trim())) return Number(v);
  return v;
}

class FakeSheet {
  constructor(name) { this.name = name; this.rows = []; this.maxRows = 1000; }
  getName() { return this.name; }
  getLastRow() { return this.rows.length; }
  getMaxRows() { return this.maxRows; }
  cell(r, c) { const row = this.rows[r - 1]; return row && row[c - 1] !== undefined ? row[c - 1] : ''; }
  set(r, c, v) {
    while (this.rows.length < r) this.rows.push([]);
    const row = this.rows[r - 1];
    while (row.length < c) row.push('');
    row[c - 1] = asEntered(v);
  }
  appendRow(values) { this.rows.push(values.map(asEntered)); return this; }
  getRange(r, c, nr = 1, nc = 1) {
    const sheet = this;
    return chainable({
      getRow: () => r,
      getValues: () => Array.from({ length: nr }, (_, i) => Array.from({ length: nc }, (_, j) => sheet.cell(r + i, c + j))),
      getValue: () => sheet.cell(r, c),
      setValues(vals) {
        if (vals.length !== nr || vals.some((row) => row.length !== nc)) throw new Error(`setValues: size mismatch ${nr}x${nc}`);
        vals.forEach((row, i) => row.forEach((v, j) => sheet.set(r + i, c + j, v)));
        return this;
      },
      setValue(v) { sheet.set(r, c, v); return this; },
    });
  }
}

function signed(buf) { return Array.from(buf, (b) => (b > 127 ? b - 256 : b)); }

/** A fresh RSA key pair: the private key in PEM (as in Code.gs), the public key as base64 DER (as in the app). */
function newKeyPair() {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  return {
    privatePem: privateKey.export({ type: 'pkcs8', format: 'pem' }),
    publicDer: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
    publicKey,
  };
}

/**
 * Creates a sandbox with Code.gs loaded. `clock()` drives the cache expiry (and can be moved in tests);
 * `keys: null` leaves PRIVATE_KEY empty, like a freshly pasted script.
 */
function loadServer({ clock = () => Date.now(), keys = newKeyPair(), codePath = path.join(__dirname, '..', 'Code.gs'), keepEmbeddedKey = false } = {}) {
  const sheets = new Map();
  const spreadsheet = chainable({
    getSheetByName: (n) => sheets.get(n) || null,
    insertSheet: (n) => { const s = chainable(new FakeSheet(n)); sheets.set(n, s); return s; },
  });
  const cacheStore = new Map();
  const cache = {
    get(k) { const e = cacheStore.get(k); if (!e) return null; if (clock() >= e.until) { cacheStore.delete(k); return null; } return e.v; },
    put(k, v, ttlSec) { cacheStore.set(k, { v: String(v), until: clock() + (ttlSec || 600) * 1000 }); },
    remove(k) { cacheStore.delete(k); },
  };
  const lockState = { held: false, max: 0 };
  const sandbox = {
    console,
    SpreadsheetApp: {
      getActiveSpreadsheet: () => spreadsheet,
      getActive: () => spreadsheet,
      newDataValidation: () => chainable({}),
    },
    CacheService: { getScriptCache: () => cache },
    LockService: {
      getScriptLock: () => ({
        waitLock() { if (lockState.held) throw new Error('lock already held (re-entrant use)'); lockState.held = true; },
        releaseLock() { lockState.held = false; },
      }),
    },
    Utilities: {
      DigestAlgorithm: { SHA_256: 'sha256' },
      Charset: { UTF_8: 'utf8' },
      computeDigest(alg, value) {
        const input = typeof value === 'string' ? Buffer.from(value, 'utf8') : Buffer.from(value.map((b) => b & 0xff));
        return signed(crypto.createHash(alg).update(input).digest());
      },
      getUuid: () => crypto.randomUUID(),
      computeRsaSha256Signature(value, key) {
        return signed(crypto.sign('sha256', Buffer.from(value, 'utf8'), key));
      },
      base64Encode(v) {
        return (typeof v === 'string' ? Buffer.from(v, 'utf8') : Buffer.from(v.map((b) => b & 0xff))).toString('base64');
      },
    },
    ContentService: {
      MimeType: { JSON: 'application/json' },
      createTextOutput(s) { return { setMimeType() { return this; }, getContent: () => s }; },
    },
  };
  vm.createContext(sandbox);
  const code = fs.readFileSync(codePath, 'utf8');
  vm.runInContext(code, sandbox, { filename: 'Code.gs' });
  if (!keepEmbeddedKey) sandbox.PRIVATE_KEY = keys ? keys.privatePem : '';
  const sheet = () => sheets.get(sandbox.LICENSE_SHEET);
  return { gs: sandbox, sheet, cache, lockState, keys };
}

module.exports = { loadServer, newKeyPair, FakeSheet };
