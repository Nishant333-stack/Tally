-- Tally schema: double-entry wallet ledger.
-- Money is stored as BIGINT minor units (paise / cents) - never floating point.

CREATE TABLE accounts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        TEXT        NOT NULL DEFAULT 'USER' CHECK (kind IN ('USER', 'SYSTEM')),
    owner_name  TEXT        NOT NULL,
    email       TEXT        UNIQUE,
    currency    CHAR(3)     NOT NULL DEFAULT 'INR',
    balance     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- user wallets can never go negative; only the SYSTEM funding account can
    CONSTRAINT non_negative_user_balance CHECK (kind = 'SYSTEM' OR balance >= 0)
);

CREATE TABLE transfers (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key  TEXT        NOT NULL UNIQUE,
    request_hash     TEXT        NOT NULL,
    type             TEXT        NOT NULL CHECK (type IN ('DEPOSIT', 'P2P')),
    from_account     UUID        NOT NULL REFERENCES accounts(id),
    to_account       UUID        NOT NULL REFERENCES accounts(id),
    amount           BIGINT      NOT NULL CHECK (amount > 0),
    currency         CHAR(3)     NOT NULL,
    note             TEXT,
    refunded_amount  BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT distinct_parties CHECK (from_account <> to_account),
    CONSTRAINT refund_within_amount CHECK (refunded_amount BETWEEN 0 AND amount)
);

CREATE TABLE refunds (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key  TEXT        NOT NULL UNIQUE,
    request_hash     TEXT        NOT NULL,
    transfer_id      UUID        NOT NULL REFERENCES transfers(id),
    amount           BIGINT      NOT NULL CHECK (amount > 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every movement writes exactly two rows that sum to zero.
CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    account_id     UUID        NOT NULL REFERENCES accounts(id),
    transfer_id    UUID        NOT NULL REFERENCES transfers(id),
    refund_id      UUID        REFERENCES refunds(id),
    amount         BIGINT      NOT NULL CHECK (amount <> 0),
    balance_after  BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Activity feed reads newest-first per account with keyset pagination.
CREATE INDEX ledger_by_account ON ledger_entries (account_id, id DESC);
CREATE INDEX transfers_by_from ON transfers (from_account, created_at DESC);
CREATE INDEX transfers_by_to   ON transfers (to_account, created_at DESC);
CREATE INDEX refunds_by_transfer ON refunds (transfer_id);

INSERT INTO accounts (id, kind, owner_name, currency)
VALUES ('00000000-0000-0000-0000-000000000001', 'SYSTEM', 'Funding', 'INR');
