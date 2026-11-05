package dev.youbal.callrecphone;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import java.util.function.IntConsumer;

/** Small view toolkit: one palette (light and dark), flat surfaces, ripples, and a few drawn icons. */
final class Ui {
    // Set by init(); read everywhere else. Dark follows the system setting.
    static int BG, CARD, TONAL, INK, MUTED, LINE, PRIMARY, ON_PRIMARY, TINT, OK, OK_TINT, DANGER, DANGER_TINT, WARN;
    static boolean dark;

    static void init(Context c) {
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            BG = 0xff0b1020; CARD = 0xff151b2f; TONAL = 0xff1f2742; INK = 0xffe8ecf8; MUTED = 0xff9aa4bf; LINE = 0xff273153;
            PRIMARY = 0xff7aa2ff; ON_PRIMARY = 0xff0b1020; TINT = 0xff22305e; OK = 0xff3ddc84; OK_TINT = 0xff123a2a; DANGER = 0xffff6b6b; DANGER_TINT = 0xff432024; WARN = 0xfff2b04a;
        } else {
            BG = 0xfff4f6fb; CARD = 0xffffffff; TONAL = 0xffe8edf8; INK = 0xff0f172a; MUTED = 0xff5b6478; LINE = 0xffe2e7f0;
            PRIMARY = 0xff2563eb; ON_PRIMARY = 0xffffffff; TINT = 0xffdbe7ff; OK = 0xff16a34a; OK_TINT = 0xffd9f5e3; DANGER = 0xffdc2626; DANGER_TINT = 0xfffde3e3; WARN = 0xffb45309;
        }
    }
    static { // Safe defaults before the first activity calls init().
        BG = 0xfff4f6fb; CARD = 0xffffffff; TONAL = 0xffe8edf8; INK = 0xff0f172a; MUTED = 0xff5b6478; LINE = 0xffe2e7f0;
        PRIMARY = 0xff2563eb; ON_PRIMARY = 0xffffffff; TINT = 0xffdbe7ff; OK = 0xff16a34a; OK_TINT = 0xffd9f5e3; DANGER = 0xffdc2626; DANGER_TINT = 0xfffde3e3; WARN = 0xffb45309;
    }

    static int dp(Context c, float n) { return Math.round(c.getResources().getDisplayMetrics().density * n); }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static LinearLayout row(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    static GradientDrawable shape(int color, int radius, Context c) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radius)); return d; }
    static GradientDrawable circle(int color) { GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(color); return d; }
    static GradientDrawable topRounded(int color, int radius, Context c) {
        GradientDrawable d = shape(color, 0, c); float r = dp(c, radius); d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0}); return d;
    }
    static int alpha(int color, int a) { return (color & 0x00ffffff) | (a << 24); }
    /** Rounded fill with a ripple that stays inside the rounded shape. */
    static void ripple(View v, int fill, int radius) {
        Context c = v.getContext();
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(alpha(PRIMARY, 40)), shape(fill, radius, c), shape(Color.WHITE, radius, c)));
    }
    static void rippleCircle(View v, int fill) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(alpha(PRIMARY, 40)), circle(fill), circle(Color.WHITE)));
    }

    static TextView text(Context c, String value, int size, int color) { TextView v = new TextView(c); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0, dp(c, 3), 0, dp(c, 3)); return v; }
    static TextView title(Context c, String value, int size) { TextView v = text(c, value, size, INK); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return v; }
    /** Small uppercase section label. */
    static TextView label(Context c, String value) {
        TextView v = text(c, value.toUpperCase(Locale.ROOT), 11, MUTED); v.setLetterSpacing(0.08f); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); v.setPadding(dp(c, 4), dp(c, 14), 0, dp(c, 6)); return v;
    }
    static void ellipsize(TextView v) { v.setSingleLine(true); v.setEllipsize(android.text.TextUtils.TruncateAt.END); }

    /** Full-width button: primary (filled) or tonal. */
    static Button button(Context c, String value, boolean primary, View.OnClickListener listener) {
        Button b = new Button(c); b.setText(value); b.setTextSize(15); b.setAllCaps(false); b.setStateListAnimator(null); b.setElevation(0);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? ON_PRIMARY : PRIMARY);
        ripple(b, primary ? PRIMARY : TINT, 26); b.setPadding(dp(c, 18), dp(c, 10), dp(c, 18), dp(c, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, 50)); lp.setMargins(0, dp(c, 6), 0, dp(c, 4)); b.setLayoutParams(lp); b.setOnClickListener(listener); return b;
    }
    static LinearLayout card(Context c) {
        LinearLayout v = column(c); v.setPadding(dp(c, 18), dp(c, 14), dp(c, 18), dp(c, 14)); v.setBackground(shape(CARD, 20, c));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(c, 6), 0, dp(c, 6)); v.setLayoutParams(lp); return v;
    }

    static View icon(Context c, String name, int color, View.OnClickListener click) {
        View v = new Icon(c, name, color); v.setContentDescription(name); v.setMinimumWidth(dp(c, 48)); v.setMinimumHeight(dp(c, 48));
        if (click != null) { rippleCircle(v, Color.TRANSPARENT); v.setOnClickListener(click); }
        return v;
    }
    /** A round button holding an icon, e.g. call, WhatsApp or an in-call control. */
    static View circleButton(Context c, String icon, int fill, int fg, int size, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(c); f.setContentDescription(icon); rippleCircle(f, fill);
        int pad = dp(c, size) / 4; Icon i = new Icon(c, icon, fg); f.addView(i, new FrameLayout.LayoutParams(-1, -1));
        i.setPadding(pad, pad, pad, pad); if (click != null) f.setOnClickListener(click);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(c, size), dp(c, size))); return f;
    }
    static void setIcon(View circle, String icon, int fg) {
        if (circle instanceof FrameLayout && ((FrameLayout) circle).getChildCount() > 0) { ((Icon) ((FrameLayout) circle).getChildAt(0)).set(icon, fg); circle.setContentDescription(icon); }
    }

    static TextView chip(Context c, String value, boolean selected, View.OnClickListener click) {
        TextView v = text(c, value, 13, selected ? ON_PRIMARY : INK); v.setGravity(Gravity.CENTER); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setMinHeight(dp(c, 36)); v.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6)); ripple(v, selected ? PRIMARY : TONAL, 18); v.setOnClickListener(click); return v;
    }
    /** A tiny status pill, e.g. "Lead" or "New". */
    static TextView tag(Context c, String value, int fg, int bg) {
        TextView v = text(c, value, 11, fg); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); v.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2)); v.setBackground(shape(bg, 8, c)); return v;
    }

    private static final int[][] AVATARS = {
        {0xffdbe7ff, 0xff1d4ed8}, {0xffd9f5e3, 0xff15803d}, {0xfffde7c8, 0xffb45309}, {0xffeadcff, 0xff7e22ce},
        {0xffd3f1f7, 0xff0e7490}, {0xffffdde6, 0xffbe185d}, {0xffe6ecc8, 0xff4d7c0f}, {0xffe0e0f5, 0xff4338ca}};
    private static final int[][] AVATARS_DARK = {
        {0xff22305e, 0xff9db9ff}, {0xff123a2a, 0xff6ee7a0}, {0xff4a3519, 0xfff2b04a}, {0xff39275f, 0xffd2b4ff},
        {0xff113b44, 0xff67d4ea}, {0xff4a1f31, 0xffff9ebd}, {0xff2f3b16, 0xffc4e47a}, {0xff2a2a5c, 0xffa5a6ff}};
    static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("[\\s._-]+");
        String first = parts[0], last = parts.length > 1 ? parts[parts.length - 1] : "";
        if (first.isEmpty()) return "?";
        String out = first.substring(0, first.offsetByCodePoints(0, 1));
        if (!last.isEmpty()) out += last.substring(0, last.offsetByCodePoints(0, 1));
        return out.toUpperCase(Locale.ROOT);
    }
    static TextView avatar(Context c, String name, int diameter) {
        TextView v = title(c, "", diameter > 80 ? 34 : diameter > 48 ? 22 : diameter > 40 ? 16 : 14); v.setGravity(Gravity.CENTER); v.setIncludeFontPadding(false); v.setPadding(0, 0, 0, 0);
        bindAvatar(v, name); return v;
    }
    /** Re-skins a recycled avatar for another name. */
    static void bindAvatar(TextView v, String name) {
        int slot = Math.abs((name == null ? "" : name).hashCode() % AVATARS.length); int[] colors = (dark ? AVATARS_DARK : AVATARS)[slot];
        v.setText(initials(name)); v.setBackground(circle(colors[0])); v.setTextColor(colors[1]);
    }

    /** Bottom sheet with a title and tappable rows. The chosen row shows a tick. */
    static Dialog sheet(Context c, String title, String[] labels, int selected, IntConsumer pick) {
        Dialog d = new Dialog(c); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = column(c); panel.setBackground(topRounded(CARD, 26, c)); panel.setPadding(dp(c, 8), dp(c, 14), dp(c, 8), dp(c, 20));
        View grab = new View(c); grab.setBackground(shape(LINE, 3, c)); LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(c, 36), dp(c, 4)); gl.gravity = Gravity.CENTER_HORIZONTAL; gl.bottomMargin = dp(c, 10); panel.addView(grab, gl);
        if (title != null && !title.isEmpty()) { TextView t = title(c, title, 18); t.setPadding(dp(c, 16), dp(c, 4), dp(c, 16), dp(c, 8)); panel.addView(t); }
        for (int i = 0; i < labels.length; i++) {
            final int index = i; LinearLayout item = row(c); item.setMinimumHeight(dp(c, 52)); item.setPadding(dp(c, 16), 0, dp(c, 12), 0); ripple(item, Color.TRANSPARENT, 14);
            TextView t = text(c, labels[i], 16, i == selected ? PRIMARY : INK); if (i == selected) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); item.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            if (i == selected) item.addView(new Icon(c, "Check", PRIMARY), new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));
            item.setOnClickListener(v -> { d.dismiss(); pick.accept(index); }); panel.addView(item);
        }
        d.setContentView(panel); Window w = d.getWindow();
        if (w != null) { w.setLayout(-1, -2); w.setGravity(Gravity.BOTTOM); w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); w.setWindowAnimations(android.R.style.Animation_InputMethod); }
        return d;
    }

    static final class Icon extends View {
        private String name; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); private final Path path = new Path();
        Icon(Context c, String name, int color) { super(c); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); set(name, color); }
        void set(String name, int color) { this.name = name; paint.setColor(color); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth() - getPaddingLeft() - getPaddingRight(), h = getHeight() - getPaddingTop() - getPaddingBottom();
            float size = getPaddingLeft() > 0 ? Math.min(w, h) : dp(getContext(), 24);
            canvas.save(); canvas.translate(getPaddingLeft() + (w - size) / 2f, getPaddingTop() + (h - size) / 2f); canvas.scale(size / 24f, size / 24f);
            boolean fill = name.endsWith("Fill"); String base = fill ? name.substring(0, name.length() - 4) : name;
            paint.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE); paint.setStrokeWidth(1.9f); Path p = path; p.reset();
            switch (base) {
                case "Recents" -> { canvas.drawCircle(12, 12, 9, paint); canvas.drawLine(12, 7, 12, 12, paint); canvas.drawLine(12, 12, 16, 14, paint); }
                case "Dialpad" -> { paint.setStyle(Paint.Style.FILL); for (int y = 5; y <= 19; y += 7) for (int x = 5; x <= 19; x += 7) canvas.drawCircle(x, y, 1.8f, paint); }
                case "Contacts" -> { canvas.drawCircle(12, 8, 4, paint); canvas.drawArc(4, 14, 20, 30, 180, 180, false, paint); }
                case "Search" -> { canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); canvas.drawLine(15.5f, 15.5f, 20.5f, 20.5f, paint); }
                case "Call" -> phone(canvas, p);
                case "End" -> { canvas.rotate(135, 12, 12); phone(canvas, p); }
                case "WhatsApp" -> { p.moveTo(4.4f, 17.4f); p.cubicTo(1.3f, 12.5f, 3, 5.5f, 8.7f, 3.3f); p.cubicTo(15, 1, 21.2f, 5.8f, 21.2f, 11.8f); p.cubicTo(21.2f, 18.1f, 14.8f, 22.3f, 8.3f, 19.6f); p.lineTo(3.2f, 21); p.close(); canvas.drawPath(p, paint); p.reset(); p.moveTo(8, 7.4f); p.lineTo(9.5f, 7.3f); p.lineTo(10.6f, 10); p.lineTo(9.5f, 11.2f); p.cubicTo(10.2f, 12.8f, 11.5f, 14, 13.2f, 14.7f); p.lineTo(14.5f, 13.5f); p.lineTo(17, 14.7f); p.lineTo(16.8f, 16.1f); p.quadTo(16.4f, 17.3f, 14.6f, 16.9f); p.cubicTo(10.6f, 16.1f, 7.4f, 12.5f, 7.1f, 9.5f); p.quadTo(6.9f, 8, 8, 7.4f); canvas.drawPath(p, paint); }
                case "Back" -> { canvas.drawLine(19, 12, 5, 12, paint); p.moveTo(11, 6); p.lineTo(5, 12); p.lineTo(11, 18); canvas.drawPath(p, paint); }
                case "Sort" -> { canvas.drawLine(4, 7, 20, 7, paint); canvas.drawLine(4, 12, 15, 12, paint); canvas.drawLine(4, 17, 10, 17, paint); }
                case "Refresh" -> { canvas.drawArc(4, 4, 20, 20, 40, 290, false, paint); p.moveTo(20, 4); p.lineTo(20, 10); p.lineTo(14, 10); canvas.drawPath(p, paint); }
                case "Settings" -> { canvas.drawCircle(12, 12, 3.5f, paint); canvas.drawCircle(12, 12, 8, paint); for (int i = 0; i < 8; i++) { canvas.save(); canvas.rotate(i * 45, 12, 12); canvas.drawLine(12, 1.5f, 12, 4, paint); canvas.restore(); } }
                case "Delete" -> { p.moveTo(9, 5); p.lineTo(21, 5); p.lineTo(21, 19); p.lineTo(9, 19); p.lineTo(2, 12); p.close(); canvas.drawPath(p, paint); paint.setStyle(Paint.Style.STROKE); canvas.drawLine(12.5f, 9, 17, 15, paint); canvas.drawLine(17, 9, 12.5f, 15, paint); }
                case "Mic", "Mute" -> {
                    canvas.drawRoundRect(new RectF(8.5f, 2.5f, 15.5f, 14f), 3.5f, 3.5f, paint); paint.setStyle(Paint.Style.STROKE); canvas.drawArc(5, 7, 19, 19, 0, 180, false, paint); canvas.drawLine(12, 19, 12, 22, paint);
                    if (base.equals("Mute")) canvas.drawLine(4, 4, 20, 20, paint);
                }
                case "Speaker" -> { p.moveTo(4, 9.5f); p.lineTo(8, 9.5f); p.lineTo(13, 5); p.lineTo(13, 19); p.lineTo(8, 14.5f); p.lineTo(4, 14.5f); p.close(); canvas.drawPath(p, paint); paint.setStyle(Paint.Style.STROKE); canvas.drawArc(10, 8, 18, 16, -50, 100, false, paint); canvas.drawArc(9, 4.5f, 21, 19.5f, -45, 90, false, paint); }
                case "Pause" -> { canvas.drawRoundRect(new RectF(6.5f, 5, 10.5f, 19), 1.5f, 1.5f, paint); canvas.drawRoundRect(new RectF(13.5f, 5, 17.5f, 19), 1.5f, 1.5f, paint); }
                case "Star" -> { for (int i = 0; i < 10; i++) { double a = -Math.PI / 2 + i * Math.PI / 5; float r = i % 2 == 0 ? 9.5f : 4.1f; float x = 12 + (float) Math.cos(a) * r, y = 12.6f + (float) Math.sin(a) * r; if (i == 0) p.moveTo(x, y); else p.lineTo(x, y); } p.close(); canvas.drawPath(p, paint); }
                case "Plus" -> { canvas.drawLine(12, 5, 12, 19, paint); canvas.drawLine(5, 12, 19, 12, paint); }
                case "Close" -> { canvas.drawLine(6, 6, 18, 18, paint); canvas.drawLine(18, 6, 6, 18, paint); }
                case "Record" -> { canvas.drawCircle(12, 12, 9, paint); paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 12, 4.2f, paint); }
                case "Play" -> { paint.setStyle(Paint.Style.FILL); p.moveTo(8, 5); p.lineTo(19, 12); p.lineTo(8, 19); p.close(); canvas.drawPath(p, paint); }
                case "Check" -> { p.moveTo(5, 12.5f); p.lineTo(10, 17.5f); p.lineTo(19, 7); canvas.drawPath(p, paint); }
                case "Chevron" -> { p.moveTo(6, 9); p.lineTo(12, 15); p.lineTo(18, 9); canvas.drawPath(p, paint); }
                case "Out" -> { canvas.drawLine(7, 17, 17, 7, paint); p.moveTo(9, 7); p.lineTo(17, 7); p.lineTo(17, 15); canvas.drawPath(p, paint); }
                case "In" -> { canvas.drawLine(17, 7, 7, 17, paint); p.moveTo(15, 17); p.lineTo(7, 17); p.lineTo(7, 9); canvas.drawPath(p, paint); }
                case "Sync" -> { canvas.drawArc(4, 4, 20, 20, 200, 250, false, paint); p.moveTo(4, 5); p.lineTo(4.5f, 11); p.lineTo(10, 10); canvas.drawPath(p, paint); }
                default -> { paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 5, 1.4f, paint); canvas.drawCircle(12, 12, 1.4f, paint); canvas.drawCircle(12, 19, 1.4f, paint); }
            }
            canvas.restore();
        }
        private void phone(Canvas canvas, Path p) { p.moveTo(5, 3); p.lineTo(9, 3); p.lineTo(10, 8); p.lineTo(7, 10); p.cubicTo(9, 14, 10, 15, 14, 17); p.lineTo(16, 14); p.lineTo(21, 15); p.lineTo(21, 19); p.quadTo(21, 22, 17, 21); p.cubicTo(10, 19, 5, 14, 3, 7); p.quadTo(2, 3, 5, 3); canvas.drawPath(p, paint); }
    }
}
