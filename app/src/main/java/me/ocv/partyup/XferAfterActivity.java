package me.ocv.partyup;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.Serializable;

public class XferAfterActivity extends AppCompatActivity {
    public static final String ACTION_KEY = "action";
    public static final String SHARE_URL_KEY = "share_url";

    public enum ActionType implements Serializable {
        ACTION_COPY,
        ACTION_SHARE,
        ACTION_SHOW_QR
    }

    private static final String TAG = "TransferAfter";
    private static final int QR_SIZE = 256;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent == null) {
            finishAndRemoveTask();
            return;
        }

        ActionType type = (ActionType) intent.getSerializableExtra(ACTION_KEY);
        String shareUrl = intent.getStringExtra(SHARE_URL_KEY);

        if (type == null || shareUrl == null || shareUrl.isBlank()) {
            finishAndRemoveTask();
            return;
        }

        // Ensure URL has scheme (important for share/open)
        if (!shareUrl.startsWith("http://") && !shareUrl.startsWith("https://")) {
            shareUrl = "http://" + shareUrl;
        }

        switch (type) {
            case ACTION_COPY:
                copyLink(shareUrl);
                break;
            case ACTION_SHARE:
                shareLink(shareUrl);
                break;
            case ACTION_SHOW_QR:
                showQRLink(shareUrl);
                break;
        }
    }

    private void copyLink(String shareUrl) {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData cd = ClipData.newPlainText(getString(R.string.clipboard_label), shareUrl);
        cb.setPrimaryClip(cd);

        Toast.makeText(this, R.string.copy_success, Toast.LENGTH_SHORT).show();

        finishAndRemoveTask();
    }

    private void shareLink(String shareUrl) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_extra_text));
        send.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_extra_text) + "\n" + shareUrl);

        Intent chooser = Intent.createChooser(send, getString(R.string.share_link_title));
        getApplicationContext().startActivity(chooser);

        finishAndRemoveTask();
    }

    private void showQRLink(String shareUrl) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(
                    shareUrl,
                    BarcodeFormat.QR_CODE,
                    QR_SIZE,
                    QR_SIZE
            );

            Bitmap shareQr = Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.RGB_565);

            for (int x = 0; x < QR_SIZE; x++) {
                for (int y = 0; y < QR_SIZE; y++) {
                    shareQr.setPixel(x, y,
                            bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE
                    );
                }
            }

            ImageView shownImage = new ImageView(this);
            shownImage.setImageBitmap(shareQr);
            shownImage.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            shownImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            shownImage.setAdjustViewBounds(true);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setView(shownImage)
                    .setCancelable(true)
                    .setOnDismissListener(d -> finishAndRemoveTask())
                    .create();

            dialog.show();

        } catch (WriterException e) {
            Toast.makeText(this, e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "Unable to show QR", e);
            finishAndRemoveTask();
        }
    }
}
