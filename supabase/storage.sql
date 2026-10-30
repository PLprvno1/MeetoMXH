-- Create the public bucket used by the Android app.
insert into storage.buckets (id, name, public)
values ('meeto-media', 'meeto-media', true)
on conflict (id) do update set public = true;

-- The app currently authenticates with Firebase, so Supabase Storage receives
-- the Supabase anon role. Limit writes to this bucket; reads use its public URL.
create policy "Allow app uploads to meeto-media"
on storage.objects
for insert
to anon, authenticated
with check (bucket_id = 'meeto-media');
