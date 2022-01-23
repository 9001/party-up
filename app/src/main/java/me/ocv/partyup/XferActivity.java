package me.ocv.partyup;

import static java.lang.String.format;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import androidx.appcompat.app.AppCompatActivity;

import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.preference.PreferenceManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;

import me.ocv.partyup.databinding.ActivityXferBinding;

class F {
    public Uri handle;
    public String name;
    public long size;
    public String full_url;
    public String share_url;
    public String desc;
}

public class XferActivity extends AppCompatActivity {
    ActivityXferBinding binding;
    SharedPreferences prefs;
    Intent the_intent;
    String password;
    String base_url;
    boolean upping;
    String the_msg;
    long bytes_done, bytes_total;
    F[] files;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        upping = false;

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        binding = ActivityXferBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        the_intent = getIntent();
        String etype = the_intent.getType();
        String action = the_intent.getAction();
        boolean one = Intent.ACTION_SEND.equals(action);
        boolean many = Intent.ACTION_SEND_MULTIPLE.equals(action);
        if (etype == null || (!one && !many)) {
            show_msg("cannot share content;\naction: " + action + "\ntype: " + etype);
            return;
        }

        Uri[] handles = null;
        if (many) {
            ArrayList<Uri> x = the_intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            handles = x.toArray(new Uri[0]);
        }
        else if (one) {
            Uri uri = (Uri) the_intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null)
                handles = new Uri[]{ uri };
            else
                the_msg = the_intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (handles != null) {
            files = new F[handles.length];
            for (int a = 0; a < handles.length; a++) {
                F f = new F();
                f.handle = handles[a];
                f.name = null;
                f.size = -1;
                files[a] = f;
            }
            handleSendImage();
        } else if (the_msg != null) {
            handleSendText();
        } else {
            show_msg("cannot decide on what to send for " + the_intent.getType());
            return;
        }

        final FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(v -> {
            fab.setVisibility(View.GONE);
            do_up();
        });
    }

    private void show_msg(String txt) {
        ((TextView)findViewById(R.id.upper_info)).setText(txt);
    }

    private void tshow_msg(String txt) {
        final TextView tv = (TextView)findViewById(R.id.upper_info);
        tv.post(() -> tv.setText(txt));
    }

    String getext(String mime) {
        if (mime == null)
            return "bin";

        mime = mime.replace(';', ' ').split(" ")[0];

        switch (mime) {
            case "audio/ogg": return "ogg";
            case "audio/mpeg": return "mp3";
            case "audio/mp4": return "m4a";
            case "image/jpeg": return "jpg";
        }

        if (mime.startsWith("text/"))
            return "txt";

        if (mime.contains("/")) {
            mime = mime.split("/")[1];
            if (mime.matches("^[a-zA-Z0-9]{1,8}$"))
                return mime;
        }

        return "bin";
    }

    private void handleSendText() {
        show_msg("Post the following link?\n\n" + the_msg);
        if (prefs.getBoolean("autosend", false))
            do_up();
    }

    @SuppressLint("DefaultLocale")
    private void handleSendImage() {
        for (F f : files) {
            // contentresolver returns the wrong filesize (off by 626 bytes)
            // but we want the name so lets go
            try {
                Cursor cur = getContentResolver().query(f.handle, null, null, null, null);
                assert cur != null;
                int iname = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int isize = cur.getColumnIndex(OpenableColumns.SIZE);
                cur.moveToFirst();
                f.name = cur.getString(iname);
                f.size = cur.getLong(isize);
                cur.close();
            }
            catch (Exception ex) {
                Log.w("me.ocv.partyup", "contentresolver: " + ex.toString());
            }

            if (f.name == null)
                f.name = "mystery-file." + getext(the_intent.getType());

            // get correct filesize
            try {
                InputStream ins = getContentResolver().openInputStream(f.handle);
                assert ins != null;
                byte[] buf = new byte[128 * 1024];
                long sz = 0;
                while (true) {
                    int n = ins.read(buf);
                    if (n <= 0)
                        break;

                    sz += n;
                }
                f.size = sz;
            } catch (Exception ex) {
                show_msg("Error3: " + ex.toString());
                return;
            }

            f.desc = format("%s\n\nsize: %,d byte\ntype: %s", f.name, f.size, the_intent.getType());
        }

        String msg;
        if (files.length == 1) {
            msg = "Upload the following file?\n\n" + files[0].desc;
        }
        else {
            bytes_done = bytes_total = 0;
            msg = "Upload the following " + files.length + " files?\n\n";
            for (int a = 0; a < Math.min(10, files.length); a++) {
                msg += files[a].name + "\n";
                bytes_total += files[a].size;
            }

            if (files.length > 10)
                msg += "[...]\n";

            msg += format("--- total %,d bytes ---", bytes_total);
        }
        show_msg(msg);
        if (prefs.getBoolean("autosend", false))
            do_up();
    }

    private void do_up() {
        if (upping)
            return;

        upping = true;
        new Thread(this::do_up2).start();
    }

    private void do_up2() {
        try {
            base_url = prefs.getString("server_url", "");
            if (base_url == null)
                throw new Exception("server_url config is invalid");

            if (!base_url.startsWith("http"))
                base_url = "http://" + base_url;

            if (!base_url.endsWith("/"))
                base_url += "/";

            password = prefs.getString("server_password", "");
            password = password == null ? "" : password;
            if (password.equals("Default value"))
                password = "";  // necessary in the emulator, not on real devices(?)

            tshow_msg("Sending to " + base_url + " ...");

            int nfiles = files == null ? 1 : files.length;
            for (int a = 0; a < nfiles; a++) {
                String full_url = base_url;
                if (files != null) {
                    F f = files[a];
                    full_url += URLEncoder.encode(f.name, "UTF-8");
                    tshow_msg("Sending to " + base_url + " ...\n\n" + f.desc);
                    f.full_url = full_url;
                }

                URL url = new URL(full_url);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                if (!password.isEmpty())
                    conn.setRequestProperty("Authorization", "Basic " + new String(Base64.getEncoder().encode(password.getBytes())));

                if (files == null)
                    do_textmsg(conn);
                else
                    if (!do_fileput(conn, a))
                        return;
            }
            findViewById(R.id.upper_info).post(() -> onsuccess());
        }
        catch (Exception ex) {
            tshow_msg("Error2: " + ex.toString() + "\n\nmaybe wrong password?");
        }
    }

    String read_err(HttpURLConnection conn) {
        try {
            byte[] buf = new byte[1024];
            int n = Math.max(0, conn.getErrorStream().read(buf));
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return ex.toString();
        }
    }

    private void do_textmsg(HttpURLConnection conn) throws Exception {
        byte[] body = ("msg=" + URLEncoder.encode(the_msg, "UTF-8")).getBytes(StandardCharsets.UTF_8);
        conn.setRequestMethod("POST");
        conn.setFixedLengthStreamingMode(body.length);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        conn.connect();
        OutputStream os = conn.getOutputStream();
        os.write(body);
        os.flush();
        int rc = conn.getResponseCode();
        if (rc >= 300) {
            tshow_msg("Server error " + rc + ":\n" + read_err(conn));
            conn.disconnect();
            return;
        }
        conn.disconnect();
    }

    @SuppressLint("DefaultLocale")
    private boolean do_fileput(HttpURLConnection conn, int nfile) throws Exception {
        F f = files[nfile];
        conn.setRequestMethod("PUT");
        conn.setFixedLengthStreamingMode(f.size);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.connect();
        final TextView tv = (TextView) findViewById(R.id.upper_info);
        OutputStream os = conn.getOutputStream();
        InputStream ins = getContentResolver().openInputStream(f.handle);
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        byte[] buf = new byte[128 * 1024];
        assert ins != null;
        while (true) {
            int n = ins.read(buf);
            if (n <= 0)
                break;

            bytes_done += n;
            os.write(buf, 0, n);
            md.update(buf, 0, n);

            tv.post(() -> {
                double perc = ((double) bytes_done * 100) / bytes_total;
                tv.setText(format("Sending %d of %d to %s ...\n\n%s\n\nbytes done:  %,d\nbytes left:  %,d\nprogress:  %.2f %%",
                        nfile + 1,
                        files.length,
                        base_url,
                        f.desc,
                        bytes_done,
                        bytes_total - bytes_done,
                        perc
                ));
                ((ProgressBar) findViewById(R.id.progbar)).setProgress((int) Math.round(perc));
            });
        }
        os.flush();
        int rc = conn.getResponseCode();
        if (rc >= 300) {
            tshow_msg("Server error " + rc + ":\n" + read_err(conn));
            conn.disconnect();
            return false;
        }
        String sha = "";
        byte[] bsha = md.digest();
        for (int a = 0; a < 28; a++)
            sha += format("%02x", bsha[a]);

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String[] lines = br.lines().toArray(String[]::new);
        conn.disconnect();

        if (lines.length < 3) {
            tshow_msg("SERVER ERROR:\n" + lines[0]);
            return false;
        }
        if (lines[2].indexOf(sha) != 0) {
            tshow_msg("ERROR:\nFile got corrupted during the upload;\n\n" + lines[2] + " expected\n" + sha + " from server");
            return false;
        }
        if (lines.length > 3 && !lines[3].isEmpty())
            f.share_url = lines[3];
        else
            f.share_url = f.full_url.split("\\?")[0];

        return true;
    }

    void onsuccess() {
        String msg = "✅ 👍\n\nCompleted successfully";
        if (files != null) {
            if (files.length == 1)
                msg += "\n\n" + files[0].share_url;
            else
                msg += "\n\n" + files.length + " files OK";
        }
        show_msg(msg);
        ((TextView)findViewById(R.id.upper_info)).setGravity(Gravity.CENTER);

        String act = prefs.getString("on_up_ok", "menu");
        if (act != null && !act.equals("menu")) {
            if (act.equals("copy"))
                copylink();
            else if (act.equals("share"))
                sharelink();
            else
                Toast.makeText(getApplicationContext(), "Upload OK", Toast.LENGTH_SHORT).show();

            finishAndRemoveTask();
            return;
        }

        findViewById(R.id.progbar).setVisibility(View.GONE);
        findViewById(R.id.successbuttons).setVisibility(View.VISIBLE);

        Button btn = (Button)findViewById(R.id.btnExit);
        btn.setOnClickListener(v -> finishAndRemoveTask());

        Button vcopy = (Button)findViewById(R.id.btnCopyLink);
        Button vshare = (Button)findViewById(R.id.btnShareLink);
        if (files == null) {
            vcopy.setVisibility(View.GONE);
            vshare.setVisibility(View.GONE);
            return;
        }
        vcopy.setOnClickListener(v -> copylink());
        vshare.setOnClickListener(v -> sharelink());
        if (files.length > 1)
            vshare.setVisibility(View.GONE);
    }

    void copylink() {
        if (files == null)
            return;

        String links = "";
        for (F file : files)
            links += file.share_url + "\n";

        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData cd = ClipData.newPlainText("copyparty upload", links);
        cb.setPrimaryClip(cd);
        Toast.makeText(getApplicationContext(), "Upload OK -- Link copied", Toast.LENGTH_SHORT).show();
    }

    void sharelink() {
        if (files == null || files.length > 1)
            return;

        F f = files[0];
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        send.putExtra(Intent.EXTRA_SUBJECT, "Uploaded file");
        send.putExtra(Intent.EXTRA_TEXT, f.share_url);
        //startActivity(Intent.createChooser(send, "Share file link"));

        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setData(Uri.parse(f.share_url));

        Intent i = Intent.createChooser(send, "Share file link");
        i.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { view });
        startActivity(i);
    }
}
