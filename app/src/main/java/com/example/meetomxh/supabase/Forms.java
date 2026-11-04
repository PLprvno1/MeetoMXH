package com.example.meetomxh.supabase;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.example.meetomxh.R;

public final class Forms {

    public static LinearLayout start(Activity activity, String title) {
        activity.setContentView(R.layout.activity_form);
        TextView titleView = activity.findViewById(R.id.formTitle);
        if (titleView != null) {
            titleView.setText(title);
        }
        ImageView back = activity.findViewById(R.id.btnFormBack);
        if (back != null) {
            back.setOnClickListener(v -> activity.onBackPressed());
        }
        return activity.findViewById(R.id.formFields);
    }

    public static EditText field(LinearLayout form, String hint, String value, int type) {
        EditText editText = new EditText(form.getContext());
        editText.setHint(hint);
        editText.setInputType(type);
        editText.setText(value == null ? "" : value);
        editText.setSingleLine(true);
        editText.setBackgroundResource(R.drawable.bg_edittext);
        editText.setTextColor(ContextCompat.getColor(form.getContext(), R.color.text_primary));
        editText.setHintTextColor(ContextCompat.getColor(form.getContext(), R.color.text_secondary));
        editText.setTextSize(15f);

        int padH = (int) (16 * form.getResources().getDisplayMetrics().density);
        int padV = (int) (12 * form.getResources().getDisplayMetrics().density);
        editText.setPadding(padH, padV, padH, padV);

        // Add subtle end icon based on hint
        int endIconRes = 0;
        if (hint != null) {
            if (hint.contains("Ngày sinh")) {
                endIconRes = R.drawable.ic_calendar;
            } else if (hint.contains("Giới tính")) {
                endIconRes = R.drawable.ic_person;
            } else if (hint.contains("Nơi ở")) {
                endIconRes = R.drawable.ic_location;
            }
        }
        if (endIconRes != 0) {
            Drawable icon = ContextCompat.getDrawable(form.getContext(), endIconRes);
            if (icon != null) {
                Drawable wrapped = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(wrapped, ContextCompat.getColor(form.getContext(), R.color.text_secondary));
                editText.setCompoundDrawablesWithIntrinsicBounds(null, null, wrapped, null);
                editText.setCompoundDrawablePadding((int) (8 * form.getResources().getDisplayMetrics().density));
            }
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (50 * form.getResources().getDisplayMetrics().density)
        );
        params.bottomMargin = (int) (12 * form.getResources().getDisplayMetrics().density);
        editText.setLayoutParams(params);

        form.addView(editText);
        return editText;
    }

    public static Button button(LinearLayout form, String text, Runnable action) {
        AppCompatButton button = new AppCompatButton(form.getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15f);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setStateListAnimator(null);

        boolean isPrimary = text != null && (
                text.contains("Lưu") || text.contains("Đăng") || text.contains("Gửi") || text.contains("Tạo") || text.contains("Hoàn tất")
        );

        if (isPrimary) {
            button.setBackgroundResource(R.drawable.bg_button_blue);
            button.setTextColor(Color.WHITE);
        } else {
            button.setBackgroundResource(R.drawable.bg_edittext);
            button.setTextColor(ContextCompat.getColor(form.getContext(), R.color.text_primary));
        }

        button.setOnClickListener(v -> {
            if (action != null) action.run();
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (48 * form.getResources().getDisplayMetrics().density)
        );
        params.bottomMargin = (int) (10 * form.getResources().getDisplayMetrics().density);
        button.setLayoutParams(params);

        form.addView(button);
        return button;
    }

    public static void status(Activity activity, String message) {
        TextView statusView = activity.findViewById(R.id.formStatus);
        if (statusView != null) {
            statusView.setText(message);
            if (message != null && !message.trim().isEmpty()) {
                statusView.setVisibility(View.VISIBLE);
            } else {
                statusView.setVisibility(View.GONE);
            }
        }
    }
}
