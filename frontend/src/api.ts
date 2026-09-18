export type EntryType = 'folder' | 'note' | 'drawing' | 'image' | 'file';

export interface TreeNode {
  name: string;
  path: string;
  type: EntryType;
  children: TreeNode[] | null;
}

export interface NoteDocument {
  path: string;
  title: string;
  content: string;
  modified: number;
}

export interface NoteRef {
  path: string;
  title: string;
}

export interface Snippet {
  line: number;
  text: string;
}

export interface Hit {
  path: string;
  title: string;
  snippets: Snippet[];
}

export interface SearchResponse {
  hits: Hit[];
  terms: string[];
}

export interface GraphData {
  nodes: { id: string; label: string; exists: boolean; tags: string[] }[];
  edges: { source: string; target: string }[];
}

export interface UploadedFile {
  path: string;
  name: string;
  markdown: string;
}

export interface SyncSettings {
  provider: 'github' | 'gitlab';
  remoteUrl: string;
  token?: string;
  tokenSet?: boolean;
  branch: string;
  authorName: string;
  authorEmail: string;
  autoSyncMinutes: number;
}

export interface SyncResult {
  ok: boolean;
  message: string;
  conflicts: string[];
  time: string;
}

export interface SyncStatus {
  configured: boolean;
  running: boolean;
  pendingChanges: number;
  lastResult: SyncResult | null;
}

export type Rating = 'AGAIN' | 'HARD' | 'GOOD' | 'EASY';

export interface Deck {
  name: string;
  total: number;
  fresh: number;
  due: number;
}

export interface StudyCard {
  id: string;
  deck: string;
  notePath: string;
  line: number;
  fresh: boolean;
  frontHtml: string;
  backHtml: string;
  intervals: Record<Rating, number>;
}

export interface Habit {
  id: string;
  name: string;
  notePath: string;
  line: number;
  /** 7 for a daily habit */
  weeklyTarget: number;
  /** checked dates within the board's days */
  done: string[];
  streak: number;
  week: number;
  total: number;
}

export interface HabitBoard {
  days: string[];
  habits: Habit[];
}

export interface Task {
  notePath: string;
  noteTitle: string;
  line: number;
  text: string;
  done: boolean;
  due: string | null;
  tags: string[];
}

export interface Card {
  line: number;
  text: string;
  /** false for a plain list item without a checkbox */
  task: boolean;
  done: boolean;
  due: string | null;
}

export interface Column {
  name: string;
  line: number;
  cards: Card[];
}

export interface Board {
  path: string;
  title: string;
  columns: Column[];
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  if (!response.ok) {
    let message = response.statusText;
    try {
      const body = await response.json();
      message = body.message || body.error || message;
    } catch {
      // not JSON, keep the status text
    }
    throw new ApiError(response.status, message);
  }
  if (response.status === 204 || response.headers.get('content-length') === '0') {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

function json(method: string, body: unknown): RequestInit {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}

const q = (path: string) => `path=${encodeURIComponent(path)}`;

export const api = {
  tree: () => request<TreeNode>('/api/tree'),
  read: (path: string) => request<NoteDocument>(`/api/notes?${q(path)}`),
  save: (path: string, content: string, baseModified?: number, keepalive = false) =>
    request<NoteDocument>(`/api/notes?${q(path)}`, { ...json('PUT', { content, baseModified }), keepalive }),
  create: (path: string, content = '') => request<NoteDocument>('/api/notes', json('POST', { path, content })),
  names: () => request<NoteRef[]>('/api/notes/names'),
  createFolder: (path: string) => request<void>('/api/folders', json('POST', { path })),
  move: (from: string, to: string) => request<{ updatedNotes: number }>('/api/move', json('POST', { from, to })),
  remove: (path: string) => request<void>(`/api/entries?${q(path)}`, { method: 'DELETE' }),
  render: (path: string, content: string) => request<{ html: string }>('/api/render', json('POST', { path, content })),
  backlinks: (path: string) => request<Hit[]>(`/api/backlinks?${q(path)}`),
  graph: () => request<GraphData>('/api/graph'),
  tags: () => request<Record<string, number>>('/api/tags'),
  search: (query: string) => request<SearchResponse>(`/api/search?q=${encodeURIComponent(query)}`),
  daily: () => request<NoteDocument>('/api/daily', { method: 'POST' }),
  upload: (files: File[], folder?: string) => {
    const form = new FormData();
    files.forEach((file) => form.append('file', file, file.name));
    if (folder) form.append('folder', folder);
    return request<UploadedFile[]>('/api/files/upload', { method: 'POST', body: form });
  },
  rawUrl: (path: string) => `/api/files/raw?${q(path)}`,
  exportUrl: '/api/export',
  decks: () => request<Deck[]>('/api/flashcards/decks'),
  dueCards: (deck: string | null) => request<StudyCard[]>(`/api/flashcards/due${deck ? `?deck=${encodeURIComponent(deck)}` : ''}`),
  review: (id: string, rating: Rating) => request<{ due: string; interval: number }>('/api/flashcards/review', json('POST', { id, rating })),
  ankiExportUrl: (deck: string | null) => `/api/flashcards/export${deck ? `?deck=${encodeURIComponent(deck)}` : ''}`,
  tasks: () => request<Task[]>('/api/tasks'),
  addTask: (text: string, path?: string) => request<Task>('/api/tasks', json('POST', { text, path })),
  toggleTask: (path: string, line: number) => request<{ done: boolean }>('/api/tasks/toggle', json('POST', { path, line })),
  boards: () => request<Board[]>('/api/kanban'),
  moveCard: (path: string, line: number, column: string, position: number) =>
    request<Board>('/api/kanban/move', json('POST', { path, line, column, position })),
  addCard: (path: string, column: string, text: string) => request<Board>('/api/kanban/card', json('POST', { path, column, text })),
  habits: (days: number) => request<HabitBoard>(`/api/habits?days=${days}`),
  toggleHabit: (id: string, date: string) => request<{ done: boolean }>('/api/habits/toggle', json('POST', { id, date })),
  syncSettings: () => request<SyncSettings>('/api/sync/settings'),
  saveSyncSettings: (settings: SyncSettings) => request<SyncSettings>('/api/sync/settings', json('PUT', settings)),
  syncStatus: () => request<SyncStatus>('/api/sync/status'),
  syncTest: () => request<SyncResult>('/api/sync/test', { method: 'POST' }),
  syncRun: () => request<SyncResult>('/api/sync/run', { method: 'POST' }),
};

export const EMPTY_DRAWING = JSON.stringify(
  { type: 'excalidraw', version: 2, source: 'kbmd', elements: [], appState: {}, files: {} },
  null,
  2,
);

export function entryType(path: string): EntryType {
  const lower = path.toLowerCase();
  if (lower.endsWith('.md')) return 'note';
  if (lower.endsWith('.excalidraw')) return 'drawing';
  if (/\.(png|jpe?g|gif|webp|svg|bmp|avif)$/.test(lower)) return 'image';
  return 'file';
}

export function baseName(path: string): string {
  return path.substring(path.lastIndexOf('/') + 1);
}

export function parentOf(path: string): string {
  const slash = path.lastIndexOf('/');
  return slash < 0 ? '' : path.substring(0, slash);
}

export function titleOf(path: string): string {
  return baseName(path).replace(/\.(md|excalidraw)$/i, '');
}

export function joinPath(folder: string, name: string): string {
  return folder ? `${folder}/${name}` : name;
}
