import type { ExcalidrawImperativeAPI } from '@excalidraw/excalidraw/types';
import { useRef } from 'react';
import { loadExcalidraw } from '../drawing';

/**
 * Stroke colours and widths beyond Excalidraw's built-in five picks and three widths.
 * Two shades of every hue (the open-color palette Excalidraw itself uses), plus black and grey.
 */
export const STROKE_COLORS: { color: string; name: string }[] = [
  { color: '#1e1e1e', name: 'Black' },
  { color: '#868e96', name: 'Grey' },
  { color: '#e03131', name: 'Red' },
  { color: '#ff8787', name: 'Light red' },
  { color: '#e8590c', name: 'Orange' },
  { color: '#ffa94d', name: 'Light orange' },
  { color: '#f08c00', name: 'Yellow' },
  { color: '#ffd43b', name: 'Light yellow' },
  { color: '#2f9e44', name: 'Green' },
  { color: '#69db7c', name: 'Light green' },
  { color: '#099268', name: 'Teal' },
  { color: '#38d9a9', name: 'Light teal' },
  { color: '#0c8599', name: 'Cyan' },
  { color: '#3bc9db', name: 'Light cyan' },
  { color: '#1971c2', name: 'Blue' },
  { color: '#4dabf7', name: 'Light blue' },
  { color: '#6741d9', name: 'Violet' },
  { color: '#9775fa', name: 'Light violet' },
  { color: '#9c36b5', name: 'Grape' },
  { color: '#da77f2', name: 'Light grape' },
  { color: '#c2255c', name: 'Pink' },
  { color: '#f783ac', name: 'Light pink' },
];

export const STROKE_WIDTHS = [0.5, 1, 2, 3, 4, 6, 8, 12];

/**
 * Excalidraw paints pencil strokes about this many times wider than a line of the same stroke width (a brush of
 * 4.25 × width, widened again by pen pressure at a normal drawing speed). Pencil elements therefore store the palette
 * width divided by this, so that every tool draws at the thickness the palette shows.
 */
export const PENCIL_SCALE = 5;
export const isPencil = (type: string) => type === 'freedraw';
/** The stroke width an element of this type stores for a palette width. */
export const toolWidth = (width: number, type: string) => (isPencil(type) ? width / PENCIL_SCALE : width);
/** The palette width behind an element's stored stroke width. */
export const paletteWidth = (width: number, type: string) => Math.round((isPencil(type) ? width * PENCIL_SCALE : width) * 1000) / 1000;

export interface StrokeStyle {
  color: string;
  width: number;
}

interface Props {
  api: ExcalidrawImperativeAPI | null;
  dark: boolean;
  /** what the selection, or the next element to draw, uses now */
  current: StrokeStyle;
}

/** Applies a stroke change to the selected elements and to whatever gets drawn next. */
async function applyStroke(api: ExcalidrawImperativeAPI, change: { strokeColor: string } | { strokeWidth: number }) {
  const { newElementWith, CaptureUpdateAction } = await loadExcalidraw();
  const state = api.getAppState();
  const selected = state.selectedElementIds;
  const elements = api
    .getSceneElementsIncludingDeleted()
    .map((element) =>
      selected[element.id] && !element.isDeleted
        ? newElementWith(element, 'strokeWidth' in change ? { strokeWidth: toolWidth(change.strokeWidth, element.type) } : change)
        : element,
    );
  api.updateScene({
    elements,
    appState: {
      currentItemStrokeColor: 'strokeColor' in change ? change.strokeColor : state.currentItemStrokeColor,
      currentItemStrokeWidth: 'strokeWidth' in change ? toolWidth(change.strokeWidth, state.activeTool.type) : state.currentItemStrokeWidth,
    },
    captureUpdate: CaptureUpdateAction.IMMEDIATELY,
  });
}

/** A strip of stroke colours and widths floating over the drawing canvas. */
export function DrawingPalette({ api, dark, current }: Props) {
  const strip = useRef<HTMLDivElement>(null);
  const pick = (change: { strokeColor: string } | { strokeWidth: number }) => {
    if (!api) return;
    void applyStroke(api, change);
    // the keyboard shortcuts (undo, tools) keep working: focus goes back to the canvas, not the button
    strip.current?.parentElement?.querySelector<HTMLElement>('.excalidraw')?.focus();
  };
  return (
    <div ref={strip} className={`drawing-palette${dark ? ' dark' : ''}`} role="toolbar" aria-label="Stroke colour and width">
      <div className="palette-colors">
        {STROKE_COLORS.map(({ color, name }) => (
          <button
            key={color}
            type="button"
            className={`swatch${current.color.toLowerCase() === color ? ' active' : ''}`}
            style={{ background: color }}
            title={name}
            aria-label={name}
            disabled={!api}
            onClick={() => pick({ strokeColor: color })}
          />
        ))}
      </div>
      <div className="palette-widths">
        {STROKE_WIDTHS.map((width) => (
          <button
            key={width}
            type="button"
            className={`stroke-width${current.width === width ? ' active' : ''}`}
            title={`Stroke width ${width}`}
            aria-label={`Stroke width ${width}`}
            disabled={!api}
            onClick={() => pick({ strokeWidth: width })}
          >
            <span style={{ height: Math.max(1, Math.min(width, 14)) }} />
          </button>
        ))}
      </div>
    </div>
  );
}
