// Thin fetch wrapper. Errors from the API arrive as { error: { code, message } }.
export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function request(method, path, body, idempotencyKey) {
  const headers = { 'Content-Type': 'application/json' };
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  const res = await fetch(`/api${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = data.error || {};
    throw new ApiError(res.status, err.code || 'network_error', err.message || `HTTP ${res.status}`);
  }
  return data;
}

export const api = {
  listAccounts: () => request('GET', '/accounts').then((d) => d.items),
  getAccount: (id) => request('GET', `/accounts/${id}`),
  createAccount: (ownerName, email) => request('POST', '/accounts', { ownerName, email }),
  deposit: (id, amount, key) => request('POST', `/accounts/${id}/deposits`, { amount }, key),
  transfer: (fromAccountId, toAccountId, amount, note, key) =>
    request('POST', '/transfers', { fromAccountId, toAccountId, amount, note: note || undefined }, key),
  refund: (transferId, key) => request('POST', `/transfers/${transferId}/refunds`, undefined, key),
  ledger: (id, before) =>
    request('GET', `/accounts/${id}/ledger?limit=10${before ? `&before=${before}` : ''}`),
};

/** "1,234.50" from paise. Money stays an integer everywhere except this display edge. */
export function formatINR(paise) {
  const sign = paise < 0 ? '−' : '';
  const abs = Math.abs(paise);
  const rupees = Math.floor(abs / 100).toLocaleString('en-IN');
  return `${sign}₹${rupees}.${String(abs % 100).padStart(2, '0')}`;
}

/** Parse "1,234.5" into paise without floating-point error; null if invalid. */
export function parseINR(text) {
  const m = /^\s*(\d{1,9})(?:\.(\d{1,2}))?\s*$/.exec(text.replace(/,/g, ''));
  if (!m) return null;
  const paise = Number(m[1]) * 100 + Number((m[2] || '').padEnd(2, '0'));
  return paise > 0 ? paise : null;
}
