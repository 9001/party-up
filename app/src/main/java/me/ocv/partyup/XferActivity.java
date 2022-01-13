package me.ocv.partyup;

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
import java.util.stream.Collectors;

import me.ocv.partyup.databinding.ActivityXferBinding;

public class XferActivity extends AppCompatActivity {

    ActivityXferBinding binding;
    SharedPreferences prefs;
    Intent the_intent;
    String the_msg;
    String base_url, full_url, share_url;
    Uri src_uri;
    String src_name;
    long src_size;
    String the_desc;
    boolean upping;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityXferBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        the_intent = getIntent();
        String action = the_intent.getAction();
        String type = the_intent.getType();

        if (!the_intent.ACTION_SEND.equals(action) || type == null) {
            show_msg("cannot share this content");
            return;
        }

        upping = false;
        the_msg = null;
        src_name = null;
        src_size = -1;
        src_uri = (Uri)the_intent.getParcelableExtra(Intent.EXTRA_STREAM);
        the_msg = the_intent.getStringExtra(Intent.EXTRA_TEXT);
        if (src_uri != null) {
            handleSendImage();
        } else if (the_msg != null) {
            handleSendText();
        } else {
            show_msg("cannot decide on what to send for " + type);
            return;
        }

        final FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fab.setVisibility(View.GONE);
                do_up();
            }
        });
    }

    private void show_msg(String txt) {
        ((TextView)findViewById(R.id.upper_info)).setText(txt);
    }

    private void tshow_msg(String txt) {
        final TextView tv = (TextView)findViewById(R.id.upper_info);
        tv.post(new Runnable() {
            @Override
            public void run() {
                tv.setText(txt);
            }
        });
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
            if (!mime.contains(".") && mime.length() < 8)
                return mime;
        }

        return "bin";
    }

    private void handleSendText() {
        show_msg("Post the following link?\n\n" + the_msg);
        if (prefs.getBoolean("autosend", false))
            do_up();
    }

    private void handleSendImage() {
        // the following code returns the wrong filesize (off by 626 bytes)
        Cursor cur = getContentResolver().query(src_uri, null, null, null, null);
        int iname = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        int isize = cur.getColumnIndex(OpenableColumns.SIZE);
        cur.moveToFirst();
        src_name = cur.getString(iname);
        src_size = cur.getLong(isize);
        cur.close();

        if (src_name == null)
            src_name = "mystery-file." + getext(the_intent.getType());

        // get correct filesize
        try {
            InputStream ins = getContentResolver().openInputStream(src_uri);
            byte[] buf = new byte[128 * 1024];
            long sz = 0;
            while (true) {
                int n = ins.read(buf);
                if (n <= 0)
                    break;

                sz += n;
            }
            src_size = sz;
        }
        catch (Exception ex) {
            show_msg("Error: " + ex.toString());
            return;
        }

        the_desc = String.format("%s\n\nsize: %,d byte\ntype: %s", src_name, src_size, the_intent.getType());

        show_msg("Upload the following file?\n\n" + the_desc);
        if (prefs.getBoolean("autosend", false))
            do_up();
    }

    private void do_up() {
        if (upping)
            return;

        upping = true;

        try {
            base_url = full_url = prefs.getString("server_url", "");
            show_msg("Sending to " + base_url + " ...\n\n" + the_desc);

            if (!full_url.endsWith("/"))
                full_url += "/";

            if (src_size >= 0 && !src_name.isEmpty())
                full_url += URLEncoder.encode(src_name, "UTF-8");

            String password = prefs.getString("server_password", "");
            if (password.equals("Default value"))
                password = "";  // necessary in the emulator, not on real devices(?)

            if (!password.isEmpty())
                full_url += "?pw=" + password;

            Thread t = new Thread() {
                public void run() {
                    do_up2();
                }
            };
            t.start();
        }
        catch (Exception ex) {
            show_msg("Error: " + ex.toString());
        }
    }

    private void do_up2() {
        try {
            URL url = new URL(full_url);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            if (src_uri != null)
                do_fileput(conn);
            else
                do_textmsg(conn);
        }
        catch (Exception ex) {
            tshow_msg("Error: " + ex.toString());
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
        share_url = "HTTP " + rc;
        if (rc >= 300) {
            byte[] buf = new byte[1024];
            int n = Math.max(0, conn.getErrorStream().read(buf));
            tshow_msg("Server error " + rc + ":\n" + new String(buf, 0, n, "UTF-8"));
            conn.disconnect();
            return;
        }
        conn.disconnect();
        findViewById(R.id.upper_info).post(new Runnable() {
            @Override
            public void run() {
                onsuccess(false);
            }
        });
    }

    private void do_fileput(HttpURLConnection conn) throws Exception {
        conn.setRequestMethod("PUT");
        conn.setFixedLengthStreamingMode(src_size);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.connect();
        final TextView tv = (TextView) findViewById(R.id.upper_info);
        OutputStream os = conn.getOutputStream();
        InputStream ins = getContentResolver().openInputStream(src_uri);
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        byte[] buf = new byte[128 * 1024];
        long bytes_done = 0;
        while (true) {
            int n = ins.read(buf);
            if (n <= 0)
                break;

            bytes_done += n;
            os.write(buf, 0, n);
            md.update(buf, 0, n);

            final long meme = bytes_done;
            tv.post(new Runnable() {
                @Override
                public void run() {
                    double perc = ((double) meme * 100) / src_size;
                    tv.setText(String.format("Sending to %s ...\n\n%s\n\nbytes done:  %,d\nbytes left:  %,d\nprogress:  %.2f %%",
                            base_url,
                            the_desc,
                            meme,
                            src_size - meme,
                            perc
                    ));
                    ((ProgressBar) findViewById(R.id.progbar)).setProgress((int) Math.round(perc));
                }
            });
        }
        os.flush();
        int rc = conn.getResponseCode();
        if (rc >= 300) {
            int n = Math.max(0, conn.getErrorStream().read(buf));
            tshow_msg("Server error " + rc + ":\n" + new String(buf, 0, n, "UTF-8"));
            conn.disconnect();
            return;
        }
        String sha = "";
        byte[] bsha = md.digest();
        for (int a = 0; a < 28; a++)
            sha += String.format("%02x", bsha[a]);

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String[] lines = br.lines().collect(Collectors.toList()).toArray(new String[0]);
        conn.disconnect();

        if (lines.length < 3) {
            tshow_msg("SERVER ERROR:\n" + lines[0]);
            return;
        }
        if (lines[2].indexOf(sha) != 0) {
            tshow_msg("ERROR:\nFile got corrupted during the upload;\n\n" + lines[2] + " expected\n" + sha + " from server");
            return;
        }
        if (lines.length > 3 && !lines[3].isEmpty())
            share_url = lines[3];
        else
            share_url = full_url.split("\\?")[0];

        tv.post(new Runnable() {
            @Override
            public void run() {
                onsuccess(true);
            }
        });
    }

    void onsuccess(Boolean upload) {
        show_msg("✅ 👍\n\nCompleted successfully\n\n" + share_url);
        ((TextView)findViewById(R.id.upper_info)).setGravity(Gravity.CENTER);

        if (prefs.getBoolean("autoclose", false)) {
            Toast.makeText(getApplicationContext(), "Upload OK", Toast.LENGTH_SHORT).show();
            finishAndRemoveTask();
            return;
        }

        findViewById(R.id.progbar).setVisibility(View.GONE);
        findViewById(R.id.successbuttons).setVisibility(View.VISIBLE);

        Button btn = (Button)findViewById(R.id.btnExit);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAndRemoveTask();
            }
        });

        if (!upload) {
            findViewById(R.id.btnCopyLink).setVisibility(View.GONE);
            findViewById(R.id.btnShareLink).setVisibility(View.GONE);
            return;
        }

        btn = (Button)findViewById(R.id.btnCopyLink);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData cd = ClipData.newPlainText("copyparty upload", share_url);
                Toast.makeText(getApplicationContext(), "Link copied", Toast.LENGTH_SHORT).show();
            }
        });

        btn = (Button)findViewById(R.id.btnShareLink);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                send.putExtra(Intent.EXTRA_SUBJECT, "Uploaded file");
                send.putExtra(Intent.EXTRA_TEXT, share_url);
                //startActivity(Intent.createChooser(send, "Share file link"));

                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setData(Uri.parse(share_url));

                Intent i = Intent.createChooser(send, "Share file link");
                i.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { view });
                startActivity(i);
            }
        });
    }
}
