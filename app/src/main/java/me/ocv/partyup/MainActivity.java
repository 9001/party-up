package me.ocv.partyup;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String txt = "<p><em>Hello from <a href=\"https://github.com/9001/party-up\">Party UP!</a> <b>version " + BuildConfig.VERSION_NAME + "</b></em></p>" +
                "<p>This app lets you upload files (images, videos) to a <a href=\"https://github.com/9001/copyparty#quickstart\">copyparty</a> server.</p>" +
                "<hr />" +
                "<p><b>Use your favorite gallery app to open a picture or video you'd like to upload, then hit the share button and select \"Party UP!\" \uD83C\uDF89</b></p>" +
                "<p>You can also share things like youtube videos; the app will then upload a message with the link. The copyparty server can be configured to log these for later viewing.</p>" +
                "<p>Funfact: You can run the copyparty server itself on any device where Python is available -- and thanks to <a href=\"https://f-droid.org/en/packages/com.termux/\">Termux</a> this also means Android phones :^)</p>";

        TextView tv = ((TextView)findViewById(R.id.textView4));
        tv.setText(Html.fromHtml(txt, Html.FROM_HTML_MODE_LEGACY));
        tv.setMovementMethod(LinkMovementMethod.getInstance());

        ((Button)findViewById(R.id.button)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(i);
            }
        });
    }
}
