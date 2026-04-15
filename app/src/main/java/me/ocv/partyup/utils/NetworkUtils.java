package me.ocv.partyup.utils;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;

public final class NetworkUtils {
    private NetworkUtils() {
    }

    private static final int BUFFER_SIZE = 1024 * 1024;

    @FunctionalInterface
    public interface ResponseOk {
        boolean check(int status);
    }

    @NonNull
    public static StringBuilder readConnection(@NonNull HttpURLConnection connection, @NonNull ResponseOk ok) throws IOException {
        InputStream is;
        int status = connection.getResponseCode();

        if (!ok.check(status) || status >= 400) {
            is = connection.getErrorStream();
        } else {
            is = connection.getInputStream();
        }

        return readInputStream(is);
    }

    @NonNull
    public static StringBuilder readConnection(@NonNull HttpURLConnection connection) throws IOException {
        InputStream is;
        if (connection.getResponseCode() >= 400) {
            is = connection.getErrorStream();
        } else {
            is = connection.getInputStream();
        }

        return readInputStream(is);
    }

    @NonNull
    public static StringBuilder readInputStream(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        if (inputStream == null) return builder;

        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream))) {
            char[] buffer = new char[BUFFER_SIZE];

            while (bufferedReader.read(buffer) > 0) {
                builder.append(buffer);
            }

            return builder;
        }
    }
}
