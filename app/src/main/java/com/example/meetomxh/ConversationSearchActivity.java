package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.example.meetomxh.models.ChatMessage;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.RpcClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** Server-paged, permission checked message search for one conversation. */
public final class ConversationSearchActivity extends AppCompatActivity {
    public static final String EXTRA_CHAT_ID = "chat_id";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String chatId, query;
    private long beforeSeq;
    private boolean loading, more;
    private long generation;
    private Runnable pending;

    private EditText etSearch;
    private ImageView btnClear;
    private View scrollResults;
    private LinearLayout resultsContainer;
    private Button btnNext;

    private View layoutInitialState;
    private TextView tvInitialHint;
    private View layoutLoading;
    private View layoutEmpty;
    private View layoutError;
    private TextView tvErrorDescription;
    private Button btnRetry;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        chatId = getIntent().getStringExtra(EXTRA_CHAT_ID);
        if (chatId == null) {
            finish();
            return;
        }
        build();
    }

    private void build() {
        setContentView(R.layout.activity_conversation_search);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        etSearch = findViewById(R.id.etSearch);
        btnClear = findViewById(R.id.btnClear);
        scrollResults = findViewById(R.id.scrollResults);
        resultsContainer = findViewById(R.id.resultsContainer);
        btnNext = findViewById(R.id.btnNext);

        layoutInitialState = findViewById(R.id.layoutInitialState);
        tvInitialHint = findViewById(R.id.tvInitialHint);
        layoutLoading = findViewById(R.id.layoutLoading);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        layoutError = findViewById(R.id.layoutError);
        tvErrorDescription = findViewById(R.id.tvErrorDescription);
        btnRetry = findViewById(R.id.btnRetry);

        btnNext.setOnClickListener(v -> load(false));
        btnRetry.setOnClickListener(v -> load(true));

        btnClear.setOnClickListener(v -> {
            etSearch.setText("");
            showInitialState("Nhập ít nhất 2 ký tự để tìm tin nhắn trong cuộc trò chuyện này.");
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) {
                if (pending != null) handler.removeCallbacks(pending);
                query = s.toString().trim();
                btnClear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);

                if (query.length() < 2) {
                    resultsContainer.removeAllViews();
                    btnNext.setVisibility(View.GONE);
                    if (query.isEmpty()) {
                        showInitialState("Nhập ít nhất 2 ký tự để tìm tin nhắn trong cuộc trò chuyện này.");
                    } else {
                        showInitialState("Nhập thêm ít nhất 1 ký tự nữa để tìm kiếm.");
                    }
                    return;
                }

                long token = ++generation;
                pending = () -> {
                    if (token == generation) load(true);
                };
                handler.postDelayed(pending, 300);
            }
            public void afterTextChanged(Editable e) {}
        });

        showInitialState("Nhập ít nhất 2 ký tự để tìm tin nhắn trong cuộc trò chuyện này.");
    }

    private void showInitialState(String hint) {
        layoutInitialState.setVisibility(View.VISIBLE);
        tvInitialHint.setText(hint);
        layoutLoading.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        scrollResults.setVisibility(View.GONE);
    }

    private void load(boolean reset) {
        if (loading) return;
        if (query == null || query.length() < 2) {
            resultsContainer.removeAllViews();
            showInitialState("Nhập ít nhất 2 ký tự để tìm tin nhắn.");
            btnNext.setVisibility(View.GONE);
            return;
        }

        if (reset) {
            beforeSeq = 0;
            more = false;
            resultsContainer.removeAllViews();
        }

        loading = true;
        btnNext.setEnabled(false);

        if (reset) {
            layoutLoading.setVisibility(View.VISIBLE);
            layoutInitialState.setVisibility(View.GONE);
            layoutEmpty.setVisibility(View.GONE);
            layoutError.setVisibility(View.GONE);
            scrollResults.setVisibility(View.GONE);
        }

        String q = query;
        long cursor = beforeSeq;
        long token = ++generation;

        Api.run(() -> {
            JSONObject page = RpcClient.object("search_messages",
                    Api.obj("p_conversation_id", chatId,
                            "p_query", q,
                            "p_before_seq", cursor == 0 ? null : cursor,
                            "p_limit", 50));
            return page.optJSONArray("items") == null ? new JSONArray() : page.getJSONArray("items");
        }, new Api.Callback<JSONArray>() {
            public void success(JSONArray rows) {
                if (isFinishing()) return;
                loading = false;
                if (token != generation || !q.equals(query)) {
                    load(true);
                    return;
                }

                btnNext.setEnabled(true);
                more = rows.length() == 50;
                btnNext.setVisibility(more ? View.VISIBLE : View.GONE);

                layoutLoading.setVisibility(View.GONE);

                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    ChatMessage message = Dto.message(row);
                    beforeSeq = message.seq;
                    addResult(message, q);
                }

                if (resultsContainer.getChildCount() == 0) {
                    scrollResults.setVisibility(View.GONE);
                    layoutEmpty.setVisibility(View.VISIBLE);
                    layoutError.setVisibility(View.GONE);
                    layoutInitialState.setVisibility(View.GONE);
                } else {
                    scrollResults.setVisibility(View.VISIBLE);
                    layoutEmpty.setVisibility(View.GONE);
                    layoutError.setVisibility(View.GONE);
                    layoutInitialState.setVisibility(View.GONE);
                }
            }

            public void error(String e) {
                if (isFinishing()) return;
                loading = false;
                if (token != generation) return;
                btnNext.setEnabled(true);

                if (resultsContainer.getChildCount() == 0) {
                    layoutLoading.setVisibility(View.GONE);
                    scrollResults.setVisibility(View.GONE);
                    layoutEmpty.setVisibility(View.GONE);
                    layoutInitialState.setVisibility(View.GONE);
                    layoutError.setVisibility(View.VISIBLE);
                    tvErrorDescription.setText(e);
                } else {
                    Toast.makeText(ConversationSearchActivity.this, e, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void addResult(ChatMessage m, String keyword) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_conversation_search_result, resultsContainer, false);

        TextView tvBody = row.findViewById(R.id.tvMessageBody);
        TextView tvTime = row.findViewById(R.id.tvMessageTime);
        TextView tvSenderName = row.findViewById(R.id.tvSenderName);

        String body = m.getDisplayText();
        if (body == null || body.trim().isEmpty()) {
            body = "Tin nhắn";
        }

        // Highlight matching keyword
        if (keyword != null && !keyword.trim().isEmpty()) {
            SpannableString spannable = new SpannableString(body);
            String lowerBody = body.toLowerCase(Locale.getDefault());
            String lowerKey = keyword.toLowerCase(Locale.getDefault());
            int start = lowerBody.indexOf(lowerKey);
            int highlightColor = ContextCompat.getColor(this, R.color.brand_blue);
            while (start >= 0) {
                int end = Math.min(start + lowerKey.length(), body.length());
                spannable.setSpan(new ForegroundColorSpan(highlightColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                start = lowerBody.indexOf(lowerKey, end);
            }
            tvBody.setText(spannable);
        } else {
            tvBody.setText(body);
        }

        String time = "";
        if (m.getCreatedAt() != null) {
            time = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(m.getCreatedAt().toDate());
        }
        tvTime.setText(time);
        tvSenderName.setText("Tin nhắn");

        row.setOnClickListener(v -> {
            Intent intent = new Intent(this, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
                    .putExtra(ChatActivity.EXTRA_JUMP_MESSAGE_ID, m.getMessageId())
                    .putExtra(ChatActivity.EXTRA_JUMP_MESSAGE_SEQ, m.seq)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });

        resultsContainer.addView(row);
    }

    @Override
    protected void onDestroy() {
        generation++;
        if (pending != null) handler.removeCallbacks(pending);
        super.onDestroy();
    }
}
