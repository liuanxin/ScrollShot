package io.github.liuanxin.scrollshot;

/** 独立测试 APK 不携带目标应用的 Kotlin 运行库, 使用 Java 接收图片; 不联网、不写相册. */
public class ShareTestActivity extends android.app.Activity {
    @Override
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        String result;
        try {
            android.net.Uri uri = getIntent().getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.class);
            byte[] bytes;
            try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                bytes = input.readAllBytes();
            }
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            Boolean writeDenied = false;
            try (android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "rw")) {
                throw new IllegalStateException("未拒绝写入");
            } catch (SecurityException expected) {
                writeDenied = true;
            }
            result = "PASS " + getContentResolver().getType(uri) + " " + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " " + bytes.length + " bytes, read-only=" + writeDenied;
            bitmap.recycle();
        } catch (Exception error) {
            result = "FAIL " + error;
        }
        try (java.io.FileWriter writer = new java.io.FileWriter(new java.io.File(getFilesDir(), "share-result.txt"))) {
            writer.write(result);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
        android.widget.TextView view = new android.widget.TextView(this);
        view.setText(result);
        view.setTextSize(20f);
        view.setPadding(40, 160, 40, 40);
        setContentView(view);
    }
}
