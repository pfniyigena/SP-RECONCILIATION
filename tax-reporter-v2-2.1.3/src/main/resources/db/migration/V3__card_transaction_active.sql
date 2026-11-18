-- Inactive card transactions are excluded from lists, exports, matching, allocation and reconciliation.
ALTER TABLE public.card_transaction ADD COLUMN IF NOT EXISTS active boolean NOT NULL DEFAULT true;
