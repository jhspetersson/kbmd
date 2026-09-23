import '@excalidraw/excalidraw/index.css';
import type { ExcalidrawImperativeAPI } from '@excalidraw/excalidraw/types';
import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { api, ApiError } from '../api';
import { loadExcalidraw, parseDrawing, type DrawingFile } from '../drawing';
import { isNative } from '../native';
import { DrawingPalette, isPencil, paletteWidth, toolWidth, type StrokeStyle } from './DrawingPalette';

const Excalidraw = lazy(() => loadExcalidraw().then((module) => ({ default: module.Excalidraw })));

interface Props {
  path: string;
  dark: boolean;
  onStatus: (status: string) => void;
  registerFlush: (flush: (() => Promise<void>) | null) => void;
}

/** Free-form drawing, stored in the vault as a regular .excalidraw file. */
export function DrawingEditor({ path, dark, onStatus, registerFlush }: Props) {
  const [initial, setInitial] = useState<DrawingFile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const modified = useRef<number | undefined>(undefined);
  const pending = useRef<string | null>(null);
  const lastVersion = useRef(-1);
  const timer = useRef<number | undefined>(undefined);
  const flushRef = useRef<(() => Promise<void>) | null>(null);
  const [excalidraw, setExcalidraw] = useState<ExcalidrawImperativeAPI | null>(null);
  const [stroke, setStroke] = useState<StrokeStyle>({ color: '#1e1e1e', width: 2 });
  const activeTool = useRef<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .read(path)
      .then((doc) => {
        if (cancelled) return;
        modified.current = doc.modified;
        setInitial(parseDrawing(doc.content));
      })
      .catch((e: Error) => setError(e.message));

    const flush = async () => {
      window.clearTimeout(timer.current);
      const content = pending.current;
      if (content === null) return;
      pending.current = null;
      try {
        onStatus('Saving…');
        modified.current = (await api.save(path, content, modified.current)).modified;
        onStatus('Saved');
      } catch (e) {
        if (e instanceof ApiError && e.status === 409 && window.confirm('This drawing changed on disk. Overwrite it with your version?')) {
          modified.current = (await api.save(path, content)).modified;
          onStatus('Saved');
        } else {
          onStatus('Not saved');
        }
      }
    };
    flushRef.current = flush;
    registerFlush(flush);
    return () => {
      cancelled = true;
      registerFlush(null);
      void flush();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path]);

  if (error) return <div className="empty-state">{error}</div>;
  if (!initial) return <div className="empty-state">Loading drawing…</div>;

  return (
    <div className="drawing-editor">
      <Suspense fallback={<div className="empty-state">Loading drawing tools…</div>}>
        <Excalidraw
          excalidrawAPI={setExcalidraw}
          theme={dark ? 'dark' : 'light'}
          initialData={{
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            elements: initial.elements as any,
            appState: {
              ...initial.appState,
              collaborators: new Map(),
              // in the Android app a blank drawing is ready for the pen right away; Excalidraw then notices the
              // stylus by itself and keeps fingers for panning and zooming (its "pen mode")
              ...(isNative && initial.elements.length === 0
                ? { activeTool: { type: 'freedraw', customType: null, locked: false, lastActiveTool: null }, currentItemStrokeWidth: toolWidth(2, 'freedraw') }
                : {}),
              // eslint-disable-next-line @typescript-eslint/no-explicit-any
            } as any,
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            files: initial.files as any,
          }}
          onChange={(elements, appState, files) => {
            // Excalidraw keeps one width for all tools, but the pencil stores a scaled-down one: convert it on the way
            // in and out of the pencil, so that switching tools keeps the thickness the palette shows
            const tool = appState.activeTool.type;
            const previousTool = activeTool.current ?? tool;
            if (isPencil(tool) !== isPencil(previousTool)) {
              const currentItemStrokeWidth = toolWidth(paletteWidth(appState.currentItemStrokeWidth, previousTool), tool);
              queueMicrotask(() => excalidraw?.updateScene({ appState: { currentItemStrokeWidth } }));
            }
            activeTool.current = tool;

            // the palette shows the selection's stroke, or the one the next element gets
            const selected = elements.filter((element) => appState.selectedElementIds[element.id] && !element.isDeleted);
            const color = selected.length > 0 && selected.every((element) => element.strokeColor === selected[0].strokeColor)
              ? selected[0].strokeColor
              : appState.currentItemStrokeColor;
            const widths = new Set(selected.map((element) => paletteWidth(element.strokeWidth, element.type)));
            const width = widths.size === 1 ? [...widths][0] : paletteWidth(appState.currentItemStrokeWidth, previousTool);
            setStroke((previous) => (previous.color === color && previous.width === width ? previous : { color, width }));

            // onChange also fires for pointer moves; only a changed scene needs saving
            const version = elements.reduce((sum, element) => sum + element.version, 0) + Object.keys(files).length;
            if (lastVersion.current === -1) {
              lastVersion.current = version;
              return;
            }
            if (version === lastVersion.current) return;
            lastVersion.current = version;
            void loadExcalidraw().then(({ serializeAsJSON }) => {
              pending.current = serializeAsJSON(elements, appState, files, 'local');
              onStatus('Unsaved changes');
              window.clearTimeout(timer.current);
              timer.current = window.setTimeout(() => void flushRef.current?.(), 1200);
            });
          }}
        />
      </Suspense>
      <DrawingPalette api={excalidraw} dark={dark} current={stroke} />
    </div>
  );
}
