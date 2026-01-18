package me.ocv.partyup;

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
import java.lang.reflect.Array;
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
import java.util.StringJoiner;
import java.util.TimeZone;
import java.util.function.Consumer;

import me.ocv.partyup.databinding.ActivityXferBinding;
import me.ocv.partyup.Uploader;
import me.ocv.partyup.Discovery;

class CustomFile {
	public Uri handle;
	public Long size;
	public String name;
	public String full_url;
	public String share_url;
	public String desc;
	public String content;
	public String mime;
	public String ext;

	public boolean isSharable() {
		boolean hasUrl = (share_url != null && !share_url.isEmpty()) ||
				(full_url != null && !full_url.isEmpty());
		boolean isText = "text/plain".equals(mime);

		return hasUrl && !isText;
	}

	public String getBestUrl() {
		if (!isSharable())
			return "";
		if (share_url != null && !share_url.isEmpty()) {
			return share_url;
		}
		if (full_url != null && !full_url.isEmpty()) {
			return full_url;
		}
		return "";
	}

	@Override
	public String toString() {
		return "CustomFile{" +
				"handle=" + String.valueOf(handle) +
				", size=" + String.valueOf(size) +
				", name=" + String.valueOf(name) +
				", full_url=" + String.valueOf(full_url) +
				", share_url=" + String.valueOf(share_url) +
				", content=" + String.valueOf(content) +
				", mime=" + String.valueOf(mime) +
				", ext=" + String.valueOf(ext) +
				'}';
	}

}

class Progress {
	// bytes
	public long done;
	public long total;

	public long t0 = System.currentTimeMillis();

	public long left() {
		return Math.max(0, total - done);
	};

	public double perc() {
		return total > 0 ? (double) done / total : 0.0;
	};

	public double speed() {
		// in bytes
		if (t0 == 0 || done == 0)
			return 0.0;
		double seconds = (System.currentTimeMillis() - t0) / 1000.0;
		return seconds <= 0 ? 0.0 : done / seconds;
	};

	public long eta() {
		// seconds
		double s = speed();
		return s <= 0 ? -1 : (long) (left() / speed());
	}

	public String stats() {
		String format = String.join("\n", "Bytes: %d/%d (%d)", "Percentage: %.2f", "Speed: %s", "ETA: %d sec");
		return String.format(format, done, total, left(), perc() * 100, formatBytes((long) speed()), eta());
	}

	public static String formatBytes(Long bytes) {
		if (bytes == null)
			return "0 B";

		if (bytes < 1024) {
			return bytes + " B";
		}

		final String[] units = { "KB", "MB", "GB", "TB", "PB" };
		double value = bytes;
		int unit = -1;

		do {
			value /= 1024;
			unit++;
		} while (value >= 1024 && unit < units.length - 1);

		return String.format(Locale.US, "%.2f %s", value, units[unit]);
	}
}

public class XferActivity extends AppCompatActivity {
	ActivityXferBinding binding;
	SharedPreferences prefs;

	String base_url;
	String password;

	Boolean upping;
	Boolean autosend;
	CustomFile[] files;

	Discovery discovery = new Discovery();
	Uploader uploader = new Uploader();
	Progress progress = new Progress();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		upping = false;

		prefs = PreferenceManager.getDefaultSharedPreferences(this);
		binding = ActivityXferBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		setSupportActionBar(binding.toolbar);

		autosend = prefs.getBoolean("autosend", false);
		base_url = prefs.getString("server_url", "");
		password = prefs.getString("server_password", "");
		if (password == null || password.isEmpty() || password.equals("Default value"))
			password = null;

		files = discovery.parseIntent(this, getIntent(), (msg) -> {
			if ("StoragePermissionNeeded".equals(msg))
				need_storage(msg);
			else
				tshow_msg(msg);
		});

		uploader.setContext(this);
		uploader.setPassword(password);
		uploader.setServerUrl(base_url);

		// Looping over all prepared files, can do additional work here
		for (CustomFile cf : files) {
			if (cf.size != null && cf.size > 0) {
				progress.total += cf.size;
			}
		}

		final FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
		fab.setOnClickListener(v -> {
			fab.setVisibility(View.GONE);
			do_up();
		});

		if (autosend)
			do_up();
		else
			showShareSettings();
	}

	private void show_msg(String txt) {
		((TextView) findViewById(R.id.upper_info)).setText(txt);
	}

	private void tshow_msg(String txt) {
		final TextView tv = (TextView) findViewById(R.id.upper_info);
		tv.post(() -> tv.setText(txt));
	}

	private String getTextBody() {
		StringJoiner messages = new StringJoiner("\n");

		int counter = 1;
		for (int i = 0; i < files.length; i++) {
			if (counter > 10)
				break;

			CustomFile cf = files[i];
			if (!cf.mime.equals("text/plain") || cf.content == null)
				continue;

			messages.add(String.format("%d. %s", counter, cf.content.trim()));
			counter++;
		}

		String header = String.format("Post%s the following link%s%s", autosend ? "ing" : "", counter > 1 ? "s" : "",
				autosend ? ":" : "?");
		return counter == 1 ? "" : String.join("\n\n", header, messages.toString(), counter > 10 ? "[...]" : "");
	}

	private String getFileBody() {
		StringJoiner filenames = new StringJoiner("\n");
		int counter = 1;
		for (int i = 0; i < files.length; i++) {
			if (counter > 10)
				break;

			CustomFile file = files[i];
			if (file.mime.equals("text/plain") || file.size == null)
				continue;

			filenames.add(String.format("%d. %s [%s]", counter, file.name, progress.formatBytes(file.size)));
			counter++;
		}

		String header = String.format("Upload%s the following file%s%s", autosend ? "ing" : "", counter > 1 ? "s" : "",
				autosend ? ":" : "?");
		return counter == 1 ? "" : String.join("\n\n", header, filenames.toString(), counter > 10 ? "[...]" : "");
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

		String header = "Hi, Welcome to PartyUP!";
		String body = String.join("\n",
				"You are uploading to:", base_url,
				getTextBody(),
				getFileBody(),
				(!autosend ? "Press the button to upload!" : "Starting..."));
		String footer = String.join("\n",
				String.format("Total files: %d", files.length),
				String.format("Total size: %s", progress.formatBytes(progress.total)));

		String fullBody = String.join("\n\n", header, body, footer);
		show_msg(fullBody);
	}

	private void do_up() {
		if (upping)
			return;

		upping = true;
		new Thread(this::do_up2).start();
	}

	private void do_up2() {
		try {
			progress.t0 = System.currentTimeMillis();

			final TextView tv = (TextView) findViewById(R.id.upper_info);
			final ProgressBar pb = (ProgressBar) findViewById(R.id.progbar);

			class ProgressStats {
				// Set default so doesn't freaks out
				public long done = 0;
				public int nfile = 0;
				public int total_file = files.length;
			}

			final Consumer<ProgressStats> onProgress = (ps) -> {
				try {
					tv.post(() -> {
						progress.done = ps.done;
						int nfile = ps.nfile;
						int total_file = ps.total_file;

						tv.setText(
								String.join(
										"\n\n",
										String.format("Sending to: %s...", base_url),
										String.format("File: %d of %d\nDesc: %s", nfile + 1, total_file,
												files[nfile].desc),
										progress.stats()));
						pb.setProgress((int) Math.round(progress.perc() * 100));
					});
				} catch (Exception e) {
					Log.e("XferActivity", "Issue in ui updating: " + e.toString());
				}
			};
			Consumer<Error> onError = (err) -> {
				tshow_msg(err.toString());
			};

			uploader.setOnError(onError);

			final ProgressStats ps = new ProgressStats();
			ps.total_file = files.length;

			for (int i = 0; i < files.length; i++) {
				CustomFile file = files[i];
				ps.nfile = i;

				Consumer<UploadProgress> onPro = (up) -> {
					ps.done += up.delta;
					onProgress.accept(ps);
				};

				uploader.setOnProgress(onPro);
				boolean uploaded = uploader.upload(file);
				if (!uploaded) {
					return;
				}
			}

			// Handle creation of share url in `onsuccess`
			findViewById(R.id.upper_info).post(() -> onsuccess());
		} catch (Exception ex) {
			tshow_msg("Error2: " + ex.toString() + "\n\nmaybe wrong password?");
		}
	}

	private void onsuccess() {
		String msg = "✅👍\n\nCompleted successfully";
		final String share_url = createShareUrl();
		String footer = String.format("Total file uploaded: %d (%s)", files.length,
				progress.formatBytes(progress.total));

		show_msg(String.join("\n", msg, share_url, footer));
		((TextView) findViewById(R.id.upper_info)).setGravity(Gravity.CENTER);
		if (share_url.isEmpty()) {
			Toast.makeText(
					getApplicationContext(),
					"Share Failed!",
					Toast.LENGTH_SHORT).show();
			return;
		}

		String act = prefs.getString("on_up_ok", "menu");
		if (act != null && !act.equals("menu")) {
			if (act.equals("copy"))
				copylink(share_url);
			else if (act.equals("share"))
				sharelink(share_url);
			else
				Toast.makeText(getApplicationContext(), "Upload OK",
						Toast.LENGTH_SHORT).show();

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

		vcopy.setOnClickListener(v -> copylink(share_url));
		vshare.setOnClickListener(v -> sharelink(share_url));
		vqrcode.setOnClickListener(view -> showQr(share_url));
	}

	private CustomFile[] getWantedFiles() {
		ArrayList<CustomFile> needed = new ArrayList<>();

		for (CustomFile file : files) {
			if (file.isSharable())
				needed.add(file);
		}

		return needed.toArray(new CustomFile[0]);
	}

	private String createShareUrl() {
		try {

			CustomFile[] wantedFiles = getWantedFiles();

			if (wantedFiles.length == 0) {
				// It means no valid files were found and if there are files then they are
				// mostly text ( aka links )
				return files[0].share_url;
			} else if ((files.length - wantedFiles.length) < 2) {
				// Too low files
				for (CustomFile file : wantedFiles) {
					String bestUrl = file.getBestUrl();
					if (bestUrl.isEmpty())
						continue;
					return bestUrl;
				}
				throw new Exception("Man don't know!");
			}

			String key = generateRandomKey(12);
			Uri shareApiUri = Uri.parse(uploader.getServerUrl());

			String expiration = getExpiration();
			// Get password
			EditText pwField = findViewById(R.id.share_password);
			String sharePw = pwField.getText().toString();

			StringBuilder sharedFilesPaths = new StringBuilder();

			// "/File_path","/File_path/2"
			for (int i = 0; i < wantedFiles.length; i++) {
				CustomFile cf = wantedFiles[i];
				String filePath = java.net.URLDecoder.decode(new URL(cf.full_url).getPath(), "UTF-8");

				sharedFilesPaths.append("\"").append(filePath).append("\"");
				if (i < files.length - 1) {
					sharedFilesPaths.append(",");
				}
			}

			if (sharedFilesPaths != null && sharedFilesPaths.length() == 0) {
				throw new Exception("No media/file type found in shared items");
			}

			return getSharableUrl(key, sharedFilesPaths, sharePw, expiration, shareApiUri);
		} catch (Exception ex) {
			Log.w("me.ocv.partyup", "Share creation error: " + ex.toString());
		}

		return "";
	}

	void copylink(String share_url) {
		StringBuilder sb = new StringBuilder();

		if (!share_url.isEmpty())
			sb.append(share_url);

		for (CustomFile file : files) {
			String bestUrl = file.getBestUrl();
			if (file.getBestUrl().isEmpty())
				continue;
			sb.append(bestUrl);
		}

		ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		ClipData cd = ClipData.newPlainText("copyparty upload", sb);
		cb.setPrimaryClip(cd);
		Toast.makeText(getApplicationContext(), "Upload OK -- Link copied",
				Toast.LENGTH_SHORT).show();
	}

	private void sharelink(String share_url) {
		Intent send = new Intent(Intent.ACTION_SEND);
		send.setType("text/plain");
		send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		send.putExtra(Intent.EXTRA_SUBJECT, "Uploaded file");
		send.putExtra(Intent.EXTRA_TEXT, share_url);
		// startActivity(Intent.createChooser(send, "Share file link"));

		Intent view = new Intent(Intent.ACTION_VIEW);
		view.setData(Uri.parse(share_url));

		Intent i = Intent.createChooser(send, "Share file link");
		i.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { view });
		startActivity(i);
	}

	private void showQr(String share_url) {
		try {
			QRCodeWriter qrCodeWriter = new QRCodeWriter();
			int size = 256;
			BitMatrix bitMatrix = qrCodeWriter.encode(share_url,
					BarcodeFormat.QR_CODE, size, size);

			Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);

			for (int x = 0; x < size; x++) {
				for (int y = 0; y < size; y++) {
					bitmap.setPixel(x, y,
							bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
				}
			}

			Bitmap share_qr = bitmap;
			AlertDialog.Builder ImageDialog = new AlertDialog.Builder(XferActivity.this);
			ImageView shownImage = new ImageView(XferActivity.this);
			shownImage.setImageBitmap(share_qr);
			shownImage.setLayoutParams(
					new ViewGroup.LayoutParams(
							ViewGroup.LayoutParams.MATCH_PARENT,
							ViewGroup.LayoutParams.WRAP_CONTENT));
			shownImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
			shownImage.setAdjustViewBounds(true);

			ImageDialog.setView(shownImage);

			ImageDialog.show();
		} catch (WriterException e) {
			throw new RuntimeException(e);
		}
	}

	private void need_storage(String exmsg) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
				exmsg.contains("EACCES")) {
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
				"PartyUP! needs additional permissions to read that file, because the app you"
						+ "shared it from is using old APIs.")
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

	private String getSharableUrl(
			String key,
			StringBuilder sharedFilesPaths,
			String sharePw,
			String expiration,
			Uri shareApiUri) throws Exception {

		// Build JSON body
		// {
		// "k": "key",
		// "vp": [
		// "/File_path",
		// "/File_path/1"
		// ],
		// "pw": "sharePw",
		// "exp": "expiration",
		// "perms": ["read"]
		// }
		String jsonBody = String.format(
				"{\"k\":\"%s\",\"vp\":[%s],\"pw\":\"%s\",\"exp\":\"%s\",\"perms\":[\"read\"]}",
				key, sharedFilesPaths, sharePw, expiration);

		HttpURLConnection conn = (HttpURLConnection) (new URL(
				shareApiUri.getScheme() + "://" + shareApiUri.getAuthority() + "/")).openConnection();
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
			throw new RuntimeException("Unable to get share url!");
		}

		BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
		String response = br.readLine();
		conn.disconnect();

		// Parse response: "created share: https://..."
		if (response != null && response.startsWith("created share: "))
			return response.substring(15);

		return "";
	}

	void request_storage() {
		String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
		requestPermissions(new String[] { perm }, 573);
	}

	@Override
	public void onRequestPermissionsResult(int permRequestCode, String perms[],
			int[] grantRes) {
		String perm = Manifest.permission.READ_EXTERNAL_STORAGE;
		if (permRequestCode != 573)
			return;

		for (int a = 0; a < grantRes.length; a++) {
			if (!perms[a].equals(perm))
				continue;

			if (grantRes[a] != PackageManager.PERMISSION_GRANTED)
				return;
		}
	}

	private String getExpiration() {
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

		return expiration;
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

	private String generateRandomKey(int size) {
		// Generate random key
		String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
		SecureRandom random = new SecureRandom();
		StringBuilder key = new StringBuilder();
		for (int i = 0; i < 12; i++)
			key.append(chars.charAt(random.nextInt(chars.length())));
		return key.toString();
	}

}
