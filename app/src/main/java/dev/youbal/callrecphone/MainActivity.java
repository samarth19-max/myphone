package dev.youbal.callrecphone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import androidx.core.content.FileProvider;
import dev.youbal.callrec.lib.BuiltinAccess;
import dev.youbal.callrec.lib.CallRecorderSetup;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Recents, dialpad, contacts and recordings. Everything shown comes from the phone itself. */
public final class MainActivity extends Activity {
    private static final int REQ_CALL = 10, REQ_LOG = 13, REQ_CONTACTS = 14;
    /** Untrusted ACTION_DIAL data is capped and reduced to characters legal in a tel: URI. */
    private static final int MAX_DIALED = 32;
    private static String safeDialed(String raw) {
        if (raw == null) return "";
        String clean = raw.replaceAll("[^0-9+*#]", "");
        return clean.length() > MAX_DIALED ? clean.substring(0, MAX_DIALED) : clean;
    }

    private PhoneApp app;
    private LinearLayout body;
    private EditText search;
    private ListView list;
    private TextView empty;
    private Rows rows;
    private String page = "recents", dialed = "", pendingNumber = "", recentFilter = "all", recentQuery = "", contactQuery = "";
    private Person selected;
    private List<CallLogs.Entry> recents = new ArrayList<>();
    private final Map<String, Integer> durations = new ConcurrentHashMap<>();
    private TextView dialedText, suggestions;
    private MediaPlayer player;
    /** Identity (display name) of the recording currently playing, or "". */
    private String playing = "";
    /** Invalidates asynchronous decrypt-then-play work when the page or activity changes. */
    private int playToken = 0;
    private final Runnable recordingsChanged = this::refreshLists;
    private OnBackInvokedCallback backCallback;

    // ---- lifecycle ---------------------------------------------------------------------------------------------------
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.init(this); app = PhoneApp.get(this);
        // Android 16+ delivers back gestures through OnBackInvokedDispatcher, not onBackPressed().
        backCallback = () -> { if (page.equals("detail")) show("contacts"); else finish(); };
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        handleIntent(getIntent()); show(page);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); show(page); }
    @Override protected void onResume() { super.onResume(); app.recordingListeners.add(recordingsChanged); loadAll(); }
    @Override protected void onPause() { app.recordingListeners.remove(recordingsChanged); stopPlayback(); super.onPause(); }

    private void handleIntent(Intent intent) {
        if (Intent.ACTION_DIAL.equals(intent.getAction())) { Uri data = intent.getData(); dialed = data == null ? "" : safeDialed(data.getSchemeSpecificPart()); page = "dialpad"; }
    }

    /** Saved names and the call log are read on a background thread; the visible list is refreshed in place when they arrive. */
    private void loadAll() {
        app.reloadNames(this::refreshLists);
        app.io.execute(() -> { List<CallLogs.Entry> log = CallLogs.load(app, 1000); app.main.post(() -> { recents = log; refreshLists(); }); });
    }
    private void refreshLists() {
        if (rows == null) return;
        if (page.equals("recents")) fillRecents(); else if (page.equals("contacts")) fillContacts(); else if (page.equals("recordings")) fillRecordings();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------
    private int dp(float n) { return Ui.dp(this, n); }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private boolean has(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private boolean isDefaultPhone() { return DefaultPhone.held(this); }
    private String clockTime(long time) { return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())); }
    private String dayLabel(long time) {
        LocalDate day = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate(), today = LocalDate.now();
        return day.equals(today) ? "Today" : day.equals(today.minusDays(1)) ? "Yesterday" : day.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()));
    }
    private static String length(int seconds) { return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    private static String size(long bytes) { return bytes < 1024 * 1024 ? Math.max(1, bytes / 1024) + " KB" : String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0); }

    private void placeCall(String number) {
        if (PhoneNumbers.normalize(number).isEmpty() && !number.matches("[+0-9*#]{1,30}")) { toast("Enter a phone number."); return; }
        pendingNumber = safeDialed(number);
        if (pendingNumber.isEmpty()) { toast("Enter a phone number."); return; }
        if (!has(Manifest.permission.CALL_PHONE)) { requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQ_CALL); return; }
        try { getSystemService(TelecomManager.class).placeCall(Uri.fromParts("tel", pendingNumber, null), new Bundle()); }
        catch (SecurityException e) { toast("Allow the Phone permission first."); }
        catch (RuntimeException e) { toast("Could not place the call: " + e.getMessage()); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        boolean granted = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        if (request == REQ_CALL && granted) placeCall(pendingNumber);
        else if (request == REQ_LOG || request == REQ_CONTACTS) { loadAll(); show(page); }
    }

    // ---- page frame --------------------------------------------------------------------------------------------------
    /** Header (search or title), body and bottom navigation. hint is null for pages without search. */
    private void frame(String title, String hint) {
        stopPlayback(); rows = null; list = null; empty = null; search = null; Ui.init(this);
        LinearLayout root = Ui.column(this); root.setBackgroundColor(Ui.BG); root.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> { android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()); v.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom); return insets; });
        setContentView(root); root.requestApplyInsets();
        LinearLayout header = Ui.row(this);
        if (hint != null) {
            LinearLayout pill = Ui.row(this); pill.setBackground(Ui.shape(Ui.TONAL, 28, this));
            pill.addView(Ui.icon(this, "Search", Ui.MUTED, null), lp(dp(44), dp(52)));
            search = new EditText(this); search.setSingleLine(true); search.setTextSize(16); search.setTextColor(Ui.INK); search.setHintTextColor(Ui.MUTED); search.setHint(hint); search.setBackgroundColor(Color.TRANSPARENT);
            search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); search.setText(page.equals("contacts") ? contactQuery : recentQuery);
            search.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int c, int f) {}
                public void onTextChanged(CharSequence s, int st, int before, int count) { if (page.equals("contacts")) contactQuery = s.toString(); else recentQuery = s.toString(); refreshLists(); }
                public void afterTextChanged(Editable e) {}
            });
            pill.addView(search, lp(0, dp(52), 1)); header.addView(pill, lp(0, dp(52), 1));
        } else header.addView(Ui.title(this, title, 28), lp(0, -2, 1));
        header.addView(Ui.icon(this, "Settings", Ui.INK, v -> startActivity(new Intent(this, SettingsActivity.class))), lp(dp(52), dp(52)));
        root.addView(header, lp(-1, dp(56), 0, 4, 0, 8));
        body = Ui.column(this); root.addView(body, lp(-1, 0, 1));
        LinearLayout bar = Ui.row(this); bar.setBackground(Ui.shape(Ui.CARD, 30, this)); bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        String[][] tabs = {{"recents", "Recents", "Recents"}, {"dialpad", "Dialpad", "Dialpad"}, {"contacts", "Contacts", "Contacts"}, {"recordings", "Recordings", "Record"}};
        for (String[] tab : tabs) {
            boolean on = page.equals(tab[0]) || (tab[0].equals("contacts") && page.equals("detail"));
            LinearLayout item = Ui.row(this); item.setGravity(Gravity.CENTER); item.setContentDescription(tab[1]);
            if (on) item.setBackground(Ui.shape(Ui.TINT, 26, this));
            item.addView(Ui.icon(this, tab[2], on ? Ui.PRIMARY : Ui.MUTED, null), lp(dp(28), dp(28)));
            if (on) { TextView t = Ui.title(this, tab[1], 14); t.setTextColor(Ui.PRIMARY); item.addView(t, lp(-2, -2, 6, 0, 0, 0)); }
            item.setOnClickListener(v -> { if (tab[0].equals("dialpad") || !page.equals(tab[0])) show(tab[0]); });
            bar.addView(item, lp(0, dp(48), on ? 2f : 1f));
        }
        root.addView(bar, lp(-1, dp(60), 0, 4, 0, 4));
    }

    private void show(String target) {
        page = target;
        switch (target) {
            case "dialpad": dialpadPage(); break;
            case "contacts": contactsPage(); break;
            case "recordings": recordingsPage(); break;
            case "detail": if (selected != null) { detailPage(selected); break; }
            default: page = "recents"; recentsPage();
        }
    }

    private void listArea() {
        list = new ListView(this); list.setDivider(null); list.setClipToPadding(false); list.setVerticalScrollBarEnabled(false); list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        rows = new Rows(); list.setAdapter(rows);
        empty = Ui.text(this, "", 15, Ui.MUTED); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(24), dp(40), dp(24), 0);
        body.addView(empty); body.addView(list, lp(-1, 0, 1));
    }
    private LinearLayout notice(String title, String text, String button, View.OnClickListener click) {
        LinearLayout card = Ui.card(this); card.setBackground(Ui.shape(Ui.TINT, 20, this));
        card.addView(Ui.title(this, title, 16)); card.addView(Ui.text(this, text, 13, Ui.MUTED)); card.addView(Ui.button(this, button, true, click)); return card;
    }

    // ---- recents -----------------------------------------------------------------------------------------------------
    private void recentsPage() {
        frame("Recents", "Search calls");
        if (!isDefaultPhone() || !has(Manifest.permission.READ_CONTACTS) || !CallLogs.allowed(app))
            body.addView(notice("Finish setup", "Make this your phone app and allow contacts and call log. Takes a minute.", "Open setup", v -> startActivity(new Intent(this, SettingsActivity.class))));
        if (app.prefs.recording() && BuiltinAccess.state(this) == BuiltinAccess.State.STOPPED && CallRecorderSetup.state(this) != CallRecorderSetup.State.READY)
            body.addView(notice("Recording access is off", "Calls are not recorded until it is started again (this happens after a restart). Turn on Wireless debugging first.", "Start it", v -> BuiltinAccess.start(this, new BuiltinAccess.Listener() {
                @Override public void onStatus(String message) { }
                @Override public void onDone(boolean ok, String message) { app.prefs.lastStatus(message); app.main.post(() -> { toast(message); show(page); }); }
            })));
        HorizontalScrollView strip = new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false); LinearLayout filters = Ui.row(this); strip.addView(filters);
        for (String[] f : new String[][]{{"all", "All"}, {"missed", "Missed"}, {"outgoing", "Outgoing"}, {"incoming", "Incoming"}}) {
            String key = f[0]; filters.addView(Ui.chip(this, f[1], recentFilter.equals(key), v -> { recentFilter = key; recentsPage(); }), lp(-2, dp(36), 0, 0, 8, 0));
        }
        body.addView(strip, lp(-1, -2, 0, 0, 0, 4));
        listArea(); fillRecents();
    }
    private void fillRecents() {
        if (rows == null) return;
        rows.items.clear(); SearchText.Query q = new SearchText.Query(recentQuery); String last = "";
        for (CallLogs.Entry e : recents) {
            if (recentFilter.equals("missed") && !e.missed()) continue;
            if (recentFilter.equals("outgoing") && !e.direction.equals("outgoing")) continue;
            if (recentFilter.equals("incoming") && !e.direction.equals("incoming")) continue;
            String name = app.phoneName(e.number);
            if (!q.blank && !SearchText.matches(q, new SearchText.Haystack(name.isEmpty() ? e.number : name, name + " " + e.number, Collections.singletonList(e.number)))) continue;
            String section = dayLabel(e.time); if (!section.equals(last)) { rows.items.add(section); last = section; }
            rows.items.add(e);
        }
        empty.setText(!CallLogs.allowed(app) ? "Allow call log access in Settings to see your calls." : recentQuery.isEmpty() ? "No calls yet." : "No calls match.");
        rows.changed();
    }

    // ---- dialpad -----------------------------------------------------------------------------------------------------
    private void dialpadPage() {
        frame("Dialpad", null);
        dialedText = Ui.title(this, "", 34); dialedText.setGravity(Gravity.CENTER); dialedText.setSingleLine(true);
        body.addView(new View(this), lp(0, 0, 1)); body.addView(dialedText, lp(-1, dp(56)));
        suggestions = Ui.text(this, "", 14, Ui.MUTED); suggestions.setGravity(Gravity.CENTER); suggestions.setMinHeight(dp(48)); body.addView(suggestions);
        TextView paste = Ui.text(this, "Paste", 13, Ui.PRIMARY); paste.setGravity(Gravity.CENTER); paste.setPadding(dp(16), dp(6), dp(16), dp(6));
        paste.setOnClickListener(v -> { ClipboardManager clip = getSystemService(ClipboardManager.class); ClipData data = clip == null ? null : clip.getPrimaryClip(); if (data != null && data.getItemCount() > 0) { dialed = safeDialed(data.getItemAt(0).coerceToText(this).toString()); updateDialed(); } });
        body.addView(paste);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
        for (int r = 0; r < 4; r++) {
            LinearLayout line = Ui.row(this); line.setGravity(Gravity.CENTER);
            for (int c = 0; c < 3; c++) {
                String key = keys[r * 3 + c]; TextView k = Ui.title(this, key, 26); k.setGravity(Gravity.CENTER); Ui.rippleCircle(k, Ui.TONAL);
                k.setOnClickListener(v -> { dialed += key; updateDialed(); });
                if (key.equals("0")) k.setOnLongClickListener(v -> { dialed += "+"; updateDialed(); return true; });
                line.addView(k, lp(dp(72), dp(72), 10, 5, 10, 5));
            }
            body.addView(line);
        }
        LinearLayout last = Ui.row(this); last.setGravity(Gravity.CENTER);
        last.addView(new View(this), lp(dp(72), dp(72), 10, 5, 10, 5));
        last.addView(Ui.circleButton(this, "Call", Ui.OK, Color.WHITE, 72, v -> { if (!dialed.isEmpty()) placeCall(dialed); }), lp(dp(72), dp(72), 10, 5, 10, 5));
        View back = Ui.icon(this, "Delete", Ui.MUTED, v -> { if (!dialed.isEmpty()) { dialed = dialed.substring(0, dialed.length() - 1); updateDialed(); } });
        back.setOnLongClickListener(v -> { dialed = ""; updateDialed(); return true; }); last.addView(back, lp(dp(72), dp(72), 10, 5, 10, 5));
        body.addView(last); updateDialed();
    }
    private void updateDialed() {
        if (dialed.length() > MAX_DIALED) dialed = dialed.substring(0, MAX_DIALED);
        dialedText.setText(PhoneNumbers.display(dialed));
        StringBuilder names = new StringBuilder(); int found = 0;
        if (!dialed.isEmpty()) {
            SearchText.Query q = new SearchText.Query(dialed);
            for (Person p : app.people()) if (!p.numbers.isEmpty() && SearchText.matches(q, p.search)) { if (found++ > 0) names.append("  ·  "); names.append(p.name); if (found == 3) break; }
        }
        suggestions.setText(names);
    }

    // ---- contacts ----------------------------------------------------------------------------------------------------
    private void contactsPage() {
        frame("Contacts", "Search name or number");
        if (!has(Manifest.permission.READ_CONTACTS)) body.addView(notice("Show your contacts", "Allow Contacts so people saved in Google, on the phone or on the SIM show here.", "Allow contacts", v -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS)));
        listArea(); fillContacts();
    }
    private void fillContacts() {
        if (rows == null) return;
        rows.items.clear(); SearchText.Query q = new SearchText.Query(contactQuery); List<Person> found = new ArrayList<>();
        for (Person p : app.people()) if (!p.numbers.isEmpty() && SearchText.matches(q, p.search)) found.add(p);
        if (!q.blank) found.sort(Comparator.comparingInt((Person p) -> SearchText.rank(q, p.search)).thenComparing((a, b) -> a.name.compareToIgnoreCase(b.name)));
        String previous = "";
        for (Person p : found) {
            if (q.blank) { String first = p.name.isEmpty() ? "#" : p.name.substring(0, 1).toUpperCase(Locale.ROOT); if (!Character.isLetter(first.charAt(0))) first = "#"; if (!first.equals(previous)) { rows.items.add(first); previous = first; } }
            rows.items.add(p);
        }
        empty.setText(!has(Manifest.permission.READ_CONTACTS) ? "" : contactQuery.isEmpty() ? "No contacts found." : "No contacts match.");
        rows.changed();
    }

    private void detailPage(Person person) {
        selected = person; page = "detail"; frame(person.name, null);
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); LinearLayout content = Ui.column(this); scroll.addView(content); body.addView(scroll, lp(-1, 0, 1));
        LinearLayout hero = Ui.column(this); hero.setGravity(Gravity.CENTER_HORIZONTAL); hero.setPadding(0, dp(8), 0, dp(12));
        hero.addView(Ui.avatar(this, person.name, 96), lp(dp(96), dp(96)));
        TextView name = Ui.title(this, person.name, 24); name.setGravity(Gravity.CENTER); hero.addView(name, lp(-1, -2, 0, 10, 0, 0));
        hero.addView(Ui.tag(this, person.source, Ui.MUTED, Ui.TONAL), lp(-2, -2, 0, 6, 0, 0)); content.addView(hero);
        LinearLayout numbers = Ui.card(this); content.addView(numbers);
        for (String number : person.numbers) {
            LinearLayout line = Ui.row(this); TextView t = Ui.title(this, PhoneNumbers.display(number), 18); t.setTextIsSelectable(true); line.addView(t, lp(0, -2, 1));
            line.addView(Ui.circleButton(this, "Call", Ui.OK_TINT, Ui.OK, 40, v -> placeCall(number)), lp(dp(40), dp(40))); numbers.addView(line);
        }
        LinearLayout noteCard = Ui.card(this); content.addView(noteCard); String key = person.primaryNumber(); String note = app.notes.get(key);
        noteCard.addView(Ui.label(this, "Note")); TextView noteText = Ui.text(this, note.isEmpty() ? "No note yet." : note, 15, note.isEmpty() ? Ui.MUTED : Ui.INK); noteCard.addView(noteText);
        noteCard.addView(Ui.button(this, note.isEmpty() ? "Add a note" : "Edit note", false, v -> {
            EditText box = new EditText(this); box.setText(app.notes.get(key)); box.setMinLines(3); box.setGravity(Gravity.TOP);
            new AlertDialog.Builder(this).setTitle("Note for " + person.name).setView(box).setPositiveButton("Save", (d, n) -> { app.notes.set(key, box.getText().toString()); detailPage(person); }).setNegativeButton("Cancel", null).show();
        }));
        List<Vault.Rec> theirs = new ArrayList<>();
        for (Vault.Rec r : Vault.list(this)) for (String n : person.numbers) if (PhoneNumbers.normalize(r.number).equals(n)) { theirs.add(r); break; }
        if (!theirs.isEmpty()) {
            LinearLayout recs = Ui.card(this); content.addView(recs); recs.addView(Ui.label(this, "Recordings"));
            for (Vault.Rec r : theirs) { TextView t = Ui.text(this, dayLabel(r.startedAt) + " · " + clockTime(r.startedAt) + " · " + size(r.sizeBytes), 15, Ui.PRIMARY); t.setPadding(0, dp(8), 0, dp(8)); t.setOnClickListener(v -> recordingMenu(r)); recs.addView(t); }
        }
    }

    // ---- recordings --------------------------------------------------------------------------------------------------
    private void recordingsPage() {
        frame("Recordings", null);
        String status = app.prefs.lastStatus();
        if (!status.isEmpty()) body.addView(Ui.text(this, status, 13, Ui.MUTED), lp(-1, -2, 0, 0, 0, 6));
        listArea(); fillRecordings(); loadDurations();
    }
    private void fillRecordings() {
        if (rows == null) return;
        rows.items.clear(); rows.items.addAll(Vault.list(this));
        empty.setText(rows.items.isEmpty() ? "No recordings yet. Finish setup in Settings, then make a call." : "");
        rows.changed();
    }
    /** Lengths are read in the background once; rows show them when ready. */
    private void loadDurations() {
        List<Vault.Rec> all = Vault.list(this);
        app.io.execute(() -> {
            boolean any = false;
            for (Vault.Rec r : all) {
                String key = r.file.getAbsolutePath() + r.sizeBytes; if (durations.containsKey(key)) continue;
                int seconds = 0;
                if (r.encrypted) {
                    // The duration is stored in the authenticated header, so no decryption is needed.
                    seconds = (int) (r.durationMs / 1000);
                } else {
                    MediaMetadataRetriever reader = new MediaMetadataRetriever();
                    try { reader.setDataSource(r.file.getAbsolutePath()); String ms = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION); seconds = ms == null ? 0 : (int) (Long.parseLong(ms) / 1000); }
                    catch (RuntimeException e) { seconds = 0; } finally { try { reader.release(); } catch (Exception ignored) { /* Nothing to free. */ } }
                }
                durations.put(key, seconds); any = true;
            }
            if (any) app.main.post(() -> { if (rows != null && page.equals("recordings")) rows.notifyDataSetChanged(); });
        });
    }
    private void recordingMenu(Vault.Rec r) {
        boolean isPlaying = playing.equals(r.displayName());
        Ui.sheet(this, "Recording", new String[]{isPlaying ? "Stop" : "Play", "Share", "Save to phone (unencrypted)", "Delete"}, -1, n -> {
            if (n == 0) { if (isPlaying) stopPlayback(); else play(r); }
            else if (n == 1) share(r);
            else if (n == 2) saveToPhone(r);
            else new AlertDialog.Builder(this).setTitle("Delete this recording?").setMessage("It cannot be recovered.")
                .setPositiveButton("Delete", (d, w) -> { stopPlayback(); Vault.delete(this, r); show(page); }).setNegativeButton("Cancel", null).show();
        }).show();
    }
    private void play(Vault.Rec r) {
        stopPlayback();
        final int token = ++playToken;
        app.io.execute(() -> {
            File plain;
            try { plain = Vault.plainFile(this, r); }
            catch (Exception e) { app.main.post(() -> toast("Could not decrypt this recording: " + e.getMessage())); return; }
            app.main.post(() -> {
                if (token != playToken || isFinishing() || isDestroyed()) return;
                try {
                    player = new MediaPlayer(); player.setDataSource(plain.getAbsolutePath()); player.prepare(); player.start();
                    playing = r.displayName();
                    player.setOnCompletionListener(p -> stopPlayback());
                    toast("Playing. Tap the recording and choose Stop to end it.");
                    if (rows != null) rows.notifyDataSetChanged();
                } catch (Exception e) { stopPlayback(); toast("Could not play this recording."); }
            });
        });
    }
    private void stopPlayback() {
        playToken++;
        if (player != null) { try { player.release(); } catch (RuntimeException ignored) { /* Already released. */ } player = null; }
        playing = "";
    }
    /** Always hands the receiving app a real, decrypted audio file — never ciphertext. */
    private void share(Vault.Rec r) {
        app.io.execute(() -> {
            try {
                File plain = Vault.plainFile(this, r);
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", plain);
                app.main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    try {
                        Intent send = new Intent(Intent.ACTION_SEND).setType(Vault.MIME)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        // ClipData makes the system grant read access to the ONE app the user picks.
                        // Deliberately do not grantUriPermission() to every resolved handler: that
                        // would let any ACTION_SEND app read a predictable decrypted content URI.
                        send.setClipData(new ClipData("recording", new String[]{Vault.MIME}, new ClipData.Item(uri)));
                        Intent chooser = Intent.createChooser(send, "Share decrypted recording");
                        chooser.setClipData(new ClipData("recording", new String[]{Vault.MIME}, new ClipData.Item(uri)));
                        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(chooser);
                    } catch (RuntimeException e) { toast("Could not share this recording."); }
                });
            } catch (Exception e) {
                app.main.post(() -> toast("Could not decrypt this recording for sharing."));
            }
        });
    }
    /** Writes an unencrypted .m4a into Music/Callrec Phone so the phone itself can keep it. */
    private void saveToPhone(Vault.Rec r) {
        app.io.execute(() -> {
            try {
                String where = Vault.saveToPhone(this, r);
                app.main.post(() -> toast("Saved unencrypted: " + where));
            } catch (Exception e) {
                app.main.post(() -> toast("Could not save an unencrypted copy: " + e.getMessage()));
            }
        });
    }

    // ---- list rows ---------------------------------------------------------------------------------------------------
    private final class Rows extends BaseAdapter {
        final List<Object> items = new ArrayList<>();
        void changed() { notifyDataSetChanged(); empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE); }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return items.get(i) instanceof String ? 0 : 1; }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            Object item = items.get(i);
            if (item instanceof String) {
                TextView header = convert instanceof TextView ? (TextView) convert : Ui.label(MainActivity.this, "");
                header.setText(((String) item).toUpperCase(Locale.ROOT)); return header;
            }
            Row row = convert == null ? new Row() : (Row) convert.getTag();
            row.bind(item); return row.root;
        }
    }

    private final class Row {
        final LinearLayout root; final TextView avatar, title, sub; final View action;
        Row() {
            root = Ui.row(MainActivity.this); root.setPadding(dp(8), dp(10), dp(4), dp(10)); Ui.ripple(root, Color.TRANSPARENT, 16); root.setTag(this);
            avatar = Ui.avatar(MainActivity.this, "?", 44); root.addView(avatar, lp(dp(44), dp(44)));
            LinearLayout words = Ui.column(MainActivity.this); words.setPadding(dp(14), 0, dp(6), 0);
            title = Ui.title(MainActivity.this, "", 16); Ui.ellipsize(title); words.addView(title);
            sub = Ui.text(MainActivity.this, "", 12, Ui.MUTED); Ui.ellipsize(sub); words.addView(sub);
            root.addView(words, lp(0, -2, 1));
            action = Ui.circleButton(MainActivity.this, "Call", Ui.OK_TINT, Ui.OK, 42, null); root.addView(action, lp(dp(42), dp(42), 2, 0, 4, 0));
        }
        void bind(Object item) {
            if (item instanceof CallLogs.Entry) {
                CallLogs.Entry e = (CallLogs.Entry) item; String name = app.phoneName(e.number), shown = !name.isEmpty() ? name : e.number.isEmpty() ? "Private number" : PhoneNumbers.display(e.number), where = app.sourceOf(e.number);
                Ui.bindAvatar(avatar, !name.isEmpty() ? name : "+"); title.setText(shown); title.setTextColor(e.missed() ? Ui.DANGER : Ui.INK);
                sub.setText(e.subtitle() + " · " + clockTime(e.time) + (where.isEmpty() ? "" : " · " + where));
                Ui.setIcon(action, "Call", Ui.OK); Ui.rippleCircle(action, Ui.OK_TINT); action.setVisibility(View.VISIBLE);
                action.setOnClickListener(v -> placeCall(e.number)); root.setOnClickListener(v -> placeCall(e.number));
                root.setOnLongClickListener(v -> { Ui.sheet(MainActivity.this, shown, new String[]{"Call", "Copy number"}, -1, n -> { if (n == 0) placeCall(e.number); else { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("number", e.number)); toast("Copied."); } }).show(); return true; });
            } else if (item instanceof Person) {
                Person p = (Person) item; Ui.bindAvatar(avatar, p.name); title.setText(p.name); title.setTextColor(Ui.INK);
                sub.setText(PhoneNumbers.display(p.primaryNumber()) + (p.numbers.size() > 1 ? "  +" + (p.numbers.size() - 1) : "") + " · " + p.source);
                Ui.setIcon(action, "Call", Ui.OK); Ui.rippleCircle(action, Ui.OK_TINT); action.setVisibility(View.VISIBLE);
                action.setOnClickListener(v -> callPerson(p)); root.setOnClickListener(v -> detailPage(p)); root.setOnLongClickListener(null); root.setLongClickable(false);
            } else if (item instanceof Vault.Rec) {
                Vault.Rec r = (Vault.Rec) item; String name = app.phoneName(r.number), shown = !name.isEmpty() ? name : PhoneNumbers.display(r.number);
                Integer seconds = durations.get(r.file.getAbsolutePath() + r.sizeBytes);
                Ui.bindAvatar(avatar, !name.isEmpty() ? name : "+"); title.setText(shown.equals("unknown") ? "Unknown number" : shown); title.setTextColor(Ui.INK);
                sub.setText((r.direction.equals("in") ? "Incoming" : r.direction.equals("out") ? "Outgoing" : "Call") + " · " + dayLabel(r.startedAt) + " " + clockTime(r.startedAt) + " · " + (seconds != null && seconds > 0 ? length(seconds) + " · " : "") + size(r.sizeBytes)
                    + (r.broken ? " · Damaged" : r.encrypted ? " · Encrypted" : " · Unencrypted"));
                boolean on = playing.equals(r.displayName()); Ui.setIcon(action, "Play", on ? Ui.ON_PRIMARY : Ui.PRIMARY); Ui.rippleCircle(action, on ? Ui.PRIMARY : Ui.TINT); action.setVisibility(View.VISIBLE);
                action.setOnClickListener(v -> { if (playing.equals(r.displayName())) stopPlayback(); else play(r); rows.notifyDataSetChanged(); }); root.setOnClickListener(v -> recordingMenu(r)); root.setOnLongClickListener(null); root.setLongClickable(false);
            }
        }
    }
    private void callPerson(Person p) {
        if (p.numbers.size() == 1) { placeCall(p.numbers.get(0)); return; }
        String[] labels = new String[p.numbers.size()]; for (int i = 0; i < labels.length; i++) labels[i] = PhoneNumbers.display(p.numbers.get(i));
        Ui.sheet(this, p.name, labels, -1, n -> placeCall(p.numbers.get(n))).show();
    }
}
