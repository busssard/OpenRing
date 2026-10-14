package app.openring.data;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Exports: one CSV row per day, or a copy of the whole SQLite database. */
public final class Exporter {
    private Exporter() {}

    private static final String[] COLS = {"date", "sleep_score", "asleep_min", "rhr", "hrv", "temp", "temp_dev", "breath", "spo2", "steps",
        "active_kcal", "total_kcal", "distance_m", "low_min", "medium_min", "high_min", "inactive_min", "target_kcal", "vo2max"};

    public static void dailyCsv(Context c, OutputStream os) throws IOException {
        Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        for (String col : COLS) head.append(col).append(',');
        head.append("readiness_score,activity_score\n");
        w.write(head.toString());
        List<String[]> days = Db.get(c).daysJson("0000-00-00", "9999-99-99");
        for (String[] d : days) {
            JSONObject o = Json.parse(d[1]);
            if (o == null) continue;
            StringBuilder row = new StringBuilder();
            for (String col : COLS) {
                Object v = o.opt(col);
                row.append(v == null ? "" : String.valueOf(v)).append(',');
            }
            JSONObject r = o.optJSONObject("readiness"), a = o.optJSONObject("activity_score");
            row.append(r == null ? "" : String.valueOf(r.optInt("value"))).append(',');
            row.append(a == null ? "" : String.valueOf(a.optInt("value"))).append('\n');
            w.write(row.toString());
        }
        w.flush();
    }

    public static void database(Context c, OutputStream os) throws IOException {
        Db db = Db.get(c);
        Cursor cur = db.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(FULL)", null);
        cur.moveToFirst();
        cur.close();
        File f = c.getDatabasePath(db.fileName);
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            in.close();
        }
        os.flush();
    }
}
