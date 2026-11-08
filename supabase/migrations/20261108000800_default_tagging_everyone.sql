-- New account settings default to allowing tags from everyone.
-- Existing recipient choices and tag-review requirements are preserved.
BEGIN;
ALTER TABLE public.user_settings ALTER COLUMN tagging_policy SET DEFAULT 'everyone';
COMMIT;
