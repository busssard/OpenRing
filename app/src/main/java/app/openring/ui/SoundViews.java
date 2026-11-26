package app.openring.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import app.openring.core.SleepSounds;
import app.openring.core.SoundMarks;
import app.openring.core.analysis.TimelineRow;
import app.openring.data.Prefs;
import app.openring.data.SoundNight;
import app.openring.data.Sounds;

/**
 * Sleep sounds on the Sleep tab (issue #380, phase 1b): the card with the night's marks and their clips (Play), Listen tonight
 * and Stop listening, the way to set it up (consent, the microphone, its test), and one player for every clip of the app.
 * Phase 1c: the Sounds strip under the night's stages ({@link #strip}) and the rows of the night timeline ({@link #timelineRows}).
 */
final class SoundViews {
    private SoundViews() {}

    /** Rows the card shows before "All clips of this night". */
    static final int CARD_ROWS = 6;

    /**
     * Starts listening from a screen in front (the card's Listen tonight, the bedtime notification's Start): demo mode says it
     * never records; without the consent or the microphone the sleep sounds screen asks for them first.
     */
    static void listen(Activity a) {
        Prefs p = Prefs.of(a);
        if (p.demo()) {
            Toast.makeText(a, SleepSounds.DEMO_NO_RECORD, Toast.LENGTH_LONG).show();
            return;
        }
        if (SoundNight.isRunning()) return;
        if (!p.soundConsent() || !app.openring.sync.SoundService.allowed(a)) {
            a.startActivity(new Intent(a, SoundsActivity.class).putExtra(SoundsActivity.MODE, SoundsActivity.CONSENT));
            return;
        }
        if (app.openring.sync.SoundService.start(a)) {
            app.openring.sync.SoundReminder.clear(a);
            Toast.makeText(a, SleepSounds.STARTED, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(a, SleepSounds.NOT_STARTED, Toast.LENGTH_LONG).show();
        }
    }

    /** The Sleep sounds card of {@code d}'s Sleep tab: always there (a working feature is standard, not hidden). */
    static View card(final Context c, final DayData d, final Pages.Nav nav) {
        Prefs p = Prefs.of(c);
        final boolean demo = p.demo();
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, SleepSounds.TITLE));
        card.addView(Ui.gap(c, 6));
        if (!d.soundsOk) {
            card.addView(Ui.body(c, "Sleep sounds could not be read. Nothing was changed."));
            return card;
        }
        boolean today = d.date != null && d.date.equals(LocalDate.now());
        final Sounds.Night n = d.soundNight;
        boolean running = !demo && SoundNight.isRunning();
        double runStart = SoundNight.runningStart();
        if (running && (today || (n != null && n.start == runStart))) {
            card.addView(Ui.body(c, SleepSounds.listeningLine(runStart, SoundNight.runningSounds(), d.zone)));
            Tiles.summary(card, "Listening now");
            TextView stop = button(c, card, SleepSounds.STOP, Ui.WARN);
            stop.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    app.openring.sync.SoundService.stop(c);
                    v.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (nav != null) nav.reload();
                        }
                    }, 1500);
                }
            });
            notes(c, card);
            return card;
        }
        if (n != null) {
            card.addView(Ui.body(c, SleepSounds.summary(n.marks())));
            Tiles.summary(card, Ui.fmt(n.clips.size(), 0) + (n.clips.size() == 1 ? " sound" : " sounds"));
            if (!n.clips.isEmpty()) card.addView(Ui.text(c, SleepSounds.COUNTS_NOTE, 11, Ui.TEXT_3, Ui.REGULAR));
            // in the zone of the night, as the stages and the Sounds strip show it (phase 1c)
            java.time.ZoneId nz = zone(d);
            String ended = SleepSounds.endedLine(n.ended, n.stop, nz), gap = SleepSounds.gapLine(n.silent);
            if (ended != null) card.addView(Ui.text(c, ended, 12, Ui.WARN, Ui.REGULAR));
            if (gap != null) card.addView(Ui.text(c, gap, 12, Ui.WARN, Ui.REGULAR));
            card.addView(Ui.gap(c, 6));
            for (int i = 0; i < n.clips.size() && i < CARD_ROWS; i++) card.addView(row(c, n.clips.get(i), d.soundFolder, nz));
            TextView all = button(c, card, n.clips.size() > CARD_ROWS ? SleepSounds.ALL_CLIPS + " (" + n.clips.size() + ")" : SleepSounds.ALL_CLIPS, Ui.SLEEP);
            all.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    c.startActivity(new Intent(c, SoundsActivity.class).putExtra(SoundsActivity.MODE, SoundsActivity.LIST).putExtra(SoundsActivity.NIGHT, n.start));
                }
            });
        } else if (!demo && !p.soundConsent()) {
            card.addView(Ui.body(c, SleepSounds.ABOUT));
            TextView set = button(c, card, SleepSounds.SET_UP, Ui.SLEEP);
            set.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    c.startActivity(new Intent(c, SoundsActivity.class).putExtra(SoundsActivity.MODE, SoundsActivity.CONSENT));
                }
            });
            notes(c, card);
            return card;
        } else if (!demo && !app.openring.sync.SoundService.allowed(c)) {
            card.addView(Ui.body(c, SleepSounds.MIC_OFF));
            TextView allow = button(c, card, SleepSounds.MIC_ALLOW, Ui.SLEEP);
            allow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    c.startActivity(new Intent(c, SoundsActivity.class).putExtra(SoundsActivity.MODE, SoundsActivity.CONSENT));
                }
            });
            notes(c, card);
            return card;
        } else {
            card.addView(Ui.body(c, SleepSounds.NOTHING));
            Tiles.summary(card, "");
        }
        if (today) {
            TextView listen = button(c, card, SleepSounds.LISTEN, Ui.SLEEP);
            listen.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (c instanceof Activity) listen((Activity) c);
                    // the service needs a moment before the card can say it listens (as Stop listening)
                    v.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (nav != null) nav.reload();
                        }
                    }, 1500);
                }
            });
        }
        TextView test = Ui.textButton(c, SleepSounds.TEST, Ui.TEXT_2);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                c.startActivity(new Intent(c, SoundsActivity.class).putExtra(SoundsActivity.MODE, SoundsActivity.TEST));
            }
        });
        card.addView(test);
        notes(c, card);
        return card;
    }

    // ------------------------------------------------------------------------------------- phase 1c: the night's timeline

    /**
     * The zone the Sleep tab shows {@code d}'s night in, the stages' zone: the ring's night's own when that night is shown,
     * else the day's (the watch's night, a night by hand, no night). Marks are shown in it (phase 1c).
     */
    static java.time.ZoneId zone(DayData d) {
        boolean watchShown = d.merged != null && d.merged.watchNight != null;
        return !watchShown && d.night != null ? d.zoneOf(d.night) : d.zone;
    }

    /**
     * The Sounds strip under the stages in {@code parent} (the card "Sleep depth through the night"), on the stages' axis from
     * {@code from} to {@code to} in {@code zone}: the strip, the rows with Play of the marks a touch reads, the note and how
     * many marks lie outside the window. Nothing when the day has no night listened.
     */
    static void strip(final Context c, final DayData d, LinearLayout parent, double from, double to, final java.time.ZoneId zone) {
        final Sounds.Night n = d.soundsOk ? d.soundNight : null;
        if (n == null || !(to > from)) return;
        final List<SleepSounds.Mark> marks = new ArrayList<SleepSounds.Mark>();
        for (Sounds.Clip k : n.clips) marks.add(k.mark());
        final LinearLayout picked = Ui.vbox(c);
        Charts.SoundStrip strip = new Charts.SoundStrip(c, marks, n.gaps, from, to, n.start, n.stop, n.ended).zone(zone)
                .touchSnore(markPending && Prefs.of(c).demo())
                .onPick(new Charts.SoundStrip.OnPick() {
                    @Override
                    public void picked(List<SleepSounds.Mark> read) {
                        picked.removeAllViews();
                        // each mark read, with Play while its sound is there (the clip with the same mark)
                        for (SleepSounds.Mark m : read) {
                            int i = marks.indexOf(m);
                            if (i >= 0) picked.addView(row(c, n.clips.get(i), d.soundFolder, zone));
                        }
                    }
                });
        parent.addView(Ui.gap(c, 6));
        // 24 dp of marks and room for a readout of two lines above them; a longer one makes it taller (review of #482)
        parent.addView(strip, Ui.sized(c, -1, 62));
        parent.addView(picked);
        parent.addView(Ui.text(c, SoundMarks.STRIP_NOTE, 11, Ui.TEXT_3, Ui.REGULAR));
        String outside = SoundMarks.outsideLine(SoundMarks.before(marks, from), SoundMarks.after(marks, to));
        if (outside != null) parent.addView(Ui.text(c, outside, 11, Ui.TEXT_3, Ui.REGULAR));
    }

    /** The screen test's touch (extra sounds_mark, demo mode only): the Sounds strip opens touched on the demo's snoring. */
    static boolean markPending;

    /** The night timeline's rows of {@code d}'s night listened, in {@code zone}; null when there is none. */
    static List<TimelineRow> timelineRows(DayData d, java.time.ZoneId zone) {
        Sounds.Night n = d.soundsOk ? d.soundNight : null;
        if (n == null) return null;
        return SoundMarks.rows(n.marks(), n.gaps, n.start, n.stop, n.ended, zone);
    }

    /** Under the night timeline {@code card}: how many short times not listening the strip shades. */
    static void timelineNote(Context c, LinearLayout card, DayData d) {
        Sounds.Night n = d.soundsOk ? d.soundNight : null;
        String line = n == null ? null : SoundMarks.shortGapsLine(SoundMarks.shortGaps(n.gaps));
        if (line == null) return;
        card.addView(Ui.gap(c, 4));
        card.addView(Ui.text(c, line, 11, Ui.TEXT_3, Ui.REGULAR));
    }

    /** The battery note and the no diagnosis sentence under the card. */
    static void notes(Context c, LinearLayout card) {
        card.addView(Ui.gap(c, 8));
        card.addView(Ui.text(c, SleepSounds.BATTERY, 11, Ui.TEXT_3, Ui.REGULAR));
        card.addView(Ui.text(c, SleepSounds.NO_DIAGNOSIS, 11, Ui.TEXT_3, Ui.REGULAR));
    }

    static TextView button(Context c, LinearLayout parent, String label, int color) {
        TextView b = Ui.smallButton(c, label, color);
        LinearLayout.LayoutParams lp = Ui.matchWrap();
        lp.setMargins(0, Ui.dp(c, 8), 0, 0);
        parent.addView(b, lp);
        return b;
    }

    /** One mark: its time, kind and length, and Play while its sound is there. */
    static View row(final Context c, final Sounds.Clip k, final File folder, java.time.ZoneId zone) {
        LinearLayout r = Ui.hbox(c);
        r.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        r.addView(Ui.text(c, SleepSounds.row(k.mark(), zone), 13, k.sound() ? Ui.TEXT : Ui.TEXT_3, Ui.REGULAR), Ui.weight(1));
        if (k.sound() && folder != null) {
            final TextView play = Ui.smallButton(c, SleepSounds.PLAY, Ui.SLEEP);
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    play(c, new File(folder, k.file), play);
                }
            });
            r.addView(play);
        }
        return r;
    }

    // ------------------------------------------------------------------------------------- one player for every clip

    private static MediaPlayer player;
    private static TextView playing;

    /** Plays {@code f}, its button saying Playing until it ends; a tap on the playing button stops it. */
    static void play(Context c, File f, TextView button) {
        boolean same = playing == button;
        stopPlaying();
        if (same) return;
        if (!f.exists()) {
            Toast.makeText(c, "This sound is no longer on the phone.", Toast.LENGTH_LONG).show();
            return;
        }
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(f.getPath());
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    stopPlaying();
                }
            });
            mp.prepare();
            mp.start();
        } catch (java.io.IOException | RuntimeException e) {
            mp.release();
            Toast.makeText(c, "This clip could not be played.", Toast.LENGTH_LONG).show();
            return;
        }
        player = mp;
        playing = button;
        button.setText(SleepSounds.PLAYING);
    }

    /** Stops the clip playing, if any (also when its screen goes). */
    static void stopPlaying() {
        MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try {
                mp.stop();
            } catch (IllegalStateException ignored) {
                // it had ended
            }
            mp.release();
        }
        TextView b = playing;
        playing = null;
        if (b != null) b.setText(SleepSounds.PLAY);
    }
}
