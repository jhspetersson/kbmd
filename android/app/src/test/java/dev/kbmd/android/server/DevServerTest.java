package dev.kbmd.android.server;

import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Not a test: runs the app's backend with the built web UI on the desktop, to try both in a browser without a phone.
 * <pre>
 *   set KBMD_DEV_SERVER=8790  &amp;&amp;  gradlew testDebugUnitTest --tests *DevServerTest
 * </pre>
 * then open http://127.0.0.1:8790 with the cookie {@code kbmd_session=dev}. The vault is build/dev-vault.
 * Stops after KBMD_DEV_MINUTES (default 10).
 */
public class DevServerTest {

    @Test
    public void serve() throws Exception {
        String port = System.getenv("KBMD_DEV_SERVER");
        assumeTrue("set KBMD_DEV_SERVER to a port to run the dev server", port != null && !port.isEmpty());
        String minutes = System.getenv("KBMD_DEV_MINUTES");

        Path web = Paths.get("src/main/assets/web").toAbsolutePath();
        Path work = Paths.get("build/dev-vault").toAbsolutePath();
        byte[] welcome = Files.readAllBytes(Paths.get("src/main/assets/seed/Welcome.md"));
        Backend backend = new Backend(work.resolve("vault"), work.resolve("private"), welcome);
        File cache = Files.createDirectories(work.resolve("cache")).toFile();
        ApiServer server = new ApiServer(Integer.parseInt(port.trim()), backend,
                path -> Files.isRegularFile(web.resolve(path)) ? Files.newInputStream(web.resolve(path)) : null, "dev", cache);
        server.start(30_000, true);
        System.out.println("kbmd dev server on http://127.0.0.1:" + port.trim() + " (" + new String("cookie kbmd_session=dev".getBytes(), StandardCharsets.UTF_8) + ")");
        Thread.sleep((minutes == null ? 10 : Long.parseLong(minutes.trim())) * 60_000L);
        server.stop();
    }
}
