# Building and running kbmd

kbmd is one Spring Boot application. The React UI is compiled by the Maven build and packed into the jar,
so the result is a single file: `target/kbmd-<version>.jar`.

## Requirements

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 25 or newer | `JAVA_HOME` must point to it when building. Any distribution (Corretto, Temurin, ...). |
| Node.js + npm | 22 or newer | Only needed for building, not for running the jar. |
| Maven | - | Not needed: the wrapper (`mvnw`) downloads Maven 3.9 on first use. |
| Docker | optional | Alternative that needs none of the above. |

Internet access is needed on the first build (Maven and npm dependencies).

## Build

### Windows

```powershell
.\build.ps1              # UI + jar + tests
.\build.ps1 -SkipTests   # faster
```

`build.ps1` checks `JAVA_HOME`; if it is missing or older than 25 it picks the newest JDK 25+ from
`%USERPROFILE%\.jdks` (where IntelliJ IDEA installs JDKs). If scripts are blocked on your machine, run it as
`powershell -ExecutionPolicy Bypass -File .\build.ps1`.

Doing the same by hand:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\corretto-25.0.3"   # your JDK 25 path
.\mvnw.cmd package
```

### Linux / macOS

```sh
export JAVA_HOME=/path/to/jdk-25
./mvnw package                 # add -DskipTests to go faster
```

### What the build does

1. `npm install` and `npm run build` in `frontend/` (type-check, then Vite bundles the UI into `target/classes/static`)
2. compiles the Java sources and runs the tests (they use temporary folders and a local Git repository, no network)
3. packages everything into `target/kbmd-0.1.0-SNAPSHOT.jar` (about 50 MB)

Useful switches:

| Command | Effect |
| --- | --- |
| `mvnw package -DskipTests` | skip the tests |
| `mvnw package -DskipFrontend=true` | backend only; reuses the UI already in `target/classes/static` (do not combine with `clean`) |
| `mvnw clean package` | from scratch |
| `mvnw test -DskipFrontend=true` | just the backend tests |

On Windows the jar cannot be replaced while the app is running from it: stop kbmd before rebuilding.

## Run

### Windows

```powershell
.\run.ps1                                 # vault: %USERPROFILE%\kbmd-vault
.\run.ps1 C:\Users\me\Notes               # your markdown folder
.\run.ps1 C:\Users\me\Notes -Port 9000
```

### Any platform

```sh
java -jar target/kbmd-0.1.0-SNAPSHOT.jar --kbmd.vault=/path/to/notes
```

Then open <http://localhost:8787>. Stop with `Ctrl+C`. `java` must be version 25+ (`java -version`).

The vault folder is created if it does not exist, and an empty one gets a `Welcome.md`. An existing Obsidian vault
can be used as is.

### Options

Pass as `--name=value` after the jar, or as environment variables.

| Option | Environment variable | Default | |
| --- | --- | --- | --- |
| `--kbmd.vault` | `KBMD_VAULT` | `~/kbmd-vault` | the markdown folder |
| `--kbmd.attachments-dir` | `KBMD_ATTACHMENTS_DIR` | `attachments` | upload folder, relative to the vault |
| `--server.port` | `SERVER_PORT` | `8787` | |
| `--server.address` | `SERVER_ADDRESS` | `127.0.0.1` | `0.0.0.0` makes it reachable from other machines. There is **no login**: only do that on a network you trust |

Uploads are limited to 100 MB per file (`--spring.servlet.multipart.max-file-size=...`).

## Run with Docker

Builds inside Docker, so only Docker is required. The argument is the local markdown folder.

```powershell
.\kbmd.ps1 C:\Users\me\Notes              # Windows;  -Port 9000, -Rebuild
```

```sh
./kbmd.sh ~/Notes                         # Linux / macOS;  ./kbmd.sh ~/Notes 9000,  REBUILD=1 ./kbmd.sh ~/Notes
```

The image is built on first use (a few minutes) and reused afterwards; use `-Rebuild` / `REBUILD=1` after changing
the source. Without the scripts:

```sh
docker build -t kbmd .
docker run --rm -p 127.0.0.1:8787:8787 -v "/path/to/notes:/vault" kbmd
```

On Linux add `--user "$(id -u):$(id -g)"` so new notes belong to you rather than root.

## Development mode

Two processes, with hot reload for the UI:

```sh
./mvnw spring-boot:run -DskipFrontend=true      # API on http://localhost:8787
cd frontend && npm install && npm run dev        # UI on http://localhost:5173 (proxies /api to 8787)
```

To use another vault while developing: `./mvnw spring-boot:run -DskipFrontend=true "-Dspring-boot.run.arguments=--kbmd.vault=/tmp/dev-vault"`.

In an IDE, run `dev.kbmd.KbmdApplication` with a JDK 25 SDK. The UI is only served from the IDE run if it was built at
least once (`npm run build`, or any full Maven build); otherwise use the Vite dev server above.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `release version 25 not supported` | Maven runs on an older JDK: point `JAVA_HOME` to JDK 25 (or use `build.ps1`). |
| `UnsupportedClassVersionError` when starting | `java` on the PATH is older than 25: use the full path to a JDK 25 `java`, or `run.ps1`. |
| `Port 8787 was already in use` | another kbmd is running, or pick another port: `--server.port=9000`. |
| Build cannot write / delete the jar (Windows) | stop the running app first. |
| `npm-build` exits with `-1073740791` (Windows) | happens when the build's error output is discarded (`2>$null`, `2>NUL`); run it without that redirect. |
| `npm warn ERESOLVE overriding peer dependency` | harmless: a dependency of Excalidraw still declares React 18. |
| Page is blank / 404 on `/` after an IDE run | the UI has not been built yet, see *Development mode*. |
