import { useCallback, useEffect, useRef, useState } from 'react';
import { api, formatINR, parseINR } from './api.js';

function useIdempotencyKey() {
  // One key per logical payment attempt: a retry after a timeout reuses it, so the
  // server charges at most once. A fresh key is minted only after a definite outcome.
  const ref = useRef(crypto.randomUUID());
  return [() => ref.current, () => { ref.current = crypto.randomUUID(); }];
}

function NewAccount({ onCreated }) {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const acct = await api.createAccount(name, email);
      setName('');
      setEmail('');
      onCreated(acct);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card" onSubmit={submit} aria-labelledby="new-acct-title">
      <h2 id="new-acct-title">Open a wallet</h2>
      <label htmlFor="na-name">Full name</label>
      <input id="na-name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} autoComplete="name" />
      <label htmlFor="na-email">Email</label>
      <input id="na-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoComplete="email" />
      {error && <p className="error" role="alert">{error}</p>}
      <button disabled={busy}>{busy ? 'Creating…' : 'Create wallet'}</button>
    </form>
  );
}

function SendMoney({ me, accounts, onDone }) {
  const others = accounts.filter((a) => a.id !== me.id);
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const [status, setStatus] = useState(null);
  const [busy, setBusy] = useState(false);
  const [key, rotateKey] = useIdempotencyKey();

  async function submit(e) {
    e.preventDefault();
    const paise = parseINR(amount);
    if (!to) return setStatus({ kind: 'error', text: 'Choose who to pay.' });
    if (paise === null) return setStatus({ kind: 'error', text: 'Enter an amount like 250 or 99.50.' });
    setBusy(true);
    setStatus(null);
    try {
      const t = await api.transfer(me.id, to, paise, note, key());
      rotateKey();
      setAmount('');
      setNote('');
      setStatus({ kind: 'ok', text: `Sent ${formatINR(t.amount)}.` });
      onDone();
    } catch (err) {
      // 4xx is a definite "no"; keep the key only when the outcome is unknown (network / 5xx)
      if (err.status && err.status < 500) rotateKey();
      setStatus({ kind: 'error', text: err.message });
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card" onSubmit={submit} aria-labelledby="send-title">
      <h2 id="send-title">Send money</h2>
      <label htmlFor="sm-to">To</label>
      <select id="sm-to" value={to} onChange={(e) => setTo(e.target.value)}>
        <option value="">Select a wallet…</option>
        {others.map((a) => <option key={a.id} value={a.id}>{a.ownerName} ({a.email})</option>)}
      </select>
      <label htmlFor="sm-amt">Amount (₹)</label>
      <input id="sm-amt" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="250.00" />
      <label htmlFor="sm-note">Note <span className="muted">(optional)</span></label>
      <input id="sm-note" value={note} onChange={(e) => setNote(e.target.value)} maxLength={140} />
      <p className={status?.kind === 'error' ? 'error' : 'ok'} role="status" aria-live="polite">{status?.text}</p>
      <button disabled={busy || others.length === 0}>{busy ? 'Sending…' : 'Send'}</button>
    </form>
  );
}

function Activity({ me, version, onChanged }) {
  const [items, setItems] = useState([]);
  const [cursor, setCursor] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let live = true;
    api.ledger(me.id).then((p) => {
      if (!live) return;
      setItems(p.items);
      setCursor(p.nextCursor);
    }).catch((e) => setError(e.message));
    return () => { live = false; };
  }, [me.id, version]);

  async function more() {
    const p = await api.ledger(me.id, cursor);
    setItems((prev) => [...prev, ...p.items]);
    setCursor(p.nextCursor);
  }

  async function refund(entry) {
    try {
      await api.refund(entry.transferId, `refund-${entry.entryId}`);
      onChanged();
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <section className="card wide" aria-labelledby="act-title">
      <h2 id="act-title">Activity</h2>
      {error && <p className="error" role="alert">{error}</p>}
      {items.length === 0 ? <p className="muted">No activity yet.</p> : (
        <ul className="feed">
          {items.map((e) => (
            <li key={e.entryId}>
              <div>
                <strong>{e.kind === 'DEPOSIT' ? 'Added money' : e.kind === 'REFUND'
                  ? `Refund ${e.amount > 0 ? 'from' : 'to'} ${e.counterpartyName}`
                  : `${e.amount > 0 ? 'From' : 'To'} ${e.counterpartyName}`}</strong>
                {e.note && <span className="muted"> · {e.note}</span>}
                <div className="muted small">{new Date(e.createdAt).toLocaleString()}</div>
              </div>
              <div className="right">
                <span className={e.amount > 0 ? 'credit' : 'debit'}>{e.amount > 0 ? '+' : ''}{formatINR(e.amount)}</span>
                {e.kind === 'P2P' && e.amount > 0 && !e.transferFullyRefunded && (
                  <button className="link" onClick={() => refund(e)} aria-label={`Refund ${formatINR(e.amount)} to ${e.counterpartyName}`}>Refund</button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {cursor && <button className="secondary" onClick={more}>Load more</button>}
    </section>
  );
}

export default function App() {
  const [accounts, setAccounts] = useState([]);
  const [meId, setMeId] = useState(null);
  const [me, setMe] = useState(null);
  const [version, setVersion] = useState(0);
  const [depKey, rotateDepKey] = useIdempotencyKey();

  const refresh = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    api.listAccounts().then((list) => {
      setAccounts(list);
      setMeId((cur) => cur ?? list[0]?.id ?? null);
    });
  }, [version]);

  useEffect(() => {
    if (meId) api.getAccount(meId).then(setMe);
  }, [meId, version]);

  async function addMoney() {
    await api.deposit(me.id, 100000, depKey());
    rotateDepKey();
    refresh();
  }

  return (
    <main>
      <header>
        <h1>Tally</h1>
        {accounts.length > 0 && (
          <label className="switch">
            <span className="muted">Signed in as</span>
            <select value={meId ?? ''} onChange={(e) => setMeId(e.target.value)} aria-label="Active wallet">
              {accounts.map((a) => <option key={a.id} value={a.id}>{a.ownerName}</option>)}
            </select>
          </label>
        )}
      </header>

      <div className="grid">
        {me && (
          <section className="card balance" aria-labelledby="bal-title">
            <h2 id="bal-title">Balance</h2>
            <p className="big" aria-live="polite">{formatINR(me.balance)}</p>
            <button className="secondary" onClick={addMoney}>Add ₹1,000</button>
          </section>
        )}
        {me && <SendMoney me={me} accounts={accounts} onDone={refresh} />}
        <NewAccount onCreated={(a) => { setMeId(a.id); refresh(); }} />
        {me && <Activity me={me} version={version} onChanged={refresh} />}
      </div>
    </main>
  );
}
