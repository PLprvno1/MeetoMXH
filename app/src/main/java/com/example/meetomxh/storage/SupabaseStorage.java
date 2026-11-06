package com.example.meetomxh.storage;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.example.meetomxh.MeetoApplication;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.ErrorMapper;
import com.example.meetomxh.supabase.RequestIds;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SupabaseConfig;
import com.example.meetomxh.supabase.SupabaseHttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.tus.java.client.ProtocolException;
import io.tus.java.client.TusClient;
import io.tus.java.client.TusExecutor;
import io.tus.java.client.TusUpload;
import io.tus.java.client.TusUploader;
import io.tus.java.client.TusURLStore;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/** Direct authenticated Storage upload followed by a server-side object metadata check. */
public final class SupabaseStorage {
    private static final long MIB = 1024L * 1024L;
    private static final long PROFILE_LIMIT = 10L * MIB;
    private static final long MEDIA_LIMIT = 50L * MIB;
    private static final long TUS_THRESHOLD = 6L * MIB;
    private static final int TUS_CHUNK = 6 * 1024 * 1024;

    private static final class ValidationError extends IOException {
        ValidationError(String message) { super(message); }
    }

    public interface UploadListener {
        void onSuccess(String assetReference);
        void onFailure(String message);
        default void onProgress(int percent) { }
    }

    public static final class Transfer {
        private volatile boolean cancelled;
        private volatile Call call;
        private volatile Thread worker;
        private volatile HttpURLConnection tusConnection;
        private volatile String tusFingerprint;
        private volatile int lastProgress;

        public void cancel() {
            cancelled = true;
            Call activeCall = call;
            if (activeCall != null) activeCall.cancel();
            HttpURLConnection connection = tusConnection;
            if (connection != null) connection.disconnect();
            Thread activeWorker = worker;
            if (activeWorker != null) activeWorker.interrupt();
        }

        public boolean isCanceled() { return cancelled; }

        private synchronized void progress(UploadListener listener, long sent, long total) {
            if (cancelled || total <= 0) return;
            int value = (int) Math.min(99, Math.max(0, sent * 100L / total));
            if (value <= lastProgress) return;
            lastProgress = value;
            Api.MAIN.post(() -> { if (!cancelled) listener.onProgress(value); });
        }
    }

    private SupabaseStorage() { }

    public static Transfer upload(ContentResolver resolver, Uri uri, String folder, String owner,
                                  UploadListener listener) {
        return upload(resolver, uri, folder, owner, null, listener);
    }

    /** Uploads a locally edited MP4 through the same direct intent and verifier lifecycle. */
    public static Transfer uploadVideo(ContentResolver resolver, File file, String folder, String owner,
                                       UploadListener listener) {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("Edited video is unavailable");
        return upload(resolver, Uri.fromFile(file), folder, owner, "video/mp4", listener);
    }

    public static Transfer uploadImage(ContentResolver resolver, File file, String folder, String owner,
                                       UploadListener listener) {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("Image file is unavailable");
        return upload(resolver, Uri.fromFile(file), folder, owner, "image/jpeg", listener);
    }

    public static Transfer uploadVoice(ContentResolver resolver, File file, String folder, String owner,
                                       UploadListener listener) {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("Voice recording is unavailable");
        return upload(resolver, Uri.fromFile(file), folder, owner, "audio/mp4", listener);
    }

    private static Transfer upload(ContentResolver resolver, Uri uri, String folder, String owner,
                                   String mimeOverride, UploadListener listener) {
        Transfer transfer = new Transfer();
        long epoch = SessionManager.getInstance().generation();
        transfer.worker = new Thread(() -> runUpload(resolver, uri, folder, owner, mimeOverride, listener, transfer, epoch),
                "meeto-storage-upload");
        transfer.worker.start();
        return transfer;
    }

    private static void runUpload(ContentResolver resolver, Uri uri, String folder, String owner,
                                  String mimeOverride, UploadListener listener, Transfer transfer, long epoch) {
        File staged = null;
        SecureTusUrlStore tusStore = new SecureTusUrlStore();
        String tusFingerprint = null;
        try {
            SupabaseConfig.check();
            ensureCurrent(transfer, epoch);
            String purpose = purpose(folder);
            String mime = normalizeMime(mimeOverride == null ? resolver.getType(uri) : mimeOverride);
            validatePurposeMime(purpose, mime);
            long maxBytes = "audio/mp4".equals(mime) ? 2L * MIB
                    : (purpose.equals("avatar") || purpose.equals("cover") || purpose.equals("group_cover")) ? PROFILE_LIMIT : MEDIA_LIMIT;
            staged = stageAndValidate(resolver, uri, mime, maxBytes, transfer, epoch);
            String operation = owner + "|" + (purpose.equals("group_cover") ? folder : purpose) + "|" + uri + "|" + mime;
            String requestId = RequestIds.get("upload_direct_v1", operation);
            JSONObject intent = prepareIntent(folder, purpose, mime, requestId);
            ensureCurrent(transfer, epoch);
            String assetId = intent.getString("id");
            String bucket = intent.getString("bucket_id");
            String path = intent.getString("object_path");
            String token = SessionManager.getInstance().access(false);

            if (staged.length() > TUS_THRESHOLD) {
                tusFingerprint = "meeto-direct-v1:" + assetId;
                transfer.tusFingerprint = tusFingerprint;
                uploadTus(staged, bucket, path, mime, token, assetId, tusFingerprint,
                        listener, transfer, tusStore);
            } else {
                uploadStandard(staged, bucket, path, mime, token, listener, transfer, epoch);
            }
            ensureCurrent(transfer, epoch);

            JSONObject ready = finalizeWithRetry(assetId, transfer, epoch,
                    purpose.equals("group_cover") ? folder.substring("group-cover/".length()) : null,
                    "audio/mp4".equals(mime));
            if (!"ready".equals(ready.optString("state"))) throw new IOException("Storage attachment not ready");
            RequestIds.complete("upload_direct_v1", operation);
            if (tusFingerprint != null) tusStore.remove(tusFingerprint);
            String reference = Dto.asset(ready.getString("id"));
            Api.MAIN.post(() -> {
                if (!transfer.cancelled && epoch == SessionManager.getInstance().generation()) {
                    listener.onProgress(100);
                    listener.onSuccess(reference);
                }
            });
        } catch (Exception error) {
            if (!transfer.cancelled && epoch == SessionManager.getInstance().generation()) {
                String message = error instanceof ValidationError ? error.getMessage() : ErrorMapper.message(error);
                Api.MAIN.post(() -> {
                    if (!transfer.cancelled && epoch == SessionManager.getInstance().generation()) listener.onFailure(message);
                });
            }
        } finally {
            transfer.call = null;
            transfer.tusConnection = null;
            if (staged != null && staged.exists()) staged.delete();
            transfer.worker = null;
        }
    }

    private static String purpose(String folder) throws IOException {
        if (folder == null) throw new ValidationError("Invalid upload purpose");
        if (folder.startsWith("chats/")) return "message";
        if (folder.startsWith("group-cover/")) return "group_cover";
        if (folder.equals("avatar") || folder.contains("avatar")) return "avatar";
        if (folder.equals("cover") || folder.contains("cover")) return "cover";
        if (folder.equals("posts") || folder.equals("post")) return "post";
        if (folder.equals("stories") || folder.equals("story")) return "story";
        throw new ValidationError("Unsupported upload purpose");
    }

    private static String normalizeMime(String mime) throws IOException {
        if (mime == null) throw new ValidationError("Could not identify the selected file type");
        String value = mime.toLowerCase(Locale.ROOT).trim();
        if (!value.equals("image/png") && !value.equals("image/jpeg") && !value.equals("image/webp")
                && !value.equals("video/mp4") && !value.equals("audio/mp4"))
            throw new ValidationError("Choose a PNG, JPEG, WebP, MP4, or recorded M4A file");
        return value;
    }

    private static void validatePurposeMime(String purpose, String mime) throws IOException {
        if ((purpose.equals("avatar") || purpose.equals("cover") || purpose.equals("group_cover")) && !mime.startsWith("image/"))
            throw new ValidationError("Avatar and cover require an image");
        if (purpose.equals("message") && !mime.startsWith("image/") && !"video/mp4".equals(mime)
                && !"audio/mp4".equals(mime)) throw new ValidationError("Unsupported chat media type");
    }

    private static File stageAndValidate(ContentResolver resolver, Uri uri, String mime, long maxBytes,
                                         Transfer transfer, long epoch) throws Exception {
        File file = File.createTempFile("meeto-upload-", ".tmp", MeetoApplication.get().getCacheDir());
        boolean keep = false;
        try (InputStream input = "file".equals(uri.getScheme())
                     ? new FileInputStream(new File(uri.getPath())) : resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(file)) {
            if (input == null) throw new ValidationError("Cannot read the selected file");
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                ensureCurrent(transfer, epoch);
                total += read;
                if (total > maxBytes) throw new ValidationError("File is larger than the allowed " + (maxBytes / MIB) + " MiB limit");
                output.write(buffer, 0, read);
            }
            output.flush();
            if (total == 0) throw new ValidationError("The selected file is empty");
            validateSignature(file, mime);
            if (mime.startsWith("image/")) validateImageDecode(file);
            else if ("video/mp4".equals(mime)) validateVideoDecode(file);
            else validateAudioRecording(file);
            keep = true;
            return file;
        } finally {
            if (!keep && file.exists()) file.delete();
        }
    }

    private static void validateSignature(File file, String mime) throws IOException {
        byte[] head = new byte[12];
        int count;
        try (InputStream input = new FileInputStream(file)) { count = input.read(head); }
        boolean valid;
        if ("image/png".equals(mime)) {
            valid = count >= 8 && (head[0] & 255) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                    && head[4] == 13 && head[5] == 10 && head[6] == 26 && head[7] == 10;
        } else if ("image/jpeg".equals(mime)) {
            valid = count >= 3 && (head[0] & 255) == 0xff && (head[1] & 255) == 0xd8 && (head[2] & 255) == 0xff;
        } else if ("image/webp".equals(mime)) {
            valid = count >= 12 && ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP");
        } else if ("video/mp4".equals(mime) || "audio/mp4".equals(mime)) {
            valid = count >= 12 && ascii(head, 4, "ftyp");
        } else {
            valid = false;
        }
        if (!valid) throw new ValidationError("File contents do not match the selected file type");
    }

    private static boolean ascii(byte[] data, int offset, String value) {
        for (int i = 0; i < value.length(); i++) if (data[offset + i] != (byte) value.charAt(i)) return false;
        return true;
    }

    private static void validateImageDecode(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new ValidationError("The selected image cannot be decoded");
        long pixels = (long) bounds.outWidth * bounds.outHeight;
        if (pixels > 100_000_000L) throw new ValidationError("Image dimensions are too large for this device");
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 2048) sample *= 2;
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        decode.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), decode);
        if (bitmap == null) throw new ValidationError("The selected image cannot be decoded");
        bitmap.recycle();
    }

    private static void validateVideoDecode(File file) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (duration == null || Long.parseLong(duration) <= 0 || frame == null)
                throw new ValidationError("This MP4 could not be read by the device");
            frame.recycle();
        } catch (RuntimeException error) {
            throw new ValidationError("This MP4 could not be read by the device");
        } finally {
            retriever.release();
        }
    }

    private static void validateAudioRecording(File file) throws Exception {
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            metadata.setDataSource(file.getAbsolutePath());
            String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration == null || Long.parseLong(duration) < 1 || Long.parseLong(duration) > 120_000)
                throw new ValidationError("Voice recording must be between 1 and 120 seconds");
            extractor.setDataSource(file.getAbsolutePath());
            boolean aac = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat format = extractor.getTrackFormat(i);
                String track = format.getString(android.media.MediaFormat.KEY_MIME);
                if (track != null && track.equals("audio/mp4a-latm")) aac = true;
            }
            if (!aac) throw new ValidationError("The voice recording does not contain an AAC audio track");
        } catch (RuntimeException error) {
            throw new ValidationError("The recorded M4A could not be read");
        } finally {
            extractor.release();
            metadata.release();
        }
    }

    private static JSONObject prepareIntent(String folder, String purpose, String mime, String requestId) throws Exception {
        JSONObject args;
        String rpc;
        if (purpose.equals("group_cover")) {
            args = Api.obj("p_group_id",folder.substring("group-cover/".length()),"p_mime",mime,"p_client_request_id",requestId);
            rpc = "prepare_group_cover_upload";
        } else if (purpose.equals("story")) {
            args = Api.obj("p_mime", mime, "p_client_request_id", requestId);
            rpc = "prepare_direct_story_upload";
        } else if (purpose.equals("message")) {
            args = Api.obj("p_conversation_id", folder.substring("chats/".length()),
                    "p_mime", mime, "p_client_request_id", requestId);
            rpc = "prepare_direct_message_upload";
        } else {
            args = Api.obj("p_purpose", purpose, "p_mime", mime, "p_client_request_id", requestId);
            rpc = "prepare_direct_upload";
        }
        return RpcClient.object(rpc, args);
    }

    private static void uploadStandard(File file, String bucket, String path, String mime, String token,
                                       UploadListener listener, Transfer transfer, long epoch) throws Exception {
        RequestBody bytes = new RequestBody() {
            @Override public MediaType contentType() { return MediaType.parse(mime); }
            @Override public long contentLength() { return file.length(); }
            @Override public void writeTo(BufferedSink sink) throws IOException {
                try (InputStream input = new FileInputStream(file)) {
                    byte[] buffer = new byte[32 * 1024];
                    long sent = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (transfer.cancelled || epoch != SessionManager.getInstance().generation()) throw new IOException("Upload cancelled");
                        sink.write(buffer, 0, read);
                        sent += read;
                        transfer.progress(listener, sent, file.length());
                    }
                }
            }
        };
        String encodedBucket = Uri.encode(bucket);
        String encodedPath = encodeObjectPath(path);
        Request request = new Request.Builder()
                .url(SupabaseConfig.URL + "/storage/v1/object/" + encodedBucket + "/" + encodedPath)
                .header("apikey", SupabaseConfig.KEY)
                .header("Authorization", "Bearer " + token)
                .header("x-upsert", "false")
                .header("cache-control", "0")
                .post(bytes).build();
        if (transfer.cancelled) throw new IOException("Upload cancelled");
        transfer.call = SupabaseHttpClient.CLIENT.newCall(request);
        try (Response response = transfer.call.execute()) {
            if (!response.isSuccessful() && response.code() != 409)
                throw new ErrorMapper.HttpError(response.code(), storageErrorCode(response));
        } finally { transfer.call = null; }
    }

    private static void uploadTus(File file, String bucket, String path, String mime, String token,
                                  String assetId, String fingerprint, UploadListener listener,
                                  Transfer transfer, SecureTusUrlStore store) throws Exception {
        TusClient client = new TusClient() {
            @Override public void prepareConnection(HttpURLConnection connection) {
                super.prepareConnection(connection);
                connection.setReadTimeout(30_000);
                transfer.tusConnection = connection;
            }
        };
        client.setUploadCreationURL(new URL(tusEndpoint()));
        client.setConnectTimeout(20_000);
        Map<String, String> headers = new HashMap<>();
        headers.put("apikey", SupabaseConfig.KEY);
        headers.put("Authorization", "Bearer " + token);
        headers.put("x-upsert", "false");
        client.setHeaders(headers);
        client.enableResuming(store);

        TusUpload upload = new TusUpload(file);
        upload.setFingerprint(fingerprint);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("bucketName", bucket);
        metadata.put("objectName", path);
        metadata.put("contentType", mime);
        metadata.put("cacheControl", "0");
        upload.setMetadata(metadata);

        TusExecutor executor = new TusExecutor() {
            @Override protected void makeAttempt() throws ProtocolException, IOException {
                if (transfer.cancelled) throw new IOException("Upload cancelled");
                TusUploader uploader = client.resumeOrCreateUpload(upload);
                uploader.setChunkSize(TUS_CHUNK);
                uploader.setRequestPayloadSize(TUS_CHUNK);
                while (true) {
                    if (transfer.cancelled) throw new IOException("Upload cancelled");
                    long offset = uploader.getOffset();
                    transfer.progress(listener, offset, upload.getSize());
                    if (uploader.uploadChunk() < 0) break;
                }
                transfer.progress(listener, upload.getSize(), upload.getSize());
                uploader.finish();
            }
        };
        executor.setDelays(new int[]{500, 1500, 3000});
        if (!executor.makeAttempts()) throw new IOException("Upload cancelled");
        transfer.tusConnection = null;
    }

    private static String tusEndpoint() throws Exception {
        android.net.Uri uri = android.net.Uri.parse(SupabaseConfig.URL);
        String host = uri.getHost();
        if (host == null) throw new IOException("Invalid Supabase Storage host");
        String storageHost = host.endsWith(".supabase.co")
                ? host.substring(0, host.length() - ".supabase.co".length()) + ".storage.supabase.co"
                : host;
        return "https://" + storageHost + "/storage/v1/upload/resumable";
    }

    private static String encodeObjectPath(String path) {
        String[] parts = path.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (result.length() > 0) result.append('/');
            result.append(Uri.encode(part));
        }
        return result.toString();
    }

    private static String storageErrorCode(Response response) {
        if (response.body() == null) return "storage_error";
        try {
            JSONObject body = new JSONObject(response.peekBody(2048).string());
            return body.optString("error", body.optString("error_code", body.optString("statusCode", "storage_error")));
        } catch (Exception ignored) { return "storage_error"; }
    }

    private static JSONObject finalizeWithRetry(String assetId, Transfer transfer, long epoch, String groupId,
                                                boolean awaitAudioVerification) throws Exception {
        Exception last = null;
        int attempts = awaitAudioVerification ? 32 : 3;
        for (int attempt = 0; attempt < attempts; attempt++) {
            ensureCurrent(transfer, epoch);
            try { return groupId == null ? RpcClient.object("finalize_upload", Api.obj("p_asset_id", assetId))
                    : RpcClient.object("finalize_group_cover_upload",Api.obj("p_asset_id",assetId,"p_group_id",groupId)); }
            catch (ErrorMapper.HttpError e) {
                last = e;
                boolean waitingForVerifier = awaitAudioVerification && e.status == 409
                        && "verification_pending".equals(e.code);
                if (!waitingForVerifier && (e.status < 500 || attempt == attempts - 1)) throw e;
            } catch (IOException e) {
                last = e;
                if (attempt == attempts - 1) throw e;
            }
            Thread.sleep(awaitAudioVerification ? 2000L : 300L * (attempt + 1));
        }
        if (last != null) {
            if (last instanceof ErrorMapper.HttpError) {
                ErrorMapper.HttpError http = (ErrorMapper.HttpError) last;
                String code = http.code != null && http.code.matches("[A-Za-z0-9_.-]{1,80}")
                        ? http.code : "unknown";
                android.util.Log.w("SupabaseUpload", "Finalize failed: HTTP " + http.status + " code=" + code);
            } else {
                android.util.Log.w("SupabaseUpload", "Finalize failed: " + last.getClass().getSimpleName());
            }
            throw last;
        }
        throw new IOException("Storage confirmation failed");
    }

    private static void ensureCurrent(Transfer transfer, long epoch) throws IOException {
        if (transfer.cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Upload cancelled");
        if (epoch != SessionManager.getInstance().generation()) throw new IOException("Login changed during upload");
    }

    private static final class SecureTusUrlStore implements TusURLStore {
        private String key(String fingerprint) { return "tus_url_" + digest(fingerprint); }
        @Override public void set(String fingerprint, URL url) {
            try { SessionManager.getInstance().pending(key(fingerprint), url.toString()); }
            catch (Exception e) { throw new IllegalStateException("Could not securely save upload resume state"); }
        }
        @Override public URL get(String fingerprint) {
            String stored = SessionManager.getInstance().pending(key(fingerprint));
            if (stored.isEmpty()) return null;
            try { return new URL(stored); } catch (Exception ignored) { remove(fingerprint); return null; }
        }
        @Override public void remove(String fingerprint) {
            try { SessionManager.getInstance().pending(key(fingerprint), ""); }
            catch (Exception ignored) { }
        }
        private static String digest(String text) {
            try {
                byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
                StringBuilder value = new StringBuilder();
                for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b));
                return value.toString();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    public static JSONObject info(String reference) throws Exception {
        com.example.meetomxh.supabase.SocialContract.require();
        String id = Dto.assetId(reference);
        java.util.UUID.fromString(id);
        JSONArray rows = (JSONArray) SupabaseHttpClient.authorized("GET",
                "/rest/v1/media_assets?id=eq." + id + "&select=id,bucket_id,object_path,mime_type", null);
        if (rows.length() != 1) throw new ErrorMapper.HttpError(404, "media_unavailable");
        return rows.getJSONObject(0);
    }

    public static java.util.Map<String, String> mimeTypes(java.util.List<String> references) throws Exception {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (references != null) for (String reference : references) {
            if (reference == null || !reference.startsWith("asset://")) continue;
            ids.add(java.util.UUID.fromString(Dto.assetId(reference)).toString());
        }
        java.util.Map<String, String> result = new java.util.HashMap<>();
        if (ids.isEmpty()) return result;
        StringBuilder filter = new StringBuilder();
        for (String id : ids) { if (filter.length() > 0) filter.append(','); filter.append(id); }
        JSONArray rows = (JSONArray) SupabaseHttpClient.authorized("GET",
                "/rest/v1/media_assets?id=in.(" + filter + ")&select=id,mime_type", null);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            result.put("asset://" + row.getString("id"), row.optString("mime_type", "application/octet-stream"));
        }
        return result;
    }

    public static android.graphics.Bitmap videoThumbnail(String reference) throws Exception {
        JSONObject asset = info(reference);
        if (!asset.optString("mime_type").startsWith("video/")) throw new IOException("Media is not a video");
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("apikey", SupabaseConfig.KEY);
        headers.put("Authorization", "Bearer " + SessionManager.getInstance().access(false));
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(url(asset), headers);
            android.graphics.Bitmap frame = retriever.getFrameAtTime(1_000_000L,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = retriever.getFrameAtTime(-1,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) throw new IOException("No decodable video frame");
            int max = 960;
            int largest = Math.max(frame.getWidth(), frame.getHeight());
            if (largest <= max) return frame;
            float ratio = max / (float) largest;
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(frame,
                    Math.max(1, Math.round(frame.getWidth() * ratio)),
                    Math.max(1, Math.round(frame.getHeight() * ratio)), true);
            if (scaled != frame) frame.recycle();
            return scaled;
        } finally { retriever.release(); }
    }

    public static android.graphics.Bitmap videoThumbnail(ContentResolver resolver, Uri uri) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            try (android.content.res.AssetFileDescriptor afd = resolver.openAssetFileDescriptor(uri, "r")) {
                if (afd == null) throw new IOException("Cannot open selected video");
                retriever.setDataSource(afd.getFileDescriptor());
            }
            android.graphics.Bitmap frame = retriever.getFrameAtTime(1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = retriever.getFrameAtTime(-1,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) throw new IOException("No decodable video frame");
            int max = 960;
            int largest = Math.max(frame.getWidth(), frame.getHeight());
            if (largest <= max) return frame;
            float ratio = max / (float) largest;
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(frame,
                    Math.max(1, Math.round(frame.getWidth() * ratio)),
                    Math.max(1, Math.round(frame.getHeight() * ratio)), true);
            if (scaled != frame) frame.recycle();
            return scaled;
        } finally { retriever.release(); }
    }

    public static String url(JSONObject asset) throws Exception {
        return SupabaseConfig.URL + "/storage/v1/object/authenticated/" + asset.getString("bucket_id")
                + "/" + encodeObjectPath(asset.getString("object_path"));
    }
}
