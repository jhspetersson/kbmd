import { ArrowUp } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import type { ViewMode } from './NoteView';

interface Props {
  path: string;
  mode: ViewMode;
  /** The reading view has its HTML, so its scroll position can be put back. */
  previewReady: boolean;
}

/** Where every note was scrolled to while the app has been open, so switching tabs and coming back lands on the same spot. */
const memory = new Map<string, { editor: number; preview: number }>();

const EDITOR = '.cm-scroller';
const PREVIEW = '.preview-pane';

/**
 * Sits inside the note panes: remembers how far the editor and the reading view are scrolled, restores that when
 * the note is opened again, and shows a "back to top" button once the reader is more than a screen down.
 */
export function ScrollKeeper({ path, mode, previewReady }: Props) {
  const marker = useRef<HTMLDivElement>(null);
  const [farDown, setFarDown] = useState<HTMLElement | null>(null);

  useEffect(() => {
    const panes = marker.current?.parentElement;
    if (!panes) return;
    // scroll events do not bubble, but they can be caught on the way down
    const onScroll = (event: Event) => {
      const target = event.target as HTMLElement;
      const kind = target.matches?.(EDITOR) ? 'editor' : target.matches?.(PREVIEW) ? 'preview' : null;
      if (!kind) return;
      const saved = memory.get(path) ?? { editor: 0, preview: 0 };
      saved[kind] = target.scrollTop;
      memory.set(path, saved);
      setFarDown(target.scrollTop > target.clientHeight ? target : null);
    };
    panes.addEventListener('scroll', onScroll, true);
    return () => panes.removeEventListener('scroll', onScroll, true);
  }, [path]);

  useEffect(() => {
    setFarDown(null); // the pane it pointed at may just have been replaced
    const panes = marker.current?.parentElement;
    const saved = memory.get(path);
    if (!panes || !saved) return;
    // after the panes have been laid out; an untouched pane only, so a reader's own scrolling is never undone
    const frame = requestAnimationFrame(() => {
      const editor = panes.querySelector<HTMLElement>(EDITOR);
      if (editor && saved.editor > 0 && editor.scrollTop === 0) editor.scrollTop = saved.editor;
      const preview = panes.querySelector<HTMLElement>(PREVIEW);
      if (preview && previewReady && saved.preview > 0 && preview.scrollTop === 0) preview.scrollTop = saved.preview;
    });
    return () => cancelAnimationFrame(frame);
  }, [path, mode, previewReady]);

  return (
    <div className="scroll-keeper" ref={marker}>
      {farDown && (
        <button
          className="back-to-top"
          title="Back to top"
          onClick={() => {
            if (farDown.isConnected) farDown.scrollTo({ top: 0, behavior: 'smooth' });
            setFarDown(null);
          }}
        >
          <ArrowUp size={18} />
        </button>
      )}
    </div>
  );
}
