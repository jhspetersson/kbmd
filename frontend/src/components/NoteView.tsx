import { redo, undo } from '@codemirror/commands';
import { openSearchPanel } from '@codemirror/search';
import type { EditorView } from '@codemirror/view';
import {
  Bold,
  Code,
  GitBranch,
  GraduationCap,
  Heading,
  Images,
  Italic,
  Link2,
  List,
  ListChecks,
  Paperclip,
  Redo2,
  Scissors,
  Search,
  Table,
  Undo2,
} from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError, titleOf, type NoteRef } from '../api';
import { MarkdownEditor } from './MarkdownEditor';
import { Preview } from './Preview';
import { ScrollKeeper } from './ScrollKeeper';

export type ViewMode = 'edit' | 'split' | 'preview';

interface Props {
  path: string;
  mode: ViewMode;
  dark: boolean;
  anchor: { text: string; tick: number } | null;
  noteNames: NoteRef[];
  onOpenLink: (path: string, target: string, anchor: string | null) => void;
  onTag: (tag: string) => void;
  onLightbox: (images: { src: string; caption: string }[], index: number) => void;
  onContent: (content: string) => void;
  onSaved: () => void;
  onFilesChanged: () => void;
  onStatus: (status: string) => void;
  onError: (message: string) => void;
  registerFlush: (flush: (() => Promise<void>) | null) => void;
  registerEditor: (view: EditorView | null) => void;
}

const MERMAID_TEMPLATE = '```mermaid\ngraph TD\n    A[Start] --> B{Decision}\n    B -->|Yes| C[Do it]\n    B -->|No| D[Skip]\n```\n';
const GALLERY_TEMPLATE = '```gallery\nattachments/\n```\n';
const TABLE_TEMPLATE = '| Column | Column |\n| --- | --- |\n|  |  |\n';
// a task line, possibly inside a blockquote
const TASK = /^((?:\s*>)*\s*(?:[-*+]|\d+[.)])\s+\[)([ xX])(\])/;

/**
 * Flips a task list checkbox in the source: the one on {@code line} when the server said which, otherwise the n-th
 * task line (which can be off when tasks sit inside blockquotes or code).
 */
function toggleTask(content: string, index: number, line: number | null): string {
  const lines = content.split('\n');
  const flip = (i: number) => {
    lines[i] = lines[i].replace(TASK, (_m, open: string, mark: string, close: string) => open + (mark === ' ' ? 'x' : ' ') + close);
  };
  if (line !== null && line >= 1 && line <= lines.length && TASK.test(lines[line - 1])) {
    flip(line - 1);
    return lines.join('\n');
  }
  let fenced = false;
  let seen = 0;
  for (let i = 0; i < lines.length; i++) {
    if (/^\s*(```|~~~)/.test(lines[i])) fenced = !fenced;
    if (fenced || !TASK.test(lines[i])) continue;
    if (seen++ === index) {
      flip(i);
      break;
    }
  }
  return lines.join('\n');
}

export function NoteView(props: Props) {
  const { path, mode, dark, onContent, onSaved, onStatus, onError } = props;
  const [content, setContent] = useState<string | null>(null);
  const [html, setHtml] = useState('');
  const [dragging, setDragging] = useState(false);

  const view = useRef<EditorView | null>(null);
  /** Runs a CodeMirror command (undo, find…) on the editor and keeps the focus there. */
  const command = (run: (target: EditorView) => boolean) => {
    if (!view.current) return;
    run(view.current);
    view.current.focus();
  };
  const fileInput = useRef<HTMLInputElement>(null);
  const latest = useRef('');
  const dirty = useRef(false);
  const modified = useRef<number | undefined>(undefined);
  const saveTimer = useRef<number | undefined>(undefined);
  const firstUnsaved = useRef<number | undefined>(undefined);
  const inFlight = useRef<Promise<void> | null>(null);

  /** Saves the latest content. Saves run one after another, so each carries the timestamp the previous one returned. */
  const flush = useCallback(
    (keepalive = false): Promise<void> => {
      window.clearTimeout(saveTimer.current);
      const previous = inFlight.current ?? Promise.resolve();
      const run = previous.then(async () => {
        if (!dirty.current) return;
        dirty.current = false;
        firstUnsaved.current = undefined;
        const saving = latest.current;
        try {
          onStatus('Saving…');
          modified.current = (await api.save(path, saving, modified.current, keepalive)).modified;
        } catch (e) {
          if (e instanceof ApiError && e.status === 409) {
            if (window.confirm(`"${path}" changed on disk (sync or another program).

OK: keep your version
Cancel: load the version from disk`)) {
              modified.current = (await api.save(path, saving)).modified;
            } else {
              const doc = await api.read(path);
              modified.current = doc.modified;
              latest.current = doc.content;
              dirty.current = false;
              setContent(doc.content);
            }
          } else {
            dirty.current = true;
            onStatus('Not saved');
            onError(`Could not save ${path}: ${(e as Error).message}`);
            return;
          }
        }
        onStatus(dirty.current ? 'Unsaved changes' : 'Saved');
        onSaved();
      });
      inFlight.current = run;
      return run.finally(() => {
        if (inFlight.current === run) inFlight.current = null;
      });
    },
    [path, onStatus, onSaved, onError],
  );

  // closing or reloading the tab: a keepalive request outlives the page
  useEffect(() => {
    const onHide = () => void flush(true);
    window.addEventListener('pagehide', onHide);
    return () => window.removeEventListener('pagehide', onHide);
  }, [flush]);

  useEffect(() => {
    let cancelled = false;
    api
      .read(path)
      .then((doc) => {
        if (cancelled) return;
        modified.current = doc.modified;
        latest.current = doc.content;
        setContent(doc.content);
        onContent(doc.content);
        onStatus('Saved');
      })
      .catch((e: Error) => onError(e.message));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path]);

  useEffect(() => {
    props.registerFlush(flush);
    if (view.current) props.registerEditor(view.current); // the editor outlives a re-run of this effect
    return () => {
      props.registerFlush(null);
      props.registerEditor(null);
      void flush();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [flush]);

  // the preview is rendered by the server, which knows how every link resolves
  useEffect(() => {
    if (content === null || mode === 'edit') return;
    let cancelled = false;
    const timer = window.setTimeout(
      () => {
        api
          .render(path, content)
          .then((result) => !cancelled && setHtml(result.html))
          .catch(() => undefined);
      },
      html ? 250 : 0,
    );
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [content, mode, path]);

  const change = useCallback(
    (value: string) => {
      if (value === latest.current) return;
      latest.current = value;
      dirty.current = true;
      setContent(value);
      onContent(value);
      onStatus('Unsaved changes');
      window.clearTimeout(saveTimer.current);
      // a second after the last keystroke, but at least every ten seconds while typing without a pause
      firstUnsaved.current ??= Date.now();
      const wait = Math.max(0, Math.min(1000, firstUnsaved.current + 10_000 - Date.now()));
      saveTimer.current = window.setTimeout(() => void flush(), wait);
    },
    [flush, onContent, onStatus],
  );

  const uploadFiles = useCallback(
    async (files: File[]) => {
      try {
        onStatus(`Uploading ${files.length} file(s)…`);
        const uploaded = await api.upload(files);
        props.onFilesChanged();
        onStatus('Uploaded');
        return uploaded.map((file) => file.markdown).join('\n') + (uploaded.length > 1 ? '\n' : '');
      } catch (e) {
        onError(`Upload failed: ${(e as Error).message}`);
        throw e;
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [onStatus, onError],
  );

  /** Inserts at the cursor, or appends when only the preview is showing. */
  const insert = (text: string, block = false) => {
    const editor = view.current;
    if (!editor || mode === 'preview') {
      change(`${latest.current.replace(/\n*$/, '\n\n')}${text}`);
      return;
    }
    const { from, to } = editor.state.selection.main;
    const lineStart = editor.state.doc.lineAt(from).from;
    const prefix = block && from !== lineStart ? '\n' : '';
    editor.dispatch({ changes: { from, to, insert: prefix + text }, selection: { anchor: from + prefix.length + text.length } });
    editor.focus();
  };

  const wrap = (before: string, after = before, placeholder = 'text') => {
    const editor = view.current;
    if (!editor || mode === 'preview') return;
    const { from, to } = editor.state.selection.main;
    const selected = editor.state.sliceDoc(from, to) || placeholder;
    editor.dispatch({
      changes: { from, to, insert: before + selected + after },
      selection: { anchor: from + before.length, head: from + before.length + selected.length },
    });
    editor.focus();
  };

  const prefixLine = (prefix: string) => {
    const editor = view.current;
    if (!editor || mode === 'preview') return;
    const line = editor.state.doc.lineAt(editor.state.selection.main.from);
    editor.dispatch({ changes: { from: line.from, insert: prefix } });
    editor.focus();
  };

  /**
   * Moves everything from the cursor line to the end into a new note next to this one, leaving a link behind.
   * A heading on the cursor line names the new note and goes with it.
   */
  const splitHere = async () => {
    const editor = view.current;
    if (!editor || mode === 'preview') return;
    const doc = editor.state.doc;
    const line = doc.lineAt(editor.state.selection.main.head);
    const rest = doc.sliceString(line.from).replace(/^\n+/, '');
    if (line.number === 1 || !rest.trim()) {
      onError('Put the cursor on the line where the new note should start');
      return;
    }
    const heading = /^#{1,6}\s+(.+?)\s*#*\s*$/.exec(line.text)?.[1];
    const title = (heading ?? window.prompt('Title of the new note', `${titleOf(path)} (part 2)`))?.trim().replace(/[\\/:*?"<>|]+/g, '-');
    if (!title) return;
    const folder = path.substring(0, path.lastIndexOf('/') + 1);
    const target = `${folder}${title}.md`;
    try {
      await api.create(target, rest.endsWith('\n') ? rest : `${rest}\n`);
    } catch (e) {
      onError(`Could not create ${target}: ${(e as Error).message}`);
      return;
    }
    // the editor keeps the head and the link; the autosave then writes it, in order with any earlier save
    const head = doc.sliceString(0, line.from).replace(/\n*$/, '');
    editor.dispatch({
      changes: { from: 0, to: doc.length, insert: `${head}\n\n[[${title}]]\n` },
      selection: { anchor: head.length + 2 },
    });
    await flush();
    props.onFilesChanged();
    props.onOpenLink(target, '', null);
  };

  if (content === null) return <div className="empty-state">Loading…</div>;

  return (
    <div
      className={`note-view mode-${mode}${dragging ? ' dragging' : ''}`}
      onDragOver={(e) => {
        if (e.dataTransfer.types.includes('Files')) {
          e.preventDefault();
          setDragging(true);
        }
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => {
        setDragging(false);
        // drops on the editor itself are handled there, at the drop position
        if (e.defaultPrevented || e.dataTransfer.files.length === 0) return;
        e.preventDefault();
        void uploadFiles(Array.from(e.dataTransfer.files)).then((text) => insert(text, true));
      }}
    >
      <div className="toolbar">
        <button title="Undo (Ctrl+Z)" disabled={mode === 'preview'} onClick={() => command(undo)}><Undo2 size={16} /></button>
        <button title="Redo (Ctrl+Y)" disabled={mode === 'preview'} onClick={() => command(redo)}><Redo2 size={16} /></button>
        <button title="Find and replace (Ctrl+F)" disabled={mode === 'preview'} onClick={() => command(openSearchPanel)}><Search size={16} /></button>
        <span className="toolbar-separator" />
        <button title="Bold" onClick={() => wrap('**')}><Bold size={16} /></button>
        <button title="Italic" onClick={() => wrap('*')}><Italic size={16} /></button>
        <button title="Heading" onClick={() => prefixLine('## ')}><Heading size={16} /></button>
        <button title="Link to note" onClick={() => wrap('[[', ']]', '')}><Link2 size={16} /></button>
        <button title="Code" onClick={() => wrap('`')}><Code size={16} /></button>
        <button title="List" onClick={() => prefixLine('- ')}><List size={16} /></button>
        <button title="Task" onClick={() => prefixLine('- [ ] ')}><ListChecks size={16} /></button>
        <button title="Table" onClick={() => insert(TABLE_TEMPLATE, true)}><Table size={16} /></button>
        <span className="toolbar-separator" />
        <button title="Mermaid diagram" onClick={() => insert(MERMAID_TEMPLATE, true)}><GitBranch size={16} /></button>
        <button title="Image gallery" onClick={() => insert(GALLERY_TEMPLATE, true)}><Images size={16} /></button>
        <button
          title="Flashcard (notes tagged #flashcards become decks)"
          onClick={() => insert(`${/(^|\s)#flashcards/.test(latest.current) ? '' : '#flashcards\n\n'}Question::Answer\n`, true)}
        >
          <GraduationCap size={16} />
        </button>
        <button title="Upload files (you can also paste or drop them)" onClick={() => fileInput.current?.click()}>
          <Paperclip size={16} />
        </button>
        <button title="Split note here (the rest moves to a new note, linked from this one)" disabled={mode === 'preview'} onClick={() => void splitHere()}>
          <Scissors size={16} />
        </button>
        <input
          ref={fileInput}
          type="file"
          multiple
          hidden
          onChange={(e) => {
            const files = Array.from(e.target.files ?? []);
            e.target.value = '';
            if (files.length > 0) void uploadFiles(files).then((text) => insert(text));
          }}
        />
      </div>
      <div className="note-panes">
        {mode !== 'preview' && (
          <div className="pane editor-pane">
            <MarkdownEditor
              value={content}
              onChange={change}
              noteNames={props.noteNames}
              onUploadFiles={uploadFiles}
              onEditorReady={(editor) => {
                view.current = editor;
                props.registerEditor(editor);
              }}
            />
          </div>
        )}
        {mode !== 'edit' && (
          <div className="pane preview-pane">
            <Preview
              html={html}
              dark={dark}
              anchor={props.anchor}
              onOpenLink={props.onOpenLink}
              onTag={props.onTag}
              onToggleTask={(index, line) => change(toggleTask(latest.current, index, line))}
              onLightbox={props.onLightbox}
            />
          </div>
        )}
        <ScrollKeeper path={path} mode={mode} previewReady={html !== ''} />
      </div>
      {dragging && <div className="drop-hint">Drop files to upload and embed them</div>}
    </div>
  );
}
