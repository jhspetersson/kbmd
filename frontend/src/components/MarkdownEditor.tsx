import { autocompletion, type CompletionContext, type CompletionResult } from '@codemirror/autocomplete';
import { markdown, markdownLanguage } from '@codemirror/lang-markdown';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { languages } from '@codemirror/language-data';
import { EditorView } from '@codemirror/view';
import { tags as t } from '@lezer/highlight';
import CodeMirror from '@uiw/react-codemirror';
import { useMemo, useRef } from 'react';
import type { NoteRef } from '../api';

interface Props {
  value: string;
  onChange: (value: string) => void;
  noteNames: NoteRef[];
  /** Uploads the files and resolves to the markdown that embeds them. */
  onUploadFiles: (files: File[]) => Promise<string>;
  onEditorReady: (view: EditorView) => void;
}

// Colours come from CSS variables, so the editor follows the app theme.
const editorTheme = EditorView.theme({
  '&': { height: '100%', backgroundColor: 'transparent', color: 'var(--text)', fontSize: '15px' },
  '&.cm-focused': { outline: 'none' },
  '.cm-scroller': { fontFamily: 'var(--font-text)', lineHeight: '1.65', overflow: 'auto' },
  '.cm-content': { maxWidth: '820px', margin: '0 auto', padding: '24px 28px 40vh', caretColor: 'var(--accent)' },
  '.cm-cursor': { borderLeftColor: 'var(--accent)' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground, ::selection': {
    backgroundColor: 'var(--selection) !important',
  },
  '.cm-activeLine': { backgroundColor: 'transparent' },
  '.cm-tooltip': {
    backgroundColor: 'var(--bg-raised)',
    color: 'var(--text)',
    border: '1px solid var(--border)',
    borderRadius: '6px',
    overflow: 'hidden',
  },
  '.cm-tooltip-autocomplete ul li[aria-selected]': { backgroundColor: 'var(--accent)', color: '#fff' },
  '.cm-completionDetail': { opacity: 0.6, fontStyle: 'normal' },
});

const highlight = HighlightStyle.define([
  { tag: t.heading1, fontSize: '1.7em', fontWeight: '700' },
  { tag: t.heading2, fontSize: '1.45em', fontWeight: '700' },
  { tag: t.heading3, fontSize: '1.25em', fontWeight: '600' },
  { tag: [t.heading4, t.heading5, t.heading6], fontWeight: '600' },
  { tag: t.strong, fontWeight: '700' },
  { tag: t.emphasis, fontStyle: 'italic' },
  { tag: t.strikethrough, textDecoration: 'line-through' },
  { tag: [t.link, t.url], color: 'var(--accent)' },
  { tag: t.monospace, fontFamily: 'var(--font-mono)', color: 'var(--code)' },
  { tag: t.quote, color: 'var(--text-muted)', fontStyle: 'italic' },
  { tag: [t.processingInstruction, t.meta, t.contentSeparator], color: 'var(--text-faint)' },
  { tag: [t.keyword, t.operator], color: 'var(--syntax-keyword)' },
  { tag: [t.string, t.special(t.string)], color: 'var(--syntax-string)' },
  { tag: [t.number, t.bool, t.atom], color: 'var(--syntax-number)' },
  { tag: t.comment, color: 'var(--text-faint)', fontStyle: 'italic' },
  { tag: [t.typeName, t.className, t.function(t.variableName)], color: 'var(--syntax-type)' },
]);

function insertFiles(view: EditorView, files: File[], at: number, upload: Props['onUploadFiles']) {
  upload(files)
    .then((text) => {
      if (!text) return;
      const position = Math.min(at, view.state.doc.length);
      view.dispatch({ changes: { from: position, insert: text }, selection: { anchor: position + text.length } });
      view.focus();
    })
    .catch(() => undefined); // the uploader reports its own errors
}

export function MarkdownEditor({ value, onChange, noteNames, onUploadFiles, onEditorReady }: Props) {
  // read through refs so the extensions (and with them the editor state) are created only once
  const names = useRef(noteNames);
  names.current = noteNames;
  const upload = useRef(onUploadFiles);
  upload.current = onUploadFiles;

  const extensions = useMemo(() => {
    const wikilinks = (context: CompletionContext): CompletionResult | null => {
      const match = context.matchBefore(/\[\[[^\]|#\n]*/);
      if (!match) return null;
      return {
        from: match.from + 2,
        validFor: /^[^\]|#\n]*$/,
        options: names.current.map((note) => ({
          label: note.title,
          detail: note.path.includes('/') ? note.path.substring(0, note.path.lastIndexOf('/')) : '',
          apply: (view: EditorView, _completion: unknown, from: number, to: number) => {
            const closed = view.state.sliceDoc(to, to + 2) === ']]';
            view.dispatch({
              changes: { from, to, insert: note.title + (closed ? '' : ']]') },
              selection: { anchor: from + note.title.length + 2 },
            });
          },
        })),
      };
    };

    return [
      markdown({ base: markdownLanguage, codeLanguages: languages }),
      syntaxHighlighting(highlight),
      editorTheme,
      EditorView.lineWrapping,
      autocompletion({ override: [wikilinks], activateOnTyping: true }),
      EditorView.domEventHandlers({
        paste(event, view) {
          const files = Array.from(event.clipboardData?.files ?? []);
          if (files.length === 0) return false;
          event.preventDefault();
          insertFiles(view, files, view.state.selection.main.head, (f) => upload.current(f));
          return true;
        },
        drop(event, view) {
          const files = Array.from(event.dataTransfer?.files ?? []);
          if (files.length === 0) return false;
          event.preventDefault();
          const at = view.posAtCoords({ x: event.clientX, y: event.clientY }) ?? view.state.selection.main.head;
          insertFiles(view, files, at, (f) => upload.current(f));
          return true;
        },
      }),
    ];
  }, []);

  return (
    <CodeMirror
      className="editor"
      value={value}
      height="100%"
      theme="none"
      extensions={extensions}
      onChange={onChange}
      onCreateEditor={onEditorReady}
      basicSetup={{ lineNumbers: false, foldGutter: false, highlightActiveLine: false, autocompletion: false }}
    />
  );
}
