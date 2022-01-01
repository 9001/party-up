package me.ocv.partyup;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import androidx.appcompat.app.AppCompatActivity;

import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import me.ocv.partyup.databinding.ActivityXferBinding;

public class XferActivity extends AppCompatActivity {

    private ActivityXferBinding binding;
    private Intent the_intent;
    private String the_msg;
    private String base_url, full_url;
    private Uri file_uri;
    private String file_name;
    private long file_size;
    private String the_desc;
    private boolean upping;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityXferBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        upping = false;
        the_msg = null;
        file_uri = null;
        file_name = null;
        file_size = -1;
        if (Intent.ACTION_SEND.equals(action) && type != null) {
            the_intent = intent;
            if (intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
                handleSendText();
            } else {
                handleSendImage();
            }

            final FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
            fab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    fab.setVisibility(View.INVISIBLE);
                    do_up();
                }
            });

            return;
        }

        show_msg("cannot share this content");
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

    private void handleSendText() {
        the_msg = the_intent.getStringExtra(Intent.EXTRA_TEXT);
        show_msg("Post the following link?\n\n" + the_msg);
    }

    private void handleSendImage() {
        file_uri = (Uri)the_intent.getParcelableExtra(Intent.EXTRA_STREAM);

        // the following code returns the wrong filesize (off by 626 bytes)
        Cursor cur = getContentResolver().query(file_uri, null, null, null, null);
        int iname = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        int isize = cur.getColumnIndex(OpenableColumns.SIZE);
        cur.moveToFirst();
        file_name = cur.getString(iname);
        file_size = cur.getLong(isize);
        cur.close();

        // get correct filesize
        try {
            InputStream ins = getContentResolver().openInputStream(file_uri);
            byte[] buf = new byte[128 * 1024];
            long sz = 0;
            while (true) {
                int n = ins.read(buf);
                if (n <= 0)
                    break;

                sz += n;
            }
            file_size = sz;
        }
        catch (Exception ex) {
            show_msg("Error: " + ex.toString());
            return;
        }

        the_desc = String.format("%s\n\nsize: %,d byte\ntype: %s", file_name, file_size, the_intent.getType());

        show_msg("Upload the following file?\n\n" + the_desc);
    }

    private void do_up() {
        if (upping)
            return;

        upping = true;

        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            base_url = full_url = prefs.getString("server_url", "");
            show_msg("Sending to " + base_url + " ...\n\n" + the_desc);

            if (!full_url.endsWith("/"))
                full_url += "/";

            if (file_size >= 0 && !file_name.isEmpty())
                full_url += URLEncoder.encode(file_name, "UTF-8");

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
            if (the_msg != null)
                do_textmsg(conn);
            else
                do_fileput(conn);
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
        conn.disconnect();
        finishAndRemoveTask();
    }

    private void do_fileput(HttpURLConnection conn) throws Exception {
        conn.setRequestMethod("PUT");
        conn.setFixedLengthStreamingMode(file_size);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.connect();
        final TextView tv = (TextView)findViewById(R.id.upper_info);
        OutputStream os = conn.getOutputStream();
        InputStream ins = getContentResolver().openInputStream(file_uri);
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
                    double perc = ((double) meme * 100) / file_size;
                    tv.setText(String.format("Sending to %s ...\n\n%s\n\nbytes done:  %,d\nbytes left:  %,d\nprogress:  %.2f %%",
                            base_url,
                            the_desc,
                            meme,
                            file_size - meme,
                            perc
                    ));
                    ((ProgressBar)findViewById(R.id.progbar)).setProgress((int)Math.round(perc));
                }
            });
        }
        os.flush();
        int rc = conn.getResponseCode();
        if (rc >= 300) {
            int n = conn.getErrorStream().read(buf);
            tshow_msg("Server error " + rc + ":\n" + new String(buf, 0, n, "UTF-8"));
            conn.disconnect();
            return;
        }
        String sha = "";
        byte[] bsha = md.digest();
        for (int a = 0; a < 28; a++)
            sha += String.format("%02x", bsha[a]);

        String line, line2 = "<null>";
        boolean ok = false;
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        while ((line = br.readLine()) != null)
            if (line.indexOf(sha) >= 0)
                ok = true;
            else
                line2 = line;

        conn.disconnect();
        if (ok) {
            finishAndRemoveTask();
            return;
        }
        tshow_msg("ERROR:\nFile got corrupted during the upload;\n\n" + line2 + " expected\n" + sha + " from server");
    }
}
