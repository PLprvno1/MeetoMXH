package com.example.meetomxh;

import android.app.DatePickerDialog;
import android.content.Context;
import android.widget.EditText;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared native selectors for registration/onboarding and profile editing. */
final class ProfileFieldSelectors {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale("vi", "VN"));
    private static final String[] GENDERS = {"Nam", "N\u1eef", "Kh\u00e1c", "Kh\u00f4ng mu\u1ed1n n\u00eau"};
    private static final String[] LOCATIONS = ("An Giang|B\u1eafc Ninh|Cao B\u1eb1ng|C\u00e0 Mau|C\u1ea7n Th\u01a1|\u0110\u00e0 N\u1eb5ng|\u0110\u1eafk L\u1eafk|\u0110i\u1ec7n Bi\u00ean|"
            + "\u0110\u1ed3ng Nai|\u0110\u1ed3ng Th\u00e1p|Gia Lai|H\u00e0 N\u1ed9i|H\u00e0 T\u0129nh|H\u1ea3i Ph\u00f2ng|Hu\u1ebf|H\u01b0ng Y\u00ean|"
            + "Kh\u00e1nh H\u00f2a|Lai Ch\u00e2u|L\u00e2m \u0110\u1ed3ng|L\u1ea1ng S\u01a1n|L\u00e0o Cai|Ngh\u1ec7 An|Ninh B\u00ecnh|Ph\u00fa Th\u1ecd|"
            + "Qu\u1ea3ng Ng\u00e3i|Qu\u1ea3ng Ninh|Qu\u1ea3ng Tr\u1ecb|S\u01a1n La|T\u00e2y Ninh|Th\u00e1i Nguy\u00ean|Thanh H\u00f3a|"
            + "TP. H\u1ed3 Ch\u00ed Minh|Tuy\u00ean Quang|V\u0129nh Long").split("\\|");

    private ProfileFieldSelectors() { }

    static void date(Context context, EditText field) {
        LocalDate utcToday = LocalDate.now(java.time.ZoneOffset.UTC);
        LocalDate initial = parse(field.getTag() instanceof String ? (String) field.getTag() : null);
        if (initial == null) initial = utcToday.minusYears(18);
        DatePickerDialog picker = new DatePickerDialog(context, (view, year, month, day) -> {
            LocalDate selected = LocalDate.of(year, month + 1, day);
            field.setTag(selected.format(ISO));
            field.setText(DISPLAY.format(selected));
        }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        picker.getDatePicker().setMaxDate(utcToday.minusYears(13).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        picker.getDatePicker().setMinDate(LocalDate.of(1900, 1, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        picker.show();
    }

    static void setDate(EditText field, String iso) {
        LocalDate parsed = parse(iso);
        if (parsed == null) { field.setText(""); field.setTag(null); return; }
        field.setTag(parsed.format(ISO)); field.setText(DISPLAY.format(parsed));
    }

    static String dateValue(EditText field) { return field.getTag() instanceof String ? (String) field.getTag() : ""; }
    static boolean validDate(String iso) {
        LocalDate value = parse(iso);
        return value != null && !value.isBefore(LocalDate.of(1900, 1, 1))
                && !value.isAfter(LocalDate.now(java.time.ZoneOffset.UTC).minusYears(13));
    }

    static void gender(Context context, TextView field) {
        new android.app.AlertDialog.Builder(context).setTitle("Ch\u1ecdn gi\u1edbi t\u00ednh")
                .setItems(GENDERS, (dialog, which) -> field.setText(GENDERS[which]))
                .setNegativeButton("H\u1ee7y", null).show();
    }

    static void location(Context context, TextView field) {
        String selected = field.getText() == null ? "" : field.getText().toString();
        int checked = -1;
        for (int i = 0; i < LOCATIONS.length; i++) if (LOCATIONS[i].equals(selected)) checked = i;
        new android.app.AlertDialog.Builder(context).setTitle("N\u01a1i \u1edf hi\u1ec7n t\u1ea1i (t\u00f9y ch\u1ecdn)")
                .setSingleChoiceItems(LOCATIONS, checked, (dialog, which) -> { field.setText(LOCATIONS[which]); dialog.dismiss(); })
                .setNeutralButton("X\u00f3a", (dialog, which) -> field.setText(""))
                .setNegativeButton("H\u1ee7y", null).show();
    }

    static void makeSelector(EditText field) {
        field.setKeyListener(null); field.setFocusable(false); field.setClickable(true);
    }

    private static LocalDate parse(String value) {
        try { return value == null || value.trim().isEmpty() ? null : LocalDate.parse(value.trim(), ISO); }
        catch (Exception ignored) { return null; }
    }
}
