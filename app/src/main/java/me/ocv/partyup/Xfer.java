package me.ocv.partyup;

import me.ocv.partyup.XferActivity;

import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Queue;
import java.util.function.Consumer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;

public class Xfer {
	private Queue<CustomFile> files = new Queue<CustomFile>();
	private String serverUrl;
	private String password;
	private Consumer<Error> onError;
	private Consumer<Integer> onProgress;
	private Runnable preInit;

	Xfer(CustomFile[] files, String serverUrl, String password) {
		for (int i = 0; i < files.length; i++) {
			this.files.add(files[i]);
		}
		this.serverUrl = serverUrl;
		this.password = password;
	}

	private void setUp() {
		if (!serverUrl.startsWith("http"))
			serverUrl = "http://" + serverUrl;

		if (!serverUrl.endsWith("/"))
			serverUrl += "/";

		if (serverUrl.contains("%")) {
			String[] dtc = "%Y %q %m %d %j %H %M %S".split(" ");

			SimpleDateFormat sdf = new SimpleDateFormat("yyyy Q MM dd DDD HH mm ss", Locale.US);
			sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
			String[] dtp = sdf.format(new Date()).split(" ");

			for (int a = 0; a < dtc.length; a++)
				serverUrl = serverUrl.replace(dtc[a], dtp[a]);
		}
	}

	private HttpURLConnection makeConnection(CustomFile customFile) {
		String fullUrl = this.serverUrl;

		fullUrl += URLEncoder.encode(customFile.name, "UTF-8");
		URL url = new URL(fullUrl);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();

		if (this.password != null)
			conn.setRequestProperty("PW", this.password);

		conn.setDoOutput(true);
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

	public onError(Consumer<Error> oE) {
		this.onError = oE;
	}

	public onProgress(Consumer<Integer> oP) {
		this.onProgress = oE;
	}

	public preInit(Runnable pI) {
		this.preInit = pI;
	}

	public addFile(CustomFile file) {
		this.files.add(file);
	}

	private boolean upload(CustomFile cs) {
		HttpURLConnection conn = makeConnection(cs);
		conn.setRequestMethod("POST");

		if (!cs.mime.equals("text/plain")) { // File upload (AKA PUT)
			conn.setFixedLengthStreamingMode(cs.size);
			conn.setRequestProperty("Content-Type", "application/octet-stream");
			conn.connect();
			this.preInit.run();

			OutputStream os = conn.getOutputStream();
			InputStream ins = getContentResolver().openInputStream(cs.handle);
			MessageDigest md = MessageDigest.getInstance("SHA-512");

			byte[] buf = new byte[128 * 1024];
			long bytesDone = 0;

			while (true) {
				int n = ins.read(buf);
				if (n <= 0)
					break;

				bytesDone += n;
				os.write(buf, 0, n);
				md.update(buf, 0, n);
				this.onProgress.accept(bytesDone);
			}

			os.flush();
			int rc = conn.getResponseCode();
			if (rc >= 300) {
				this.onError.accept(new Error("Server error " + rc + ":\n" +
						this.read_err(conn)));
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
				cs.share_url = lines[3];
			else
				cs.share_url = cs.full_url.split("\\?")[0];
		} else {
			if (cs.content == null) {
				conn.disconnect();
				return false;
			}

			byte[] body = ("msg=" + URLEncoder.encode(cs.content, "UTF-8")).getBytes(StandardCharsets.UTF_8);
			conn.setRequestMethod("POST");
			conn.setFixedLengthStreamingMode(body.length);
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
			conn.connect();

			OutputStream os = conn.getOutputStream();
			os.write(body);
			os.flush();
			int rc = conn.getResponseCode();
			if (rc >= 300) {
				this.onError.accept(new Error("Server error " + rc + ":\n" + read_err(conn)));
				conn.disconnect();
				return false;
			}
		}

		conn.disconnect();
		return true;
	}

	public void startUpload() {
		Iterator iterator = this.files.iterator();

		while (iterator.hasNext()) {
			upload(iterator.next());
		}
	}

}
