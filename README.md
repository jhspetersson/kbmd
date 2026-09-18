# kbmd

A self-hosted, Obsidian-style knowledge base. Notes are plain Markdown files in a local folder;
the app is a Spring Boot server with a React UI, shipped as a single jar or Docker image.

- **Local Markdown files** – the folder is the database; works with an existing Obsidian vault
- **Wikilinks** – `[[Note]]`, `[[Note#Heading|label]]`, `![[embed.png|300]]`, `[[` autocomplete, backlinks,
  links are rewritten when a note is renamed or moved
- **Graph view** – force-directed graph of notes and links, with filter, zoom, drag
- **Indexed search** – Lucene full-text index: prefix matching while typing, `"phrases"`, `tag:name`, `path:folder`
- **Editor** – CodeMirror 6, editor / split / reading modes, autosave, tabs, outline, tags, daily notes, quick switcher
- **Uploads** – paste, drag & drop or pick files; stored in `attachments/` and embedded automatically
- **Diagrams** – ` ```mermaid ` blocks for text diagrams; free-form drawings with the built-in Excalidraw editor
  (`*.excalidraw` files, embed with `![[Drawing.excalidraw]]`)
- **Image galleries** – ` ```gallery ` block listing images or a whole folder; lightbox with keyboard navigation
- **Flashcards (Anki-style)** – notes tagged `#flashcards` or `#flashcards/deck` become decks: `Q::A`, `A:::B`
  (both directions), multi-line cards split by a `?` line, `==cloze==`. Spaced-repetition review (SM-2, Again / Hard /
  Good / Easy), progress kept in `<vault>/.flashcards.json` (synced with Git), export to Anki's text import format
- **Sync with GitHub / GitLab** – two-way Git sync over HTTPS using an access token, manual or on a timer
- **Export** – the whole vault as a zip

## Run with Docker (one command)

```sh
./kbmd.sh ~/Notes              # Linux / macOS
.\kbmd.ps1 C:\Users\me\Notes   # Windows
```

The argument is the local Markdown folder (created if missing); an optional second argument / `-Port` changes the
port. The image is built on first use. Then open <http://localhost:8787>.

Without the script it is still one command:

```sh
docker run --rm -p 127.0.0.1:8787:8787 -v "/path/to/notes:/vault" $(docker build -q .)
```

## Run without Docker

Requires JDK 25 and Node 22+ (`JAVA_HOME` must point to the JDK 25). Full instructions, options and
troubleshooting: [BUILDING.md](BUILDING.md).

```sh
./mvnw package                      # builds the UI and the jar, runs the tests
java -jar target/kbmd-*.jar --kbmd.vault=/path/to/notes
```

On Windows: `.\build.ps1`, then `.\run.ps1 C:\path\to\notes` (both find a JDK 25 by themselves).

| Option | Default | |
| --- | --- | --- |
| `--kbmd.vault` (env `KBMD_VAULT`) | `~/kbmd-vault` | the Markdown folder |
| `--kbmd.attachments-dir` | `attachments` | where uploads go, relative to the vault |
| `--server.port` | `8787` | |
| `--server.address` | `127.0.0.1` | there is **no login**, so do not expose the app to a network you do not trust |

## Sync

Open the sync dialog (bottom of the left ribbon), choose GitHub or GitLab, enter the repository (`owner/repo` or a
full HTTPS URL, self-hosted GitLab works too) and an access token:

- GitHub: fine-grained token with *Contents: read and write* (or a classic token with `repo`)
- GitLab: personal/project access token with `write_repository`

Each sync commits local changes, merges the remote branch and pushes. If the same file changed on both sides, your
version stays in place and the remote one is saved next to it as `Name (remote conflict <date>).md`.
The token lives in `<vault>/.kbmd/sync.json`, which is git-ignored and never served by the API.

## Android

`android/` holds the phone app: the same UI and features, with the backend running inside the app and sync going
through the GitHub / GitLab API. Build the APK with `androiduild.ps1`; see [android/README.md](android/README.md).

## Development

```sh
./mvnw spring-boot:run -DskipFrontend=true   # API on :8787
cd frontend && npm install && npm run dev     # UI on :5173 with hot reload, proxies /api
```

```
src/main/java/dev/kbmd
  vault/      file operations (path-safe), rename with link rewriting, zip export
  markdown/   CommonMark + GFM + wikilinks, embeds, tags, mermaid and gallery blocks
  index/      link graph, backlinks, tags, link resolution, Lucene search
  sync/       Git sync (JGit) with token authentication
  web/        REST controllers
frontend/src  React 19 + TypeScript + Vite
```

Deleted files go to `<vault>/.trash`. Hidden folders (`.git`, `.obsidian`, `.kbmd`, `.trash`) are never listed or served.
