package net.chess99.nasfind;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.OpenableColumns;
import java.io.InputStream;

/** A separate UID verifies the actual activity-result permission grant, not just same-app reads. */
public class TestPickActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null) startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
            .setType(getIntent().getStringExtra("mime"))
            .setPackage("net.chess99.nasfind.debug")
            .addCategory(Intent.CATEGORY_OPENABLE), 1);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        String name = ""; long count = 0; boolean readable = false;
        if (result == RESULT_OK && data != null && data.getData() != null) {
            try (Cursor cursor = getContentResolver().query(data.getData(), new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                 InputStream input = getContentResolver().openInputStream(data.getData())) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) count += n;
                readable = true;
            } catch (Exception ignored) { }
        }
        sendBroadcast(new Intent("net.chess99.nasfind.TEST_PICKED").setPackage("net.chess99.nasfind.debug")
            .putExtra("readable", readable).putExtra("count", count).putExtra("name", name).putExtra("result", result)
            .putExtra("flags", data == null ? 0 : data.getFlags()).putExtra("mime", data == null ? "" : data.getType()));
        finish();
    }
}
