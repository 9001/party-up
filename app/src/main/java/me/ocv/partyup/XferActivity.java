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
import android.widget.TextView;
import android.preference.PreferenceManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import me.ocv.partyup.databinding.ActivityXferBinding;

public class XferActivity extends AppCompatActivity {

    private ActivityXferBinding binding;
    private Intent the_intent;
    private String the_msg;
    private Uri file_uri;
    private String file_name;
    private long file_size;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityXferBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        the_msg = null;
        file_uri = null;
        file_name = null;
        file_size = -1;
        if (Intent.ACTION_SEND.equals(action) && type != null) {
            the_intent = intent;
            if ("text/plain".equals(type)) {
                handleSendText(); // Handle text being sent
            } else {
                handleSendImage(); // Handle single image being sent
            }

            FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
            fab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
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

        show_msg("Upload the following file?\n\n" + file_name + " (" + file_size + " B)");
        //show_msg("Upload the following file?\n\n" + file_uri);
    }

    private void do_up() {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            String server_url = prefs.getString("server_url", "");
            String password = prefs.getString("server_password", "");
            if (password.equals("Default value"))
                password = "";  // necessary in the emulator, not on real devices(?)

            show_msg("Sending to " + server_url);

            final String server_url2 = server_url + (password.isEmpty() ? "" : "?pw=" + password);

            Thread t = new Thread() {
                public void run() {
                    do_up2(server_url2);
                }
            };
            t.start();
            t.join();
            // android: throws exception if you do network stuff on the main thread
            // also android: deletes the file handle when the main thread finishes
        }
        catch (Exception ex) {
            show_msg("Error: " + ex.toString());
        }
    }

    private void do_up2(String server_url) {
        try {
            URL url = new URL(server_url);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            if (the_msg != null)
                do_textmsg(conn);
            else
                do_fileput(conn);
        }
        catch (Exception ex) {
            show_msg("Error: " + ex.toString());
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
        OutputStream os = conn.getOutputStream();
        InputStream ins = getContentResolver().openInputStream(file_uri);
        byte[] buf = new byte[128 * 1024];
        while (true) {
            int n = ins.read(buf);
            if (n <= 0)
                break;

            os.write(buf, 0, n);
        }
        os.flush();
        conn.disconnect();
        finishAndRemoveTask();
    }
}
