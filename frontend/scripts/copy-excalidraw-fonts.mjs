// Excalidraw loads its fonts from a CDN unless they are served locally.
// Copy them into public/ so drawings work offline (and inside Docker).
import { cpSync, existsSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const source = join(root, 'node_modules/@excalidraw/excalidraw/dist/prod/fonts');
const target = join(root, 'public/fonts');

if (existsSync(source)) {
  rmSync(target, { recursive: true, force: true });
  cpSync(source, target, { recursive: true });
} else {
  console.warn('Excalidraw fonts not found at', source);
}
