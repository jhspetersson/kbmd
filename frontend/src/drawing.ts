// Excalidraw is large, so everything touching it is loaded on demand.

declare global {
  interface Window {
    EXCALIDRAW_ASSET_PATH?: string;
  }
}

// fonts are copied to /fonts at build time (scripts/copy-excalidraw-fonts.mjs)
window.EXCALIDRAW_ASSET_PATH = '/';

export function loadExcalidraw() {
  return import('@excalidraw/excalidraw');
}

export interface DrawingFile {
  elements: readonly unknown[];
  appState: Record<string, unknown>;
  files: Record<string, unknown>;
}

export function parseDrawing(content: string): DrawingFile {
  try {
    const data = JSON.parse(content);
    return { elements: data.elements ?? [], appState: data.appState ?? {}, files: data.files ?? {} };
  } catch {
    return { elements: [], appState: {}, files: {} };
  }
}

export async function renderDrawingSvg(content: string, dark: boolean): Promise<SVGSVGElement> {
  const { exportToSvg } = await loadExcalidraw();
  const drawing = parseDrawing(content);
  return exportToSvg({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    elements: drawing.elements as any,
    appState: { ...drawing.appState, exportBackground: false, exportWithDarkMode: dark },
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    files: drawing.files as any,
  });
}
