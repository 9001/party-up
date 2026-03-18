package me.ocv.partyup.utils;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import androidx.core.util.Consumer;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;

import me.ocv.partyup.objects.CustomFile;

public class Discovery {
    private Context context;
    private Consumer<String> onError;

    public CustomFile[] parseIntent(
            Context context,
            Intent intent,
            Consumer<String> onError) {

        this.onError = onError;
        this.context = context;
        ArrayList<CustomFile> files = new ArrayList<>();
        String the_msg = null;

        String eType = intent.getType();
        String action = intent.getAction();
        boolean one = Intent.ACTION_SEND.equals(action);
        boolean many = Intent.ACTION_SEND_MULTIPLE.equals(action);

        if (eType == null || (!one && !many)) {
            this.onError.accept(
                    "cannot share content;\naction: " + action + "\ntype: " + eType);
            return files.toArray(new CustomFile[0]);
        }

        Uri[] handles = null;

        if (many) {
            ArrayList<Uri> x = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (x != null)
                handles = x.toArray(new Uri[0]);
        } else {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null)
                handles = new Uri[]{uri};
            else
                the_msg = intent.getStringExtra(Intent.EXTRA_TEXT);
        }

        if (handles != null) {
            // 'handles' non text files
            for (Uri uri : handles) {
                CustomFile cf = new CustomFile();
                cf.handle = uri;
                cf.mime = eType;
                parseFile(cf);

                if (cf.size == null) continue;

                files.add(cf);
            }
        } else if (the_msg != null) {
            // only text files
            CustomFile cf = new CustomFile();
            cf.content = the_msg;
            cf.mime = "text/plain";
            cf.size = (long) the_msg.length();

            files.add(cf);
        } else {
            this.onError.accept(
                    "Cannot decide on what to send for " + intent.getType());
        }

        return files.toArray(new CustomFile[0]);
    }

    private String getExt(String mime) {
        if (mime == null)
            return "bin";

        mime = mime.replace(';', ' ').split(" ")[0];

        switch (mime) {
            case "audio/ogg":
                return "ogg";
            case "audio/mpeg":
                return "mp3";
            case "audio/mp4":
                return "m4a";
            case "image/jpeg":
                return "jpg";
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

    private void parseFile(CustomFile cf) {
        // This fn doesn't parse files that is only text (aka share on selected text)
        // mime is set above
        cf.ext = getExt(cf.mime);

        if ("file".equals(cf.handle.getScheme())) {
            String path = cf.handle.getPath();
            cf.name = path != null
                    ? path.substring(path.lastIndexOf('/') + 1)
                    : "file";
        } else {
            try (Cursor cur = this.context.getContentResolver().query(
                    cf.handle,
                    new String[]{
                            OpenableColumns.DISPLAY_NAME,
                            OpenableColumns.SIZE
                    },
                    null, null, null)) {

                if (cur != null && cur.moveToFirst()) {
                    int iName = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int iSize = cur.getColumnIndex(OpenableColumns.SIZE);

                    if (iName != -1)
                        cf.name = cur.getString(iName);
                    if (iSize != -1)
                        cf.size = cur.getLong(iSize);
                }
            } catch (Exception ex) {
                Log.w("Discovery", "Content Resolver: " + ex);
            }
        }

        MessageDigest md = null;

        if (cf.name == null) {
            try {
                md = MessageDigest.getInstance("SHA512");
            } catch (Exception e) {
                Log.e("Discovery", "Unable to get MD Instance");
            }
        }

        // get correct file size
        try (InputStream ins = this.context.getContentResolver().openInputStream(cf.handle)) {
            assert ins != null;
            byte[] buf = new byte[128 * 1024];
            long sz = 0;
            while (true) {
                int n = ins.read(buf);
                if (n <= 0)
                    break;

                sz += n;
                if (md != null)
                    md.update(buf, 0, n);
            }
            cf.size = sz;
        } catch (Exception ex) {
            this.onError.accept("StoragePermissionNeeded");
            String exMsg = "Error3: " + ex;
            this.onError.accept("StoragePermissionNeeded: " + exMsg);
            return;
        }

        if (md != null) {
            String cSum = Base64.encodeToString(md.digest(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP)
                    .substring(0, 15);
            cf.name = String.format("mystery-file-%s.%s", cSum, cf.ext);
        }

        cf.desc = String.format(Locale.getDefault(),
                "%s\n\nsize: %,d byte\ntype: %s",
                cf.name,
                cf.size,
                cf.mime);
    }
}
