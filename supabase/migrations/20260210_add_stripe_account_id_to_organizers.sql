-- Add Stripe Connect account ID to organizers table
ALTER TABLE public.organizers
ADD COLUMN IF NOT EXISTS stripe_account_id text NULL;
