-- 2.1.0: counter used by the scheduled stamp lookup (stamp.lookup.max-attempts). Null = never tried.
ALTER TABLE public.customer_deposit ADD COLUMN IF NOT EXISTS stamp_lookup_attempts integer;
