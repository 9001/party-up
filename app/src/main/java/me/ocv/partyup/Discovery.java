package me.ocv.partyup;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.function.Consumer;

public class Discovery {
	private Content context;
	private Consumer<String> onError;

	public CustomFile[] parseIntent(
			Context context,
			Intent intent,
			Consumer<String> onError) {

		this.onError = onError;
		this.context = context;
		ArrayList<CustomFile> files = new ArrayList<>();
		String the_msg = null;

		String etype = intent.getType();
		String action = intent.getAction();
		boolean one = Intent.ACTION_SEND.equals(action);
		boolean many = Intent.ACTION_SEND_MULTIPLE.equals(action);

		if (etype == null || (!one && !many)) {
			this.onError.accept(
					"cannot share content;\naction: " + action + "\ntype: " + etype);
			return new CustomFile[0];
		}

		Uri[] handles = null;

		if (many) {
			ArrayList<Uri> x = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
			if (x != null)
				handles = x.toArray(new Uri[0]);
		} else {
			Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
			if (uri != null)
				handles = new Uri[] { uri };
			else
				the_msg = intent.getStringExtra(Intent.EXTRA_TEXT);
		}

		if (handles != null) {
			for (Uri uri : handles) {
				CustomFile cf = new CustomFile();
				cf.handle = uri;
				cf.size = -1;
				cf.mime = etype;
				parseFile(cf);
				files.add(cf);
			}
		} else if (the_msg != null) {
			CustomFile cf = new CustomFile();
			cf.content = the_msg;
			cf.mime = "text/plain";
			files.add(cf);
		} else {
			this.onError.accept(
					"cannot decide on what to send for " + intent.getType());
		}

		return files.toArray(new CustomFile[0]);
	}

	private String getext(String mime) {
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
		cf.ext = getext(cf.mime);

		if ("file".equals(cf.handle.getScheme())) {
			String path = cf.handle.getPath();
			cf.name = path != null
					? path.substring(path.lastIndexOf('/') + 1)
					: "file";
		} else {
			Cursor cur = null;
			try {
				cur = this.context.getContentResolver().query(
						cf.handle,
						new String[] {
								OpenableColumns.DISPLAY_NAME,
								OpenableColumns.SIZE
						},
						null, null, null);

				if (cur != null && cur.moveToFirst()) {
					int iname = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
					int isize = cur.getColumnIndex(OpenableColumns.SIZE);

					if (iname != -1)
						cf.name = cur.getString(iname);
					if (isize != -1)
						cf.size = cur.getLong(isize);
				}
			} catch (Exception ex) {
				Log.w("Discovery", "contentresolver: " + ex);
			} finally {
				if (cur != null)
					cur.close();
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

		// get correct filesize
		try {
			InputStream ins = this.context.getContentResolver().openInputStream(customFile.handle);
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
			customFile.size = sz;
		} catch (Exception ex) {
			String exmsg = "Error3: " + ex.toString();
			this.onError.accept("StoragePermissionNeeded: " + exmsg);
			return;
		}

		if (md != null) {
			String csum = Base64.encodeToString(md.digest(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP)
					.substring(0, 15);
			customFile.name = String.format("mystery-file-%s.%s", csum, cf.ext);
		}

		customFile.desc = String.format(
				"%s\n\nsize: %,d byte\ntype: %s",
				customFile.name,
				customFile.size,
				cf.mime);
	}
}
