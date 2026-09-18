package dev.kbmd.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.SecureRandom;

import android.app.Application;
import dev.kbmd.android.server.ApiServer;
import dev.kbmd.android.server.Backend;

/** Owns the backend and the local server, so they outlive activity restarts. */
public class KbmdApp extends Application {

    private static final int FIRST_PORT = 8787;
    private static final int PORT_ATTEMPTS = 10;

    private final Object startLock = new Object();
    private volatile Backend backend;
    private volatile ApiServer server;
    private volatile String sessionToken;
    private volatile int port;

    /** Starts the backend on first use. Indexes the vault, so call it off the main thread. */
    public Backend start() throws IOException {
        Backend started = backend;
        if (started != null) {
            return started;
        }
        synchronized (startLock) {
            if (backend == null) {
                backend = create();
            }
            return backend;
        }
    }

    private Backend create() throws IOException {
        File privateDir = new File(getFilesDir(), "kbmd");
        Backend created = new Backend(vaultDir().toPath(), privateDir.toPath(), readAsset("seed/Welcome.md"));

        sessionToken = new BigInteger(160, new SecureRandom()).toString(32);
        ApiServer.WebAssets assets = path -> {
            try {
                return getAssets().open("web/" + path);
            } catch (FileNotFoundException e) {
                return null;
            }
        };
        IOException failure = null;
        for (int candidate = FIRST_PORT; candidate < FIRST_PORT + PORT_ATTEMPTS; candidate++) {
            // the first free port; a stable one keeps the UI's saved tabs and theme (they are stored per origin)
            ApiServer attempt = new ApiServer(candidate, created, assets, sessionToken, getCacheDir());
            try {
                attempt.start(30_000, true);
                server = attempt;
                port = candidate;
                break;
            } catch (IOException e) {
                failure = e;
            }
        }
        if (server == null) {
            throw failure == null ? new IOException("No free port") : failure;
        }
        return created;
    }

    /** The notes live in the app's folder on shared storage: no permission needed, and visible over USB. */
    public File vaultDir() {
        File external = getExternalFilesDir(null);
        return new File(external != null ? external : getFilesDir(), "vault");
    }

    /** Null until {@link #start()} has finished; never blocks. */
    public Backend backend() {
        return backend;
    }

    public String origin() {
        return "http://127.0.0.1:" + port;
    }

    public String sessionToken() {
        return sessionToken;
    }

    private byte[] readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
