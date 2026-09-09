package net.chess99.nasfind;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Separate APK/UID: no dependency on Kotlin runtime stripped from the test APK by AGP. */
public class TestOpenActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String text = "unreadable";
        try (InputStream input = getContentResolver().openInputStream(getIntent().getData());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
        sendBroadcast(new Intent("net.chess99.nasfind.TEST_OPENED")
            .setPackage("net.chess99.nasfind.debug").putExtra("text", text));
        finish();
    }
}
