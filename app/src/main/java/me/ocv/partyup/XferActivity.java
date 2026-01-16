package me.ocv.partyup;

import static java.lang.String.format;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.provider.OpenableColumns;
import android.util.Log;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.preference.PreferenceManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import me.ocv.partyup.databinding.ActivityXferBinding;
import me.ocv.partyup.Xfer;

class CustomFile {
	public Uri handle;
	public String name;
	public Long size;
	public String full_url;
	public String share_url;
	public String desc;
	public String content;
	public String mime;
	public String ext;
}

public class XferActivity extends AppCompatActivity {
	ActivityXferBinding binding;
	SharedPreferences prefs;
	Intent the_intent;
	String password;
	String base_url;
	String share_url;
	Bitmap share_qr;
	boolean upping;
	String the_msg;
	long bytes_done, bytes_total, t0;
	CustomFile[] files;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		upping = false;
		share_url = null;
		share_qr = null;

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
		} else if (one) {
			Uri uri = (Uri) the_intent.getParcelableExtra(Intent.EXTRA_STREAM);
			if (uri != null)
				handles = new Uri[] { uri };
			else
				the_msg = the_intent.getStringExtra(Intent.EXTRA_TEXT);
		}
		if (handles != null) {
			files = new CustomFile[handles.length];
			for (int a = 0; a < handles.length; a++) {
				CustomFile customFile = new CustomFile();
				customFile.handle = handles[a];
				customFile.name = null;
				customFile.size = -1;
				files[a] = customFile;
			}
			handleSendImage();
		} else if (the_msg != null) {
			handleSendText();
		} else {
			show_msg("cannot decide on what to send for " + the_intent.getType());
			return;
		}

		password = prefs.getString("server_password", "");
		if (password == null || password.isEmpty() || password.equals("Default value"))
			password = null;

		final FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
		fab.setOnClickListener(v -> {
			fab.setVisibility(View.GONE);
			do_up();
		});
	}

	private void show_msg(String txt) {
		((TextView) findViewById(R.id.upper_info)).setText(txt);
	}

	private void tshow_msg(String txt) {
		final TextView tv = (TextView) findViewById(R.id.upper_info);
		tv.post(() -> tv.setText(txt));
	}

	void need_storage(String exmsg) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exmsg.contains("EACCES")) {
			show_msg(exmsg + "\n\nThe app you shared from uses deprecated file APIs.");
			return;
		}

		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
			request_storage();
			return;
		}

		String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
		if (this.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED)
			return; // already have it, so that's not why it failed

		if (!shouldShowRequestPermissionRationale(perm)) {
			request_storage();
			return;
		}

		AlertDialog.Builder ab = new AlertDialog.Builder(findViewById(R.id.upper_info).getContext());
		ab.setMessage(
				"PartyUP! needs additional permissions to read that file, because the app you shared it from is using old APIs.")
				.setPositiveButton("OK", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						request_storage();
					}
				}).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {

					}
				}).show();
	}

	void request_storage() {
		String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
		requestPermissions(new String[] { perm }, 573);
	}

	@Override
	public void onRequestPermissionsResult(int permRequestCode, String perms[], int[] grantRes) {
		String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
		if (permRequestCode != 573)
			return;

		for (int a = 0; a < grantRes.length; a++) {
			if (!perms[a].equals(perm))
				continue;

			if (grantRes[a] != PackageManager.PERMISSION_GRANTED)
				return;

			handleSendImage();
		}
	}

	String getext(String mime) {
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

	int[] parseExpiration(String value) {
		// Returns [number, unit] where unit: 0=minutes, 1=hours, 2=days,
		// -1=invalid/empty
		if (value == null || value.trim().isEmpty())
			return new int[] { 0, -1 };

		value = value.trim().toLowerCase();
		if (!value.matches("^\\d+[mhd]?$"))
			return new int[] { 0, -1 };

		char unit = value.charAt(value.length() - 1);
		int num;
		int unitType;

		if (Character.isDigit(unit)) {
			num = Integer.parseInt(value);
			unitType = 0; // minutes
		} else {
			num = Integer.parseInt(value.substring(0, value.length() - 1));
			switch (unit) {
				case 'h':
					unitType = 1;
					break;
				case 'd':
					unitType = 2;
					break;
				default:
					unitType = 0;
					break;
			}
		}
		return new int[] { num, unitType };
	}

	String getExpirationMinutes() {
		String value = prefs.getString("link_expiration", "");
		int[] parsed = parseExpiration(value);
		if (parsed[1] < 0)
			return "";

		int minutes = parsed[0];
		if (parsed[1] == 1)
			minutes *= 60; // hours
		else if (parsed[1] == 2)
			minutes *= 1440; // days

		return String.valueOf(minutes);
	}

	String getExpirationLabel() {
		String value = prefs.getString("link_expiration", "");
		int[] parsed = parseExpiration(value);
		if (parsed[1] < 0)
			return "never expires";

		int num = parsed[0];
		switch (parsed[1]) {
			case 0:
				return num + " minute" + (num != 1 ? "s" : "");
			case 1:
				return num + " hour" + (num != 1 ? "s" : "");
			case 2:
				return num + " day" + (num != 1 ? "s" : "");
			default:
				return "never expires";
		}
	}

	private void handleSendText() {
		show_msg("Post the following link?\n\n" + the_msg);
		showShareSettings();
		if (prefs.getBoolean("autosend", false))
			do_up();
	}

	private void showShareSettings() {
		if (prefs.getBoolean("use_share_url", false)) {
			findViewById(R.id.share_settings).setVisibility(View.VISIBLE);

			EditText expField = findViewById(R.id.share_expiration);
			EditText pwField = findViewById(R.id.share_password);

			String defaultExp = prefs.getString("link_expiration", "");
			String defaultPw = prefs.getString("share_password", "");

			expField.setText(defaultExp != null ? defaultExp : "");
			pwField.setText(defaultPw != null ? defaultPw : "");
		}
	}

	@SuppressLint("DefaultLocale")
	private void handleSendImage() {
		for (CustomFile customFile : files) {
			Log.d("me.ocv.partyup", format("handle [%s]", customFile.handle));
			if (customFile.handle.toString().startsWith("file:///")) {
				String path = customFile.handle.getPath();
				customFile.name = path != null ? path.substring(path.lastIndexOf('/') + 1) : "file";
			} else {
				// contentresolver returns the wrong filesize (off by 626 bytes)
				// but we want the name so lets go
				try {
					Cursor cur = getContentResolver().query(customFile.handle, null, null, null, null);
					assert cur != null;
					int iname = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
					int isize = cur.getColumnIndex(OpenableColumns.SIZE);
					cur.moveToFirst();
					customFile.name = cur.getString(iname);
					customFile.size = cur.getLong(isize);
					cur.close();
				} catch (Exception ex) {
					Log.w("me.ocv.partyup", "contentresolver: " + ex.toString());
				}
			}

			MessageDigest md = null;
			if (customFile.name == null) {
				try {
					md = MessageDigest.getInstance("SHA-512");
				} catch (Exception ex) {
				}
			}

			// get correct filesize
			try {
				InputStream ins = getContentResolver().openInputStream(customFile.handle);
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
				show_msg(exmsg);
				need_storage(exmsg);
				return;
			}

			if (md != null) {
				String csum = Base64.encodeToString(md.digest(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP)
						.substring(0, 15);
				customFile.name = format("mystery-file-%s.%s", csum, getext(the_intent.getType()));
			}

			customFile.desc = format("%s\n\nsize: %,d byte\ntype: %s", customFile.name, customFile.size,
					the_intent.getType());
		}

		String msg;
		bytes_done = bytes_total = 0;
		if (files.length == 1) {
			msg = "Upload the following file?\n\n" + files[0].desc;
			bytes_total = files[0].size;
		} else {
			msg = "Upload the following " + files.length + " files?\n\n";
			for (int a = 0; a < Math.min(10, files.length); a++) {
				msg += "  ► " + files[a].name + "\n";
				bytes_total += files[a].size;
			}

			if (files.length > 10)
				msg += "[...]\n";

			msg += format("\n(total %,d bytes)", bytes_total);
		}
		show_msg(msg);
		showShareSettings();
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

			if (base_url.contains("%")) {
				String[] dtc = "%Y %q %m %d %j %H %M %S".split(" ");

				SimpleDateFormat sdf = new SimpleDateFormat("yyyy Q MM dd DDD HH mm ss", Locale.US);
				sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
				String[] dtp = sdf.format(new Date()).split(" ");

				for (int a = 0; a < dtc.length; a++)
					base_url = base_url.replace(dtc[a], dtp[a]);
			}

			t0 = System.currentTimeMillis();
			tshow_msg("Sending to " + base_url + " ...");

			int nfiles = files == null ? 1 : files.length;
			for (int a = 0; a < nfiles; a++) {
				String full_url = base_url;
				if (files != null) {
					CustomFile customFile = files[a];
					full_url += URLEncoder.encode(customFile.name, "UTF-8");
					tshow_msg("Sending to " + base_url + " ...\n\n" + customFile.desc);
					customFile.full_url = full_url;
				}

				URL url = new URL(full_url);
				HttpURLConnection conn = (HttpURLConnection) url.openConnection();
				conn.setDoOutput(true);
				if (password != null)
					conn.setRequestProperty("PW", password);

				if (files == null)
					do_textmsg(conn);
				else if (!do_fileput(conn, a))
					return;
			}
			if (prefs.getBoolean("use_share_url", false))
				createShareUrl(files);

			findViewById(R.id.upper_info).post(() -> onsuccess());
		} catch (Exception ex) {
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

	// private void do_textmsg(HttpURLConnection conn) throws Exception {
	// 	byte[] body = ("msg=" + URLEncoder.encode(the_msg, "UTF-8")).getBytes(StandardCharsets.UTF_8);
	// 	conn.setRequestMethod("POST");
	// 	conn.setFixedLengthStreamingMode(body.length);
	// 	conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
	// 	conn.connect();
	// 	OutputStream os = conn.getOutputStream();
	// 	os.write(body);
	// 	os.flush();
	// 	int rc = conn.getResponseCode();
	// 	if (rc >= 300) {
	// 		tshow_msg("Server error " + rc + ":\n" + read_err(conn));
	// 		conn.disconnect();
	// 		return;
	// 	}
	// 	conn.disconnect();
	// }

	@SuppressLint("DefaultLocale")
	private boolean do_fileput(HttpURLConnection conn, int nfile) throws Exception {
		CustomFile customFile = files[nfile];
		conn.setRequestMethod("PUT");
		conn.setFixedLengthStreamingMode(customFile.size);
		conn.setRequestProperty("Content-Type", "application/octet-stream");
		conn.connect();
		final TextView tv = (TextView) findViewById(R.id.upper_info);
		final ProgressBar pb = (ProgressBar) findViewById(R.id.progbar);
		OutputStream os = conn.getOutputStream();
		InputStream ins = getContentResolver().openInputStream(customFile.handle);
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
				double perc = ((double) bytes_done * 1000) / bytes_total;
				long td = 1 + System.currentTimeMillis() - t0;
				double spd = bytes_done / (td / 1000.0);
				long left = (long) ((bytes_total - bytes_done) / spd);
				tv.setText(format(
						"Sending to %s ...\n\nFile %d of %d:\n%s\n\nbytes done:  %,d\nbytes left:  %,d\nspeed:  %.2f MiB/s\nprogress:  %.2f %%\nETA:  %d sec",
						base_url,
						nfile + 1,
						files.length,
						customFile.desc,
						bytes_done,
						bytes_total - bytes_done,
						spd / 1024 / 1024,
						perc / 10,
						left));
				pb.setProgress((int) Math.round(perc));
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

		ArrayList<String> linesList = new ArrayList<>();
		String line;
		while ((line = br.readLine()) != null) {
			linesList.add(line);
		}
		String[] lines = linesList.toArray(new String[0]);

		conn.disconnect();

		if (lines.length < 3) {
			tshow_msg("SERVER ERROR:\n" + lines[0]);
			return false;
		}
		if (lines[2].indexOf(sha) != 0) {
			tshow_msg("ERROR:\nFile got corrupted during the upload;\n\n" + lines[2] + " expected\n" + sha
					+ " from server");
			return false;
		}

		if (lines.length > 3 && !lines[3].isEmpty())
			customFile.share_url = lines[3];
		else
			customFile.share_url = customFile.full_url.split("\\?")[0];

		return true;
	}

	void createShareUrl(CustomFile[] customFile) {
		try {
			// Generate random key
			String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
			SecureRandom random = new SecureRandom();
			StringBuilder key = new StringBuilder();
			for (int i = 0; i < 12; i++)
				key.append(chars.charAt(random.nextInt(chars.length())));

			URL url = new URL(customFile[0].full_url);

			// Build share API URL (base URL without file path)
			String shareApiUrl = url.getProtocol() + "://" + url.getHost();
			if (url.getPort() != -1)
				shareApiUrl += ":" + url.getPort();
			shareApiUrl += "/?share";

			// Get expiration
			EditText expField = findViewById(R.id.share_expiration);
			String expValue = expField.getText().toString();
			int[] parsed = parseExpiration(expValue);
			String expiration = "";
			if (parsed[1] >= 0) {
				int minutes = parsed[0];
				if (parsed[1] == 1)
					minutes *= 60;
				else if (parsed[1] == 2)
					minutes *= 1440;
				expiration = String.valueOf(minutes);
			}

			// Get password
			EditText pwField = findViewById(R.id.share_password);
			String sharePw = pwField.getText().toString();

			StringBuilder sharedFilesPaths = new StringBuilder();
			for (int i = 0; i < files.length; i++) {
				String filePath = java.net.URLDecoder.decode(new URL(files[i].full_url).getPath(), "UTF-8");
				sharedFilesPaths.append("\"").append(filePath).append("\"");
				if (i < files.length - 1) {
					sharedFilesPaths.append(",");
				}
			}

			// Build JSON body
			String jsonBody = format("{\"k\":\"%s\",\"vp\":[%s],\"pw\":\"%s\",\"exp\":\"%s\",\"perms\":[\"read\"]}",
					key.toString(), sharedFilesPaths, sharePw, expiration);

			URL apiUrl = new URL(shareApiUrl);
			HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "text/plain");
			if (password != null)
				conn.setRequestProperty("PW", password);

			byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
			conn.setFixedLengthStreamingMode(body.length);
			conn.connect();

			OutputStream os = conn.getOutputStream();
			os.write(body);
			os.flush();

			int rc = conn.getResponseCode();
			if (rc >= 300) {
				Log.w("me.ocv.partyup", "Share creation failed: " + rc);
				conn.disconnect();
				return;
			}

			BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			String response = br.readLine();
			conn.disconnect();

			// Parse response: "created share: https://..."
			if (response != null && response.startsWith("created share: "))
				share_url = response.substring(15);

		} catch (Exception ex) {
			Log.w("me.ocv.partyup", "Share creation error: " + ex.toString());
		}
	}

	void onsuccess() {
		String msg = "✅ 👍\n\nCompleted successfully";
		if (files != null) {
			if (files.length == 1 && share_url == null) {
				msg += "\n\n" + files[0].share_url;
			} else if (share_url != null) {
				msg += "\n\n" + share_url;
			} else {
				msg += "\n\n" + files.length + " files OK";
			}
		}
		show_msg(msg);
		((TextView) findViewById(R.id.upper_info)).setGravity(Gravity.CENTER);

		String act = prefs.getString("on_up_ok", "menu");
		if (act != null && !act.equals("menu")) {
			if (act.equals("copy"))
				copylink();
			else if (act.equals("share"))
				sharelink();
			else
				Toast.makeText(getApplicationContext(), "Upload OK", Toast.LENGTH_SHORT).show();

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				finishAndRemoveTask();
			} else {
				finish();
			}
			return;
		}

		findViewById(R.id.progbar).setVisibility(View.GONE);
		findViewById(R.id.share_settings).setVisibility(View.GONE);
		findViewById(R.id.successbuttons).setVisibility(View.VISIBLE);

		Button btn = (Button) findViewById(R.id.btnExit);
		btn.setOnClickListener(v -> {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				finishAndRemoveTask();
			} else {
				finish();
			}
		});

		Button vcopy = (Button) findViewById(R.id.btnCopyLink);
		Button vqrcode = (Button) findViewById(R.id.btnQrCode);
		Button vshare = (Button) findViewById(R.id.btnShareLink);
		if (files == null) {
			vcopy.setVisibility(View.GONE);
			vshare.setVisibility(View.GONE);
			return;
		}
		vcopy.setOnClickListener(v -> copylink());
		vshare.setOnClickListener(v -> sharelink());
		vqrcode.setOnClickListener(view -> showQr());
		if (files.length > 1 && share_url == null) {
			vshare.setVisibility(View.GONE);
			vqrcode.setVisibility(View.GONE);
		}

	}

	void copylink() {
		if (files == null)
			return;

		String links = "";

		if (share_url != null) {
			links = share_url;
		} else {
			for (CustomFile file : files)
				links += file.share_url + "\n";
		}

		ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		ClipData cd = ClipData.newPlainText("copyparty upload", links);
		cb.setPrimaryClip(cd);
		Toast.makeText(getApplicationContext(), "Upload OK -- Link copied", Toast.LENGTH_SHORT).show();
	}

	void sharelink() {
		if (files == null)
			return;

		String link_to_share;

		if (share_url != null) {
			link_to_share = share_url;
		} else if (files.length > 1) {
			return;
		} else {
			link_to_share = files[0].share_url;
		}

		Intent send = new Intent(Intent.ACTION_SEND);
		send.setType("text/plain");
		send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		send.putExtra(Intent.EXTRA_SUBJECT, "Uploaded file");
		send.putExtra(Intent.EXTRA_TEXT, link_to_share);
		// startActivity(Intent.createChooser(send, "Share file link"));

		Intent view = new Intent(Intent.ACTION_VIEW);
		view.setData(Uri.parse(link_to_share));

		Intent i = Intent.createChooser(send, "Share file link");
		i.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { view });
		startActivity(i);
	}

	void showQr() {
		if (share_qr == null) {
			String url_to_share;
			if (share_url != null)
				url_to_share = share_url;
			else
				url_to_share = files[0].full_url;

			try {
				QRCodeWriter qrCodeWriter = new QRCodeWriter();
				int size = 256;
				BitMatrix bitMatrix = qrCodeWriter.encode(url_to_share, BarcodeFormat.QR_CODE, size, size);

				Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);

				for (int x = 0; x < size; x++) {
					for (int y = 0; y < size; y++) {
						bitmap.setPixel(x, y,
								bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
					}
				}

				share_qr = bitmap;

			} catch (WriterException e) {
				throw new RuntimeException(e);
			}
		}
		AlertDialog.Builder ImageDialog = new AlertDialog.Builder(XferActivity.this);
		ImageView shownImage = new ImageView(XferActivity.this);
		shownImage.setImageBitmap(share_qr);
		shownImage.setLayoutParams(new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		shownImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
		shownImage.setAdjustViewBounds(true);

		ImageDialog.setView(shownImage);

		ImageDialog.show();
	}
}
