package dev.kbmd.android;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import dev.kbmd.android.server.ApiServer;
import dev.kbmd.android.server.Backend;
import dev.kbmd.android.vault.VaultService;
import org.json.JSONObject;

/**
 * Shows the kbmd web UI in a WebView, served by the in-app backend, and connects it to the phone:
 * sharing into the vault, launcher shortcuts, camera and file pickers, downloads, the back gesture and the system bars.
 */
public class MainActivity extends Activity {

    public static final String ACTION_NEW_NOTE = "dev.kbmd.android.NEW_NOTE";
    public static final String ACTION_NEW_DRAWING = "dev.kbmd.android.NEW_DRAWING";
    public static final String ACTION_DAILY = "dev.kbmd.android.DAILY";
    public static final String ACTION_SEARCH = "dev.kbmd.android.SEARCH";
    public static final String ACTION_HABITS = "dev.kbmd.android.HABITS";

    private static final String FILES_AUTHORITY = "dev.kbmd.android.files";
    private static final int REQUEST_PICK_FILES = 1;
    private static final int REQUEST_SAVE_FILE = 2;
    private static final int LIGHT_BARS = 0xffececee;
    private static final int DARK_BARS = 0xff19191c;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> pendingCommands = new ArrayList<>();

    private KbmdApp app;
    private FrameLayout root;
    private WebView web;
    private boolean pageReady;
    private ValueCallback<Uri[]> fileCallback;
    private File cameraFile;
    private String pendingDownloadUrl;
    /** Base64 of a blob: download read in the page, waiting for the "save as" dialog. */
    private volatile String pendingBlob;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (KbmdApp) getApplication();
        pageReady = false;

        root = new FrameLayout(this);
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        setContentView(root);
        applyBars(night);

        // edge to edge: the page gets the whole screen minus the system bars, the camera cutout and the keyboard
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configureWebView();

        if (savedInstanceState == null) {
            handleIntent(getIntent()); // a recreated activity gets the same intent again: not a second share
        }
        worker.execute(() -> {
            try {
                app.start();
                runOnUiThread(this::loadUi);
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> showFatal("kbmd could not start: " + e.getMessage()));
            }
        });
    }

    private void configureWebView() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        web.addJavascriptInterface(new NativeBridge(), "KbmdNative");
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!uri.toString().startsWith(app.origin() + "/")) {
                    openExternally(uri);
                    return true;
                }
                String path = uri.getPath() == null ? "" : uri.getPath();
                if (path.equals("/api/files/raw")) {
                    openVaultFile(uri.getQueryParameter("path"));
                    return true;
                }
                if (path.equals("/api/export") || path.equals("/api/flashcards/export")) {
                    saveAs(uri.toString(), null, null);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // the page's process died (out of memory, or reclaimed in the background): start it over
                // instead of letting the whole app be killed
                root.removeView(view);
                view.destroy();
                web = new WebView(MainActivity.this);
                web.setBackgroundColor(Color.TRANSPARENT);
                root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                pageReady = false;
                configureWebView();
                loadUi();
                return true;
            }
        });
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            if (url.startsWith("blob:")) {
                // the page revokes the blob soon after the click: read it now, save it once a location is chosen
                pendingBlob = null;
                web.evaluateJavascript("fetch(" + JSONObject.quote(url) + ").then(r => r.blob()).then(b => new Promise(done => {"
                        + " const reader = new FileReader(); reader.onload = () => done(reader.result.split(',')[1]); reader.readAsDataURL(b); }))",
                        base64 -> pendingBlob = base64 == null || base64.equals("null") ? "" : base64.replace("\"", ""));
            }
            saveAs(url, name, mimeType);
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return pickFiles(callback, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            }
        });
    }

    private void loadUi() {
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setCookie(app.origin(), ApiServer.SESSION_COOKIE + "=" + app.sessionToken() + "; path=/; SameSite=Lax",
                done -> web.loadUrl(app.origin() + "/"));
    }

    private void showFatal(String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setPadding(48, 48, 48, 48);
        text.setGravity(Gravity.CENTER);
        root.removeAllViews();
        root.addView(text);
    }

    // ---------------------------------------------------------------- bridge to the web UI

    private final class NativeBridge {

        /** The UI has its own light/dark switch; the system bars follow it. */
        @JavascriptInterface
        public void setTheme(boolean dark) {
            runOnUiThread(() -> applyBars(dark));
        }

        /** The UI is up and listening for commands. */
        @JavascriptInterface
        public void ready() {
            runOnUiThread(() -> {
                pageReady = true;
                for (String command : pendingCommands) {
                    web.evaluateJavascript(command, null);
                }
                pendingCommands.clear();
            });
        }
    }

    private void applyBars(boolean dark) {
        root.setBackgroundColor(dark ? DARK_BARS : LIGHT_BARS);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), root);
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);
    }

    /** Runs {@code window.__kbmd.command(name, argument)} in the page, once it is ready. */
    private void command(String name, String argument) {
        String script = "window.__kbmd && window.__kbmd.command(" + JSONObject.quote(name) + ","
                + (argument == null ? "null" : JSONObject.quote(argument)) + ")";
        runOnUiThread(() -> {
            if (pageReady) {
                web.evaluateJavascript(script, null);
            } else {
                pendingCommands.add(script);
            }
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!pageReady) {
            super.onBackPressed();
            return;
        }
        // the UI closes whatever is open on top (dialog, image, side panel); with nothing left, the app steps aside
        web.evaluateJavascript("window.__kbmd ? window.__kbmd.back() : false", handled -> {
            if (!"true".equals(handled)) {
                moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pageReady) {
            web.evaluateJavascript("window.__kbmd && window.__kbmd.flush()", null);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Backend backend = app.backend();
        if (backend != null) {
            backend.sync.syncInBackgroundIfDue();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        command("refresh", null); // a background sync may have changed files
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- intents: sharing and launcher shortcuts

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null || (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return;
        }
        switch (action) {
            case ACTION_NEW_NOTE:
                command("new-note", null);
                break;
            case ACTION_DAILY:
                command("daily", null);
                break;
            case ACTION_SEARCH:
                command("search", null);
                break;
            case ACTION_HABITS:
                command("habits", null);
                break;
            case ACTION_NEW_DRAWING:
                inBackend(backend -> {
                    // straight to an empty canvas: made for the S Pen
                    String path = uniquePath(backend, "Drawings", "Drawing " + timestamp(), ".excalidraw");
                    backend.notes.create(path, "{\"type\":\"excalidraw\",\"version\":2,\"source\":\"kbmd\",\"elements\":[],\"appState\":{},\"files\":{}}");
                    command("open", path);
                });
                break;
            case Intent.ACTION_PROCESS_TEXT:
                CharSequence selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
                importShared(selected == null ? null : selected.toString(), null, new ArrayList<>());
                break;
            case Intent.ACTION_SEND:
            case Intent.ACTION_SEND_MULTIPLE:
                List<Uri> streams = new ArrayList<>();
                ClipData clip = intent.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        if (clip.getItemAt(i).getUri() != null) {
                            streams.add(clip.getItemAt(i).getUri());
                        }
                    }
                }
                importShared(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT), streams);
                break;
            default:
                break;
        }
        intent.setAction(Intent.ACTION_MAIN); // handled: a rotation or restart must not import it again
    }

    /** Shared text becomes a note in Inbox/; shared files go to the attachments and are embedded in that note. */
    private void importShared(String text, String subject, List<Uri> streams) {
        if ((text == null || text.trim().isEmpty()) && streams.isEmpty()) {
            return;
        }
        inBackend(backend -> {
            StringBuilder content = new StringBuilder();
            if (text != null && !text.trim().isEmpty()) {
                content.append(text.trim()).append("\n");
            }
            for (Uri stream : streams) {
                try (InputStream in = getContentResolver().openInputStream(stream)) {
                    if (in != null) {
                        String stored = backend.notes.upload(displayName(stream), null, in);
                        content.append(content.length() > 0 ? "\n" : "").append(ApiServer.uploadedFile(stored).getString("markdown")).append("\n");
                    }
                }
            }
            String title = subject != null && !subject.trim().isEmpty() ? subject.trim() : firstLine(text);
            String name = VaultService.sanitizeFileName(title == null || title.startsWith("http") ? "Shared " + timestamp() : title);
            String path = uniquePath(backend, "Inbox", name.length() > 80 ? name.substring(0, 80).trim() : name, ".md");
            backend.notes.create(path, content.toString());
            command("open", path);
        });
    }

    private interface BackendTask {
        void run(Backend backend) throws Exception;
    }

    private void inBackend(BackendTask task) {
        worker.execute(() -> {
            try {
                task.run(app.start());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "kbmd: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static String uniquePath(Backend backend, String folder, String name, String extension) {
        Path dir = backend.vault.resolve(folder);
        return backend.vault.relativize(VaultService.uniqueSibling(dir, name + extension));
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        String line = text.trim().split("\\R", 2)[0].replaceAll("^#+\\s*", "").trim();
        return line.isEmpty() ? null : line.length() > 60 ? line.substring(0, 60).trim() : line;
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss"));
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            // providers without a name column
        }
        String last = uri.getLastPathSegment();
        return last == null ? "shared" : last;
    }

    // ---------------------------------------------------------------- files in and out of the WebView

    /** File picker for uploads, with the camera offered beside it: a photo goes straight into the note. */
    private boolean pickFiles(ValueCallback<Uri[]> callback, boolean multiple) {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
        }
        fileCallback = callback;

        Intent pick = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        Intent chooser = Intent.createChooser(pick, "Attach");
        try {
            File dir = new File(getCacheDir(), "camera");
            dir.mkdirs();
            cameraFile = new File(dir, "Photo " + timestamp() + ".jpg");
            Uri output = FileProvider.getUriForFile(this, FILES_AUTHORITY, cameraFile);
            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, output)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            camera.setClipData(ClipData.newRawUri("photo", output));
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] {camera});
        } catch (RuntimeException e) {
            cameraFile = null; // no camera option then
        }
        try {
            startActivityForResult(chooser, REQUEST_PICK_FILES);
            return true;
        } catch (ActivityNotFoundException e) {
            fileCallback = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_PICK_FILES && fileCallback != null) {
            List<Uri> picked = new ArrayList<>();
            if (result == RESULT_OK) {
                if (data != null && data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        picked.add(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data != null && data.getData() != null) {
                    picked.add(data.getData());
                } else if (cameraFile != null && cameraFile.length() > 0) {
                    picked.add(FileProvider.getUriForFile(this, FILES_AUTHORITY, cameraFile));
                }
            }
            fileCallback.onReceiveValue(picked.isEmpty() ? null : picked.toArray(new Uri[0]));
            fileCallback = null;
        } else if (request == REQUEST_SAVE_FILE) {
            String url = pendingDownloadUrl;
            pendingDownloadUrl = null;
            if (result == RESULT_OK && data != null && data.getData() != null && url != null) {
                download(url, data.getData());
            }
        }
    }

    /** Exports (vault zip, Anki deck) are saved wherever the user points the system's "save as" dialog. */
    private void saveAs(String url, String suggestedName, String mimeType) {
        String name = suggestedName;
        if (name == null) {
            name = url.contains("/flashcards/") ? "kbmd-anki.txt" : "kbmd-vault-" + java.time.LocalDate.now() + ".zip";
        }
        pendingDownloadUrl = url;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType != null ? mimeType : ApiServer.mimeType(name).replaceAll(";.*", ""))
                .putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(create, REQUEST_SAVE_FILE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can save files", Toast.LENGTH_LONG).show();
        }
    }

    private void download(String url, Uri target) {
        worker.execute(() -> {
            String message = "Saved";
            try {
                InputStream source;
                if (url.startsWith("blob:")) {
                    String blob = pendingBlob;
                    for (int waited = 0; blob == null && waited < 100; waited++) {
                        Thread.sleep(100); // the page is still reading it
                        blob = pendingBlob;
                    }
                    if (blob == null || blob.isEmpty()) {
                        throw new IOException("the page no longer has the file");
                    }
                    source = new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(blob));
                } else {
                    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setRequestProperty("X-Kbmd-Session", app.sessionToken());
                    source = connection.getInputStream();
                }
                try (InputStream in = source; OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) {
                        throw new IOException("cannot write the file");
                    }
                    byte[] buffer = new byte[64 * 1024];
                    for (int count; (count = in.read(buffer)) > 0; ) {
                        out.write(buffer, 0, count);
                    }
                }
            } catch (IOException | RuntimeException | InterruptedException e) {
                message = "Saving failed: " + e.getMessage();
            }
            pendingBlob = null;
            String shown = message;
            runOnUiThread(() -> Toast.makeText(this, shown, Toast.LENGTH_SHORT).show());
        });
    }

    /** PDFs, audio, office files and the like open in whatever app handles them. */
    private void openVaultFile(String path) {
        Backend backend = app.backend();
        if (backend == null || path == null) {
            return;
        }
        try {
            File file = backend.vault.existingFile(path).toFile();
            Uri uri = FileProvider.getUriForFile(this, FILES_AUTHORITY, file);
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, ApiServer.mimeType(file.getName()).replaceAll(";.*", ""))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, file.getName()));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Cannot open " + path + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "No app can open " + uri, Toast.LENGTH_LONG).show();
        }
    }
}
