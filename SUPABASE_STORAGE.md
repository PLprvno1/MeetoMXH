# Supabase Storage setup

The Android app uploads media to the public `meeto-media` bucket. Set these values in the ignored root `local.properties` file:

```properties
supabase.url=https://YOUR_PROJECT_REF.supabase.co
supabase.anonKey=YOUR_SUPABASE_ANON_KEY
```

Run `supabase/storage.sql` in the Supabase SQL Editor before using uploads. The bucket is public so stored file URLs can be saved in Firestore and displayed without signed URL refreshes. The SQL policy allows anonymous app uploads because sign-in currently uses Firebase; use upload restrictions or a server endpoint before exposing this policy to untrusted traffic.

Images and videos from profiles, posts, and chat are uploaded to this bucket. Post documents continue storing the media URL in `postImage` and `imageUrls`; `mediaType` is `image` or `video`.
