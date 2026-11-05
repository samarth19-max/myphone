package dev.youbal.callrecphone;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** What is known about the person on the line, laid out to read at a glance: where they are saved, the last call, and your note. */
final class CallCard extends LinearLayout {
    private final View savedBox, lastBox;
    private final TextView saved, last, note;

    CallCard(Context c) {
        super(c); setOrientation(VERTICAL); setBackground(Ui.shape(0x1fffffff, 20, c)); setPadding(dp(16), dp(4), dp(16), dp(14));
        saved = value(false); savedBox = section("SAVED AS", saved);
        last = value(false); lastBox = section("LAST CALL", last);
        note = value(true); section("NOTE", note);
    }

    private int dp(float n) { return Ui.dp(getContext(), n); }
    private TextView value(boolean editable) {
        TextView v = new TextView(getContext()); v.setTextColor(Color.WHITE); v.setTextSize(15); v.setMaxLines(4); v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (editable) v.setPadding(0, dp(2), 0, dp(2));
        return v;
    }
    private View section(String title, View content) {
        LinearLayout box = new LinearLayout(getContext()); box.setOrientation(VERTICAL);
        TextView label = new TextView(getContext()); label.setText(title); label.setTextSize(10); label.setTextColor(0x99ffffff); label.setLetterSpacing(0.14f); label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(label); box.addView(content);
        LayoutParams p = new LayoutParams(-1, -2); p.topMargin = dp(12); addView(box, p); return box;
    }

    /** savedLine and lastCall may be empty; an empty note shows a prompt and tapping the note edits it. */
    void bind(String savedLine, String lastCall, String noteText, View.OnClickListener editNote) {
        savedBox.setVisibility(savedLine.isEmpty() ? GONE : VISIBLE); saved.setText(savedLine);
        lastBox.setVisibility(lastCall.isEmpty() ? GONE : VISIBLE); last.setText(lastCall);
        note.setText(noteText.isEmpty() ? "Tap to add a note" : noteText); note.setTextColor(noteText.isEmpty() ? 0x99ffffff : Color.WHITE); note.setOnClickListener(editNote);
    }
}
