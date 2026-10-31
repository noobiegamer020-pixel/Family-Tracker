package com.husbandwife.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.json.JSONObject;

/**
 * Runs in the background about every 15 minutes, even when the app is closed.
 * It reads the shared couple document from Firestore and notifies about what the partner added.
 */
public class SyncWorker extends Worker {

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            run();
        } catch (Exception e) {
            // ignore, try again next time
        }
        return Result.success();
    }

    private String who(String c, boolean fr) {
        if ("h".equals(c)) return fr ? "Mari" : "Husband";
        return fr ? "Épouse" : "Wife";
    }

    private String titleOf(JSONObject o, boolean fr) {
        Object n = o.opt("n");
        if (n instanceof JSONObject) {
            JSONObject j = (JSONObject) n;
            return j.optString(fr ? "fr" : "en", j.optString("en", ""));
        }
        return n == null ? "" : String.valueOf(n);
    }

    private void run() throws Exception {
        Context ctx = getApplicationContext();
        SharedPreferences cap = ctx.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
        String cs = cap.getString("hwcfg", null);
        if (cs == null) return;
        JSONObject cfg = new JSONObject(cs);
        String code = cfg.optString("code");
        String pid = cfg.optString("pid");
        String key = cfg.optString("key");
        String me = cfg.optString("who");
        boolean fr = "fr".equals(cfg.optString("lang", "en"));
        if (code.isEmpty() || pid.isEmpty()) return;

        URL url = new URL("https://firestore.googleapis.com/v1/projects/" + pid
                + "/databases/(default)/documents/couples/" + code + "?key=" + key);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        if (c.getResponseCode() != 200) return;
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        String ln;
        while ((ln = br.readLine()) != null) sb.append(ln);
        br.close();

        JSONObject fields = new JSONObject(sb.toString()).optJSONObject("fields");
        if (fields == null) return;

        SharedPreferences bg = ctx.getSharedPreferences("hwbg", Context.MODE_PRIVATE);
        boolean base = !code.equals(bg.getString("code", ""));
        JSONObject seen = new JSONObject(cap.getString("hwseen", "{}"));
        long alive = 0;
        try {
            alive = Long.parseLong(cap.getString("hwalive", "0"));
        } catch (Exception e) {
            alive = 0;
        }
        boolean appOpen = System.currentTimeMillis() - alive < 70000;

        SharedPreferences.Editor ed = bg.edit();
        ed.putString("code", code);
        List<String> msgs = new ArrayList<>();

        Iterator<String> it = fields.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!(k.startsWith("g_") || k.startsWith("w_") || k.startsWith("v_"))) continue;
            JSONObject mv = fields.getJSONObject(k).optJSONObject("mapValue");
            if (mv == null) continue;
            JSONObject f = mv.optJSONObject("fields");
            if (f == null || f.has("del")) continue;
            JSONObject dj = f.optJSONObject("d");
            JSONObject uj = f.optJSONObject("u");
            if (dj == null || uj == null) continue;

            String u = uj.optString("integerValue", uj.optString("doubleValue", ""));
            String prev = bg.getString("u:" + k, null);
            String sj = seen.optString(k, "");
            JSONObject o = new JSONObject(dj.getString("stringValue"));
            int nx = o.optInt("nx", -1);

            if (!base && !u.equals(prev) && !u.equals(sj)) {
                String by = o.optString("lastBy", "");
                if (by.isEmpty()) by = o.optString("by", "");
                if (by.isEmpty()) by = o.optString("who", "");
                if (("h".equals(by) || "w".equals(by)) && !by.equals(me)) {
                    String w = who(by, fr);
                    boolean isNew = (prev == null && sj.isEmpty());
                    String title = titleOf(o, fr);
                    if (k.startsWith("g_")) {
                        if (o.optJSONArray("items") != null) {
                            int count = o.optJSONArray("items").length();
                            int before = bg.getInt("nx:" + k, -1);
                            if (isNew) {
                                msgs.add(fr ? "🛒 " + w + " a envoyé une liste de courses (" + count + " articles)"
                                        : "🛒 " + w + " sent a shopping list (" + count + " items)");
                            } else if (before >= 0 && nx > before) {
                                msgs.add(fr ? "➕ " + w + " a ajouté " + (nx - before) + " article(s) : " + o.optString("lx", "")
                                        : "➕ " + w + " added " + (nx - before) + " item(s): " + o.optString("lx", ""));
                            }
                        } else if (isNew) {
                            msgs.add(fr ? "✅ " + w + " a ajouté une tâche : " + title
                                    : "✅ " + w + " added a task: " + title);
                        }
                    } else if (k.startsWith("w_") && isNew) {
                        msgs.add(fr ? "💝 " + w + " a ajouté un souhait : " + title
                                : "💝 " + w + " added a wish: " + title);
                    } else if (k.startsWith("v_") && isNew) {
                        msgs.add(fr ? "🎯 " + w + " a ajouté un rêve : " + title
                                : "🎯 " + w + " added a dream: " + title);
                    }
                }
            }
            ed.putString("u:" + k, u);
            if (nx >= 0) ed.putInt("nx:" + k, nx);
        }
        ed.apply();

        if (!msgs.isEmpty() && !appOpen) {
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < msgs.size(); i++) {
                if (i > 0) body.append("\n");
                body.append(msgs.get(i));
            }
            notifyUser(ctx, body.toString());
        }
    }

    private void notifyUser(Context ctx, String body) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("couple", "Couple updates", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Updates from your partner and reminders");
            ch.enableVibration(true);
            try {
                Uri snd = Uri.parse("android.resource://" + ctx.getPackageName() + "/raw/soft");
                AudioAttributes aa = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                ch.setSound(snd, aa);
            } catch (Exception e) {
                // default sound
            }
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String first = body.split("\n")[0];
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, "couple")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("💞 Husband ♥ Wife")
                .setContentText(first)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify((int) (System.currentTimeMillis() % 2000000000L), b.build());
    }
}
