package app.openring.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.time.ZoneId;

import app.openring.core.PermissionAsk;
import app.openring.core.SleepSounds;
import app.openring.core.analysis.SoundDetector;
import app.openring.data.Db;
import app.openring.data.Prefs;
import app.openring.data.SoundNight;
import app.openring.data.Sounds;

/**
 * Sleep sounds' own screen (issue #380, phase 1b), in one of three modes:
 * <ul>
 * <li>{@link #CONSENT}: the text before the first night (it names the person next to you and backups), "I agree, ask for the
 * microphone", then the microphone asked in context (Android's rule of issue #140: refused twice, the way to the app's
 * settings), then the microphone test.</li>
 * <li>{@link #TEST}: Test the microphone (the owner's wish of 21 November): 10 s of quiet for the room, then the sentence, then
 * the result with the evidence (a bar with both levels and the threshold), the sentence played back once from memory and
 * never stored. The threshold is saved at once ({@code Prefs.setSoundTest}); with no sound nothing changes.</li>
 * <li>{@link #LIST}: the marks of one night with Play, Keep and Delete sound, and "Delete all clips of this night".</li>
 * </ul>
 * Demo mode never records: the consent only shows, the test shows a made up result (nothing saved), the list is the demo's.
 */
public final class SoundsActivity extends Activity {
    static final String MODE = "mode", NIGHT = "night";
    static final String CONSENT = "consent", TEST = "test", LIST = "list";
    static final int REQUEST = 380;

    private LinearLayout body;
    private String mode;
    private boolean quietDenial;
    /** The running microphone test: stopped when the screen goes. */
    private volatile boolean testStop;
    private Thread testThread;
    private AudioTrack track;
    /** The test's result is on screen (kept when the screen comes back). */
    private boolean resultShown;
    /** The last answer's line on the microphone card, null before one. */
    private String micLine;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.vbox(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, Ui.dp(this, 12), p, p);
        scroll.addView(root);
        LinearLayout top = Ui.hbox(this);
        android.widget.ImageView back = new android.widget.ImageView(this);
        back.setImageResource(app.openring.R.drawable.ic_chevron_left);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_2));
        int bp = Ui.dp(this, 8);
        back.setPadding(bp, bp, bp, bp);
        back.setContentDescription("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        top.addView(Ui.h1(this, SleepSounds.TITLE));
        root.addView(top);
        root.addView(Ui.gap(this, 8));
        body = Ui.vbox(this);
        root.addView(body, Ui.matchWrap());
        setContentView(scroll);
        mode = getIntent().getStringExtra(MODE);
        if (mode == null) mode = CONSENT;
        show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // the microphone only while the screen is in front; a clip or the test sentence stops with it
        stopTest();
        SoundViews.stopPlaying();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // back from Android's app settings: the microphone may be allowed now; a test cut off by leaving starts again
        if (body != null && !(TEST.equals(mode) && resultShown)) show();
    }

    void show() {
        if (LIST.equals(mode)) list();
        else if (TEST.equals(mode)) testStart();
        else consent();
    }

    // ------------------------------------------------------------------------------------- consent and the microphone

    void consent() {
        body.removeAllViews();
        final Prefs p = Prefs.of(this);
        if (!p.demo() && p.soundConsent()) {
            // agreed before: only the microphone is missing (or it is allowed and the test is next)
            if (app.openring.sync.SoundService.allowed(this)) {
                mode = TEST;
                testStart();
                return;
            }
            micCard(null);
            return;
        }
        LinearLayout card = Ui.card(this);
        card.addView(Ui.caption(this, SleepSounds.CONSENT_TITLE));
        card.addView(Ui.gap(this, 6));
        card.addView(Ui.body(this, SleepSounds.CONSENT));
        card.addView(Ui.gap(this, 6));
        card.addView(Ui.text(this, SleepSounds.BATTERY, 12, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.text(this, Explain.SOUND_KINDS, 12, Ui.TEXT_3, Ui.REGULAR));
        TextView agree = Ui.button(this, SleepSounds.AGREE, Ui.SLEEP, true);
        agree.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (p.demo()) {
                    // demo mode never records and asks for nothing
                    Toast.makeText(SoundsActivity.this, SleepSounds.DEMO_NO_RECORD, Toast.LENGTH_LONG).show();
                    return;
                }
                p.setSoundConsent(true);
                app.openring.sync.SoundReminder.planLater(SoundsActivity.this);
                if (app.openring.sync.SoundService.allowed(SoundsActivity.this)) {
                    mode = TEST;
                    testStart();
                } else {
                    ask();
                }
            }
        });
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(this, 14), 0, 0);
        card.addView(agree, lp);
        TextView no = Ui.textButton(this, SleepSounds.NOT_NOW, Ui.TEXT_2);
        no.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        card.addView(no);
        body.addView(card);
    }

    void ask() {
        // the bedtime notification needs Notifications on Android 13 and newer: asked in the same dialog, never required
        boolean notify = android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED;
        requestPermissions(notify ? new String[] {android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS}
                : new String[] {android.Manifest.permission.RECORD_AUDIO}, REQUEST);
    }

    /** The microphone is not allowed: Allow (Android asks), or once Android no longer asks, the way to the app's settings. */
    void micCard(String line) {
        // the screen comes back after Android's dialog: the answer's line stays (Allow, or the way to the settings)
        if (line == null) line = micLine;
        else micLine = line;
        body.removeAllViews();
        LinearLayout card = Ui.card(this);
        card.addView(Ui.caption(this, "Microphone"));
        card.addView(Ui.gap(this, 6));
        final boolean blocked = SleepSounds.MIC_SETTINGS.equals(line);
        card.addView(Ui.body(this, line == null ? SleepSounds.MIC_OFF : line));
        TextView b = Ui.button(this, blocked ? PermissionAsk.SETTINGS_BUTTON : SleepSounds.MIC_ALLOW, Ui.SLEEP, true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (blocked) Permissions.openSettings(SoundsActivity.this);
                else ask();
            }
        });
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(this, 14), 0, 0);
        card.addView(b, lp);
        body.addView(card);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQUEST) return;
        Prefs p = Prefs.of(this);
        String[] needed = {android.Manifest.permission.RECORD_AUDIO};
        boolean granted = app.openring.sync.SoundService.allowed(this);
        boolean[] rationale = {!granted && shouldShowRequestPermissionRationale(needed[0])};
        // the rule of issue #140: a refusal after an earlier one, without explanation, means Android no longer asks
        PermissionAsk.Answer a = PermissionAsk.answer(granted, perms, results, needed, rationale, p.soundMicDenied(), quietDenial);
        p.setSoundMicDenied(a.denied);
        quietDenial = a.quiet;
        if (granted) {
            mode = TEST;
            testStart();
            return;
        }
        micCard(a.blocked ? SleepSounds.MIC_SETTINGS : SleepSounds.MIC_REFUSED);
    }

    // ------------------------------------------------------------------------------------- the microphone test

    /** Step 1: where the phone lies, then Start (10 s of quiet). */
    void testStart() {
        stopTest();
        resultShown = false;
        body.removeAllViews();
        final Prefs p = Prefs.of(this);
        if (p.demo()) {
            // demo mode never records: the result of a made up test, nothing saved
            showResult(-58, -36, true);
            return;
        }
        if (!p.soundConsent()) {
            mode = CONSENT;
            consent();
            return;
        }
        if (!app.openring.sync.SoundService.allowed(this)) {
            micCard(null);
            return;
        }
        LinearLayout card = Ui.card(this);
        card.addView(Ui.caption(this, SleepSounds.TEST));
        card.addView(Ui.gap(this, 6));
        card.addView(Ui.body(this, SleepSounds.TEST_ROOM));
        if (SoundNight.isRunning()) {
            card.addView(Ui.gap(this, 6));
            card.addView(Ui.text(this, "The phone is listening for the night now. Stop listening first to test.", 13, Ui.WARN, Ui.REGULAR));
            body.addView(card);
            return;
        }
        TextView start = Ui.button(this, SleepSounds.TEST_START, Ui.SLEEP, true);
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTest();
            }
        });
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(this, 14), 0, 0);
        card.addView(start, lp);
        body.addView(card);
    }

    /** The test's progress line, on the screen thread. */
    private void step(final String title, final String line, final boolean sentence) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                body.removeAllViews();
                LinearLayout card = Ui.card(SoundsActivity.this);
                card.addView(Ui.caption(SoundsActivity.this, SleepSounds.TEST));
                card.addView(Ui.gap(SoundsActivity.this, 6));
                card.addView(Ui.body(SoundsActivity.this, title));
                if (sentence) {
                    card.addView(Ui.gap(SoundsActivity.this, 10));
                    card.addView(Ui.text(SoundsActivity.this, SleepSounds.TEST_SENTENCE, 24, Ui.TEXT, Ui.LIGHT));
                }
                card.addView(Ui.gap(SoundsActivity.this, 10));
                card.addView(Ui.text(SoundsActivity.this, line, 14, Ui.SLEEP, Ui.MEDIUM));
                body.addView(card);
            }
        });
    }

    /** Reads {@code out} full from {@code rec}, a line each second; false when stopped or the microphone failed. */
    private boolean readAll(AudioRecord rec, short[] out, String title, String what, boolean sentence) {
        int at = 0, shown = -1;
        while (at < out.length) {
            if (testStop) return false;
            int left = (out.length - at) / SoundDetector.RATE + ((out.length - at) % SoundDetector.RATE > 0 ? 1 : 0);
            if (left != shown) {
                shown = left;
                step(title, what + " (" + left + " s)", sentence);
            }
            int n = rec.read(out, at, Math.min(SoundDetector.RATE / 10, out.length - at));
            if (n < 0) return false;
            at += n;
        }
        return true;
    }

    void runTest() {
        stopTest();
        testStop = false;
        final Context ctx = getApplicationContext();
        testThread = new Thread(new Runnable() {
            @Override
            public void run() {
                AudioRecord rec = app.openring.sync.SoundService.open(ctx);
                final short[] room = new short[SleepSounds.ROOM_S * SoundDetector.RATE];
                final short[] voice = new short[SleepSounds.VOICE_S * SoundDetector.RATE];
                boolean ok = false;
                try {
                    if (rec != null) {
                        rec.startRecording();
                        ok = readAll(rec, room, SleepSounds.TEST_ROOM, "Listening to the room, stay quiet", false)
                                && readAll(rec, voice, SleepSounds.TEST_SAY, "Listening", true);
                    }
                } catch (RuntimeException e) {
                    ok = false;
                } finally {
                    if (rec != null) {
                        try {
                            rec.stop();
                        } catch (RuntimeException ignored) {
                            // never started
                        }
                        rec.release();
                    }
                }
                if (testStop) return;
                final double rl = ok ? SleepSounds.roomLevel(SleepSounds.frameLevels(room, room.length)) : Double.NaN;
                final double vl = ok ? SleepSounds.voiceLevel(SleepSounds.frameLevels(voice, voice.length)) : Double.NaN;
                final boolean heard = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        double margin = SleepSounds.margin(rl, vl);
                        // saved at once; with no sound nothing changes
                        if (!Double.isNaN(margin)) Prefs.of(SoundsActivity.this).setSoundTest(System.currentTimeMillis() / 1000, margin);
                        showResult(rl, vl, false);
                        if (heard && !Double.isNaN(margin)) playOnce(voice);
                    }
                });
            }
        }, "sound-test");
        testThread.start();
    }

    void stopTest() {
        testStop = true;
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.stop();
            } catch (IllegalStateException ignored) {
                // not playing
            }
            t.release();
        }
    }

    /** The sentence played back once from memory; the samples are dropped after it and never stored. */
    void playOnce(short[] voice) {
        try {
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, SoundDetector.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    voice.length * 2, AudioTrack.MODE_STATIC);
            t.write(voice, 0, voice.length);
            t.play();
            track = t;
            body.postDelayed(new Runnable() {
                @Override
                public void run() {
                    stopTest();
                }
            }, SleepSounds.VOICE_S * 1000L + 500);
        } catch (RuntimeException e) {
            // the result stays; only the playback is missing
        }
    }

    /** Step 3: the result with the evidence: both levels on a bar, the margin, the verdict, the threshold now in use. */
    void showResult(double room, double voice, boolean demo) {
        resultShown = true;
        body.removeAllViews();
        double margin = SleepSounds.margin(room, voice);
        int verdict = SleepSounds.verdict(margin);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.caption(this, SleepSounds.TEST));
        card.addView(Ui.gap(this, 6));
        if (demo) card.addView(Ui.text(this, "Demo mode: a made up result, nothing was recorded or saved.", 12, Ui.ACTIVE, Ui.REGULAR));
        if (!Double.isNaN(margin)) {
            double th = demo ? SleepSounds.threshold(margin) : Prefs.of(this).soundThreshold();
            card.addView(new LevelBar(this, room, voice, th), Ui.sized(this, -1, 64));
            card.addView(Ui.gap(this, 6));
            card.addView(Ui.text(this, SleepSounds.marginLine(margin), 18, Ui.TEXT, Ui.REGULAR));
            card.addView(Ui.text(this, SleepSounds.MARGIN_NOTE, 11, Ui.TEXT_3, Ui.REGULAR));
            card.addView(Ui.gap(this, 6));
            card.addView(Ui.body(this, SleepSounds.verdictText(verdict)));
            card.addView(Ui.gap(this, 6));
            card.addView(Ui.text(this, SleepSounds.thresholdLine(th), 14, Ui.TEXT_2, Ui.REGULAR));
            card.addView(Ui.text(this, SleepSounds.THRESHOLD_NOTE + " Half the difference, kept between 6 and 15 dB.", 11, Ui.TEXT_3, Ui.REGULAR));
            if (!demo) card.addView(Ui.text(this, SleepSounds.TEST_PLAYED, 12, Ui.TEXT_3, Ui.REGULAR));
        } else {
            card.addView(Ui.body(this, SleepSounds.verdictText(verdict)));
            card.addView(Ui.text(this, "Nothing was changed.", 12, Ui.TEXT_3, Ui.REGULAR));
        }
        if (!demo && !app.openring.sync.SoundService.unprocessed(this)) {
            card.addView(Ui.gap(this, 6));
            card.addView(Ui.text(this, SleepSounds.TEST_PROCESSED, 12, Ui.TEXT_3, Ui.REGULAR));
        }
        LinearLayout row = Ui.hbox(this);
        TextView again = Ui.smallButton(this, SleepSounds.TEST_AGAIN, Ui.SLEEP), done = Ui.smallButton(this, SleepSounds.TEST_DONE, Ui.TEXT_2);
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testStart();
            }
        });
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        la.setMargins(0, 0, Ui.dp(this, 4), 0);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lb.setMargins(Ui.dp(this, 4), 0, 0, 0);
        row.addView(again, la);
        row.addView(done, lb);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(this, 12), 0, 0);
        card.addView(row, lp);
        body.addView(card);
    }

    /** The evidence of the test: the room and the voice on one scale (-90 to -10 dB of full scale), the threshold between. */
    static final class LevelBar extends View {
        final double room, voice, threshold;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LevelBar(Context c, double room, double voice, double threshold) {
            super(c);
            this.room = room;
            this.voice = voice;
            this.threshold = threshold;
            setContentDescription("Room and your voice on a level bar: your voice " + Math.round(voice - room) + " dB above the room");
        }

        float x(double db) {
            double f = (Math.max(-90, Math.min(-10, db)) + 90) / 80.0;
            return (float) (getPaddingLeft() + f * (getWidth() - getPaddingLeft() - getPaddingRight()));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float y = 20 * d, h = 10 * d;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.LINE);
            cv.drawRoundRect(x(-90), y, x(-10), y + h, h / 2, h / 2, paint);
            paint.setColor(Ui.alpha(Ui.SLEEP, 0.5f));
            cv.drawRoundRect(x(-90), y, x(voice), y + h, h / 2, h / 2, paint);
            paint.setColor(Ui.TEXT_3);
            cv.drawRoundRect(x(-90), y, x(room), y + h, h / 2, h / 2, paint);
            paint.setColor(Ui.ACTIVE);
            float t = x(room + threshold);
            cv.drawRect(t - d, y - 4 * d, t + d, y + h + 4 * d, paint);
            paint.setTextSize(11 * d);
            paint.setColor(Ui.TEXT_3);
            paint.setTextAlign(Paint.Align.LEFT);
            cv.drawText("Room", Math.max(0, x(room) - 30 * d), y - 6 * d, paint);
            paint.setColor(Ui.SLEEP);
            paint.setTextAlign(Paint.Align.RIGHT);
            cv.drawText("Your voice", Math.min(getWidth(), x(voice) + 30 * d), y + h + 18 * d, paint);
        }
    }

    // ------------------------------------------------------------------------------------- the clip list

    void list() {
        body.removeAllViews();
        body.addView(Ui.text(this, "Reading the night", 14, Ui.TEXT_2, Ui.REGULAR));
        final double asked = getIntent().getDoubleExtra(NIGHT, Double.NaN);
        final Context ctx = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Sounds.Night n = null;
                File folder = null;
                boolean ok = true;
                try {
                    Db db = Db.get(ctx);
                    folder = Sounds.folder(ctx, db);
                    // the retention and the sweep before the list (never the sweep while a night listens, Sounds.tidy)
                    if (!Db.DEMO.equals(db.fileName)) {
                        try {
                            SoundNight.tidyReal(ctx, Prefs.of(ctx).soundKeepDays());
                        } catch (java.io.IOException e) {
                            // the list shows what is stored; the next tidy tries again
                        }
                    }
                    double start = asked;
                    if (Double.isNaN(start)) {
                        Sounds.Night newest = Sounds.forDay(db, java.time.LocalDate.now(), ZoneId.systemDefault());
                        start = newest == null ? Double.NaN : newest.start;
                    }
                    n = Double.isNaN(start) ? null : Sounds.night(db, start);
                } catch (RuntimeException e) {
                    ok = false;
                }
                final Sounds.Night night = n;
                final File f = folder;
                final boolean read = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing() && !isDestroyed()) showList(night, f, read);
                    }
                });
            }
        }, "sound-list").start();
    }

    void showList(final Sounds.Night n, final File folder, boolean read) {
        body.removeAllViews();
        if (!read) {
            body.addView(Ui.body(this, "Sleep sounds could not be read. Nothing was changed."));
            return;
        }
        if (n == null) {
            body.addView(Ui.body(this, SleepSounds.NOTHING));
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        LinearLayout card = Ui.card(this);
        java.time.LocalDate day = SleepSounds.nightDay(n.start, zone);
        card.addView(Ui.caption(this, "Night before " + day.getDayOfMonth() + " " + day.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)));
        card.addView(Ui.gap(this, 6));
        card.addView(Ui.body(this, SleepSounds.summary(n.marks())));
        if (!n.clips.isEmpty()) card.addView(Ui.text(this, SleepSounds.COUNTS_NOTE, 11, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.text(this, SleepSounds.keepLine(Prefs.of(this).soundKeepDays()) + " " + SleepSounds.KEPT_NOTE, 11, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.gap(this, 8));
        for (final Sounds.Clip k : n.clips) {
            card.addView(Ui.divider(this));
            card.addView(SoundViews.row(this, k, folder, zone));
            if (!k.sound()) continue;
            LinearLayout actions = Ui.hbox(this);
            TextView keep = Ui.smallButton(this, k.kept ? SleepSounds.KEPT : SleepSounds.KEEP, k.kept ? Ui.GOOD : Ui.TEXT_2);
            keep.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    write(new Runnable() {
                        @Override
                        public void run() {
                            Sounds.keep(Db.get(getApplicationContext()), k.start, !k.kept);
                        }
                    });
                }
            });
            TextView del = Ui.smallButton(this, SleepSounds.DELETE_SOUND, Ui.WARN);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    write(new Runnable() {
                        @Override
                        public void run() {
                            Sounds.deleteSound(Db.get(getApplicationContext()), folder, k.start);
                        }
                    });
                }
            });
            LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            la.setMargins(0, 0, Ui.dp(this, 4), Ui.dp(this, 6));
            LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lb.setMargins(Ui.dp(this, 4), 0, 0, Ui.dp(this, 6));
            actions.addView(keep, la);
            actions.addView(del, lb);
            card.addView(actions);
        }
        TextView all = Ui.smallButton(this, SleepSounds.DELETE_NIGHT, Ui.WARN);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SoundsActivity.this).setTitle(SleepSounds.DELETE_NIGHT).setMessage(SleepSounds.DELETE_NIGHT_ASK)
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                SoundViews.stopPlaying();
                                write(new Runnable() {
                                    @Override
                                    public void run() {
                                        SoundNight.deleteNight(getApplicationContext(), Db.get(getApplicationContext()), n.start);
                                    }
                                });
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(this, 14), 0, 0);
        card.addView(all, lp);
        body.addView(card);
        body.addView(Ui.text(this, Explain.SOUND_KINDS, 12, Ui.TEXT_3, Ui.REGULAR));
    }

    /** A change of the list off the screen thread, then the list again; a failure says so and changes nothing more. */
    void write(final Runnable r) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = true;
                try {
                    r.run();
                } catch (RuntimeException e) {
                    ok = false;
                }
                final boolean done = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (!done) Toast.makeText(SoundsActivity.this, "Not done. Nothing was changed.", Toast.LENGTH_LONG).show();
                        list();
                    }
                });
            }
        }, "sound-change").start();
    }
}
