package com.example.meetomxh.storage;

import android.content.ContentResolver;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.example.meetomxh.BuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/** Uploads image and video files to a public Supabase Storage bucket. */
public final class SupabaseStorage {
    public static final String BUCKET = "meeto-media";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private SupabaseStorage() { }

    public interface UploadListener {
        void onSuccess(String publicUrl);
        void onFailure(String message);
    }

    public static Call upload(ContentResolver resolver, Uri uri, String folder, String ownerId, UploadListener listener) {
        if (TextUtils.isEmpty(BuildConfig.SUPABASE_URL) || TextUtils.isEmpty(BuildConfig.SUPABASE_ANON_KEY)) {
            listener.onFailure("Thiếu supabase.url hoặc supabase.anonKey trong local.properties.");
            return null;
        }
        String mime = resolver.getType(uri);
        if (mime == null || !(mime.toLowerCase(Locale.ROOT).startsWith("image/")
                || mime.toLowerCase(Locale.ROOT).startsWith("video/"))) {
            listener.onFailure("Chỉ hỗ trợ tệp ảnh hoặc video.");
            return null;
        }

        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        String path = safeSegment(folder) + "/" + safeSegment(ownerId) + "/" + UUID.randomUUID()
                + (TextUtils.isEmpty(extension) ? "" : "." + extension);
        String baseUrl = BuildConfig.SUPABASE_URL.replaceAll("/+$", "");
        String encodedPath = encodePath(path);
        RequestBody body = new UriRequestBody(resolver, uri, mime);
        Request request = new Request.Builder()
                .url(baseUrl + "/storage/v1/object/" + encodeSegment(BUCKET) + "/" + encodedPath)
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer " + BuildConfig.SUPABASE_ANON_KEY)
                .header("x-upsert", "false")
                .post(body)
                .build();

        Call call = CLIENT.newCall(request);
        call.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                listener.onFailure(error.getLocalizedMessage() == null ? "Tải tệp lên Supabase thất bại." : error.getLocalizedMessage());
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (Response closeable = response) {
                    if (closeable.isSuccessful()) {
                        listener.onSuccess(baseUrl + "/storage/v1/object/public/" + encodeSegment(BUCKET) + "/" + encodedPath);
                    } else {
                        listener.onFailure("Supabase Storage trả về mã " + closeable.code() + ". Kiểm tra bucket và policy Storage.");
                    }
                }
            }
        });
        return call;
    }

    private static String safeSegment(String value) {
        if (TextUtils.isEmpty(value)) return "unknown";
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String encodePath(String path) {
        String[] segments = path.split("/");
        StringBuilder encoded = new StringBuilder();
        for (String segment : segments) {
            if (encoded.length() > 0) encoded.append('/');
            encoded.append(encodeSegment(segment));
        }
        return encoded.toString();
    }

    private static String encodeSegment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
    private static final class UriRequestBody extends RequestBody {
        private final ContentResolver resolver;
        private final Uri uri;
        private final MediaType mediaType;

        UriRequestBody(ContentResolver resolver, Uri uri, String mime) {
            this.resolver = resolver;
            this.uri = uri;
            this.mediaType = MediaType.parse(mime);
        }

        @Override public MediaType contentType() { return mediaType; }
        @Override public long contentLength() { return -1; }

        @Override public void writeTo(BufferedSink sink) throws IOException {
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new IOException("Không thể mở tệp đã chọn.");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) sink.write(buffer, 0, count);
            }
        }
    }
}
