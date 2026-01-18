package me.ocv.partyup;

import me.ocv.partyup.XferActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Queue;
import java.util.function.Consumer;
import java.net.HttpURLConnection;
import android.net.Uri;
import java.net.URL;
import java.net.URLEncoder;
import android.util.Log;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;
import android.content.Context;

class UploadProgress {
	public long delta;
	public long done;
	public long total;
}

public class Uploader {

	private String serverUrl;
	private String password;
	private Consumer<Error> onError = (err) -> {
		Log.e("Uploader", "Upload error: " + err.toString());
	};
	private Consumer<UploadProgress> onProgress = (progress) -> {
		Log.i("Uploader",
				String.format("Uploaded: %d/%d bytes (delta=%d)", progress.done, progress.total, progress.delta));
	};
	private Runnable onInit = () -> {
		Log.d("Uploader", "Uploading started!");
	};
	private Runnable onComplete = () -> {
		Log.d("Uploader", "Uploading completed!");
	};
	private Context context;

	private boolean uploadFile(CustomFile cf, HttpURLConnection conn) throws Exception {
		if (cf.size == null || cf.size == 0) {
			Log.e("Uploader", String.format("Bad media file: %s", cf));
			conn.disconnect();
			return false;
		}
		Log.d("Uploader", String.format("Identified '%s' as a file (%s) with size: %d", cf.name, cf.mime, cf.size));

		conn.setRequestMethod("PUT");
		conn.setFixedLengthStreamingMode(cf.size);
		conn.setRequestProperty("Content-Type", "application/octet-stream");
		conn.connect();
		this.onInit.run();

		OutputStream os = conn.getOutputStream();
		InputStream ins = this.context.getContentResolver().openInputStream(cf.handle);
		MessageDigest md = MessageDigest.getInstance("SHA-512");

		byte[] buf = new byte[128 * 1024];

		UploadProgress up = new UploadProgress();
		up.total = cf.size;
		up.done = 0;
		up.delta = 0;

		while (true) {
			int n = ins.read(buf);
			if (n == -1)
				break;

			os.write(buf, 0, n);
			md.update(buf, 0, n);

			up.delta = n;
			up.done += n;
			this.onProgress.accept(up);
			Log.d("Uploader", String.format("[Progress] delta, total, done: %d, %d, %d", up.delta, up.total, up.done));
		}

		os.flush();
		int rc = conn.getResponseCode();
		if (rc >= 300) {
			this.onError.accept(new Error("Server error " + rc + ":\n" +
					this.read_err(conn)));
			conn.disconnect();
			return false;
		}
		return uploadSuccess(md, conn, cf);
	}

	private boolean uploadText(CustomFile cf, HttpURLConnection conn) throws Exception {
		if (cf.content == null) {
			Log.e("Uploader", String.format("Bad text file: %s", cf));
			conn.disconnect();
			return false;
		}

		Log.d("Uploader", "Creating body...");

		byte[] body = ("msg=" + URLEncoder.encode(cf.content, "UTF-8"))
				.getBytes(StandardCharsets.UTF_8);

		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
		conn.connect();

		this.onInit.run();
		Log.d("Uploader", "[POST] Body creation successful: " + body.length);

		UploadProgress up = new UploadProgress();
		up.delta = (long) body.length;
		up.total = (long) body.length;
		up.done = (long) body.length;

		Log.d("Uploader", String.format("[Progress] delta, total, done: %d, %d, %d", up.delta, up.total, up.done));

		OutputStream os = conn.getOutputStream();
		os.write(body);
		os.flush();
		this.onProgress.accept(up);

		int rc = conn.getResponseCode();
		if (rc >= 300) {
			this.onError.accept(new Error("Server error " + rc + ":\n" + read_err(conn)));
			conn.disconnect();
			return false;
		}

		String[] serverResponse = readServerResponse(conn);
		if (serverResponse != null && serverResponse.length > 0) {
			cf.share_url = String.join("\n", serverResponse).trim();
		} else {
			cf.share_url = "Server not happy!";
		}

		return true;
	}

	public boolean upload(CustomFile cf) throws Exception {
		HttpURLConnection conn = makeConnection(cf);
		cf.full_url = conn.getURL().toString();

		Log.d("Uploader", String.format("Uploading started of file: %s", cf));
		boolean uploadSuccess = false;

		if (cf.mime.equals("text/plain")) {
			// Text (aka links) Upload POST
			uploadSuccess = this.uploadText(cf, conn);
		} else {
			// Files Upload PUT
			uploadSuccess = this.uploadFile(cf, conn);
		}

		if (uploadSuccess)
			this.onComplete.run();

		Log.i("Uploader", String.format("Uploader result: %s", uploadSuccess));
		conn.disconnect();
		return uploadSuccess;
	}

	private HttpURLConnection makeConnection(CustomFile customFile) throws Exception {
		String base = serverUrl.endsWith("/") ? serverUrl : serverUrl + "/";
		String encodedName = customFile.mime.equals("text/plain") ? "" : Uri.encode(customFile.name, "/");
		String fullUrl = base + encodedName;

		URL url = new URL(fullUrl);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();

		if (this.password != null)
			conn.setRequestProperty("PW", this.password);

		conn.setDoOutput(true);
		Log.d("Uploader", "Sending to: " + fullUrl);
		return conn;
	}

	private String read_err(HttpURLConnection conn) {
		try {
			byte[] buf = new byte[1024];
			int n = Math.max(0, conn.getErrorStream().read(buf));
			return new String(buf, 0, n, StandardCharsets.UTF_8);
		} catch (Exception ex) {
			return ex.toString();
		}
	}

	private String[] readServerResponse(HttpURLConnection conn) throws Exception {
		BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));

		ArrayList<String> linesList = new ArrayList<>();
		String line;
		while ((line = br.readLine()) != null) {
			linesList.add(line);
		}

		return linesList.toArray(new String[0]);
	}

	private boolean uploadSuccess(MessageDigest md, HttpURLConnection conn, CustomFile cf) throws Exception {
		String sha = "";
		byte[] bsha = md.digest();
		for (int a = 0; a < 28; a++)
			sha += String.format("%02x", bsha[a]);

		String[] lines = readServerResponse(conn);

		if (lines.length < 3) {
			this.onError.accept(new Error("SERVER ERROR:\n" + lines[0]));
			return false;
		}

		if (lines[2].indexOf(sha) != 0) {
			this.onError.accept(
					new Error("ERROR:\nFile got corrupted during the upload;\n\n" + lines[2] + " expected\n" + sha
							+ " from server"));
			return false;
		}

		if (lines.length > 3 && !lines[3].isEmpty())
			cf.share_url = lines[3];
		else
			cf.share_url = cf.full_url.split("\\?")[0];

		return true;
	}

	public void setContext(Context con) {
		this.context = con;
	}

	public void setPassword(String pass) {
		this.password = pass;
	}

	public void setOnProgress(Consumer<UploadProgress> onProc) {
		this.onProgress = onProc;
	}

	public void setOnError(Consumer<Error> onErr) {
		this.onError = onErr;
	}

	public void setOnInit(Runnable onInit) {
		this.onInit = onInit;
	}

	public void setOnComplete(Runnable onComplete) {
		this.onComplete = onComplete;
	}

	public void setServerUrl(String url) {
		this.serverUrl = url;

		if (!this.serverUrl.startsWith("http"))
			this.serverUrl = "http://" + this.serverUrl;

		if (!this.serverUrl.endsWith("/"))
			this.serverUrl += "/";

		if (this.serverUrl.contains("%")) {
			String[] dtc = "%Y %q %m %d %j %H %M %S".split(" ");

			SimpleDateFormat sdf = new SimpleDateFormat("yyyy Q MM dd DDD HH mm ss", Locale.US);
			sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
			String[] dtp = sdf.format(new Date()).split(" ");

			for (int a = 0; a < dtc.length; a++)
				this.serverUrl = this.serverUrl.replace(dtc[a], dtp[a]);
		}

		Log.d("Uploader", "Server Url: " + this.serverUrl);
	}

	public String getServerUrl() {
		return this.serverUrl;
	}

	public String getPassword() {
		return this.password;
	}

}
