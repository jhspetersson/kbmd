import { EditorView } from '@codemirror/view';
import {
  BookOpen,
  Calendar,
  CalendarDays,
  Columns2,
  Download,
  FilePlus,
  FolderPlus,
  GraduationCap,
  Files,
  Hash,
  ListTodo,
  Moon,
  PanelRight,
  PenTool,
  Pencil,
  RefreshCw,
  Repeat,
  Search,
  SquareKanban,
  Sun,
  Upload,
  Waypoints,
  X,
  Zap,
} from 'lucide-react';
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import {
  api,
  baseName,
  EMPTY_DRAWING,
  entryType,
  joinPath,
  parentOf,
  titleOf,
  type NoteRef,
  type SyncResult,
  type SyncStatus,
  type TreeNode,
} from './api';
import { Lightbox, PromptDialog, QuickSwitcher, type PromptRequest } from './components/Dialogs';
import { FlashcardsView } from './components/FlashcardsView';
import { FileTree, type TreeAction } from './components/FileTree';
import { GraphView } from './components/GraphView';
import { CalendarView } from './components/CalendarView';
import { HabitsView } from './components/HabitsView';
import { KanbanView } from './components/KanbanView';
import { TasksView } from './components/TasksView';
import { NoteView, type ViewMode } from './components/NoteView';
import { ContextPanel, SearchPanel, TagsPanel } from './components/Panels';
import { SyncDialog } from './components/SyncDialog';
import { isNarrow, isNative } from './native';

const DrawingEditor = lazy(() => import('./components/DrawingEditor').then((module) => ({ default: module.DrawingEditor })));

const GRAPH_TAB = '::graph';
const CARDS_TAB = '::cards';
const HABITS_TAB = '::habits';
const TASKS_TAB = '::tasks';
const KANBAN_TAB = '::kanban';
const CALENDAR_TAB = '::calendar';
const SPECIAL_TABS: Record<string, string> = {
  [GRAPH_TAB]: 'Graph view',
  [CARDS_TAB]: 'Flashcards',
  [HABITS_TAB]: 'Habits',
  [TASKS_TAB]: 'Tasks',
  [KANBAN_TAB]: 'Kanban',
  [CALENDAR_TAB]: 'Calendar',
};
const isFile = (tab: string | null): tab is string => tab !== null && !(tab in SPECIAL_TABS);
const MODES: ViewMode[] = ['split', 'edit', 'preview'];
type LeftPanel = 'files' | 'search' | 'tags';

function stored<T>(key: string, fallback: T): T {
  try {
    const value = localStorage.getItem(key);
    return value === null ? fallback : (JSON.parse(value) as T);
  } catch {
    return fallback;
  }
}

function pathFromHash(): string | null {
  const hash = window.location.hash;
  return hash.startsWith('#/') && hash.length > 2 ? decodeURIComponent(hash.substring(2)) : null;
}

export function App() {
  const [tree, setTree] = useState<TreeNode | null>(null);
  const [names, setNames] = useState<NoteRef[]>([]);
  const [revision, setRevision] = useState(0);
  const [reloadToken, setReloadToken] = useState(0);

  const [tabs, setTabs] = useState<string[]>(() => stored('kbmd.tabs', [] as string[]));
  const [active, setActive] = useState<string | null>(() => pathFromHash() ?? stored<string | null>('kbmd.active', null));
  const [mode, setMode] = useState<ViewMode>(() => stored<ViewMode>('kbmd.mode', isNarrow() ? 'edit' : 'split'));
  const [dark, setDark] = useState(() => stored('kbmd.dark', window.matchMedia('(prefers-color-scheme: dark)').matches));
  const [leftPanel, setLeftPanel] = useState<LeftPanel | null>(() => (isNarrow() ? null : 'files'));
  // on a phone the side panels cover the note, so they start closed and are not remembered
  const [rightOpen, setRightOpen] = useState(() => !isNarrow() && stored('kbmd.right', true));

  const [searchQuery, setSearchQuery] = useState('');
  const [content, setContent] = useState('');
  const [anchor, setAnchor] = useState<{ path: string; text: string; tick: number } | null>(null);
  const [status, setStatus] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [syncStatus, setSyncStatus] = useState<SyncStatus | null>(null);
  const [syncing, setSyncing] = useState(false);

  const [prompt, setPrompt] = useState<PromptRequest | null>(null);
  const [switcherOpen, setSwitcherOpen] = useState(false);
  const [syncOpen, setSyncOpen] = useState(false);
  const [lightbox, setLightbox] = useState<{ images: { src: string; caption: string }[]; index: number } | null>(null);

  const flushRef = useRef<(() => Promise<void>) | null>(null);
  const editorRef = useRef<EditorView | null>(null);
  const uploadInput = useRef<HTMLInputElement>(null);
  const uploadFolder = useRef('');

  // ------------------------------------------------------------------ data

  const refresh = useCallback(async () => {
    const [nextTree, nextNames] = await Promise.all([api.tree(), api.names()]);
    setTree(nextTree);
    setNames(nextNames);
    setRevision((r) => r + 1);
  }, []);

  const refreshSyncStatus = useCallback(() => {
    api.syncStatus().then(setSyncStatus).catch(() => undefined);
  }, []);

  const fail = useCallback((message: string) => setError(message), []);

  useEffect(() => {
    refresh().catch((e: Error) => fail(`Cannot reach the server: ${e.message}`));
    refreshSyncStatus();
    const timer = window.setInterval(refreshSyncStatus, 30_000);
    return () => window.clearInterval(timer);
  }, [refresh, refreshSyncStatus, fail]);

  useEffect(() => {
    if (!error) return;
    const timer = window.setTimeout(() => setError(null), 8000);
    return () => window.clearTimeout(timer);
  }, [error]);

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    window.KbmdNative?.setTheme(dark);
    localStorage.setItem('kbmd.dark', JSON.stringify(dark));
  }, [dark]);
  useEffect(() => localStorage.setItem('kbmd.mode', JSON.stringify(mode)), [mode]);
  useEffect(() => {
    if (!isNarrow()) localStorage.setItem('kbmd.right', JSON.stringify(rightOpen));
  }, [rightOpen]);
  useEffect(() => {
    localStorage.setItem('kbmd.tabs', JSON.stringify(tabs));
    localStorage.setItem('kbmd.active', JSON.stringify(active));
    const hash = isFile(active) ? `#/${encodeURI(active)}` : '#/';
    if (window.location.hash !== hash) window.history.replaceState(null, '', hash);
    document.title = isFile(active) ? `${titleOf(active)} - kbmd` : 'kbmd';
  }, [tabs, active]);

  // ------------------------------------------------------------------ navigation

  const openPath = useCallback((path: string, heading?: string | null) => {
    setTabs((current) => (current.includes(path) ? current : [...current, path]));
    setActive(path);
    setAnchor(heading ? { path, text: heading, tick: Date.now() } : null);
    if (isNarrow()) {
      setLeftPanel(null);
      setRightOpen(false);
    }
  }, []);

  useEffect(() => {
    // first visit: open the welcome note; and keep the active tab listed
    if (active && !tabs.includes(active)) setTabs((current) => [...current, active]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const firstTree = useRef(true);
  useEffect(() => {
    if (!tree || !firstTree.current) return;
    firstTree.current = false;
    if (!active && tabs.length === 0 && tree.children?.some((child) => child.path === 'Welcome.md')) {
      openPath('Welcome.md');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tree]);
  useEffect(() => {
    const onHash = () => {
      const path = pathFromHash();
      if (path) openPath(path);
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, [openPath]);

  const closeTab = (path: string) => {
    setTabs((current) => {
      const next = current.filter((tab) => tab !== path);
      if (active === path) {
        const index = current.indexOf(path);
        setActive(next[Math.min(index, next.length - 1)] ?? null);
      }
      return next;
    });
  };

  /** After a move or delete: rewrite or drop the tabs under the old path. */
  const remapTabs = (from: string, to: string | null) => {
    const remap = (path: string) =>
      path === from ? to : path.startsWith(`${from}/`) ? (to === null ? null : to + path.substring(from.length)) : path;
    setTabs((current) => current.map(remap).filter((path): path is string => path !== null));
    setActive((current) => (current === null ? null : remap(current)));
  };

  // ------------------------------------------------------------------ vault actions

  const ask = (title: string, initial: string, confirmLabel: string) =>
    new Promise<string | null>((resolve) =>
      setPrompt({
        title,
        initial,
        confirmLabel,
        resolve: (value) => {
          setPrompt(null);
          resolve(value);
        },
      }),
    );

  const guarded = async (action: () => Promise<void>) => {
    try {
      await action();
    } catch (e) {
      fail((e as Error).message);
    }
  };

  /** Text selected in the editor, tidied into a file name; empty when nothing usable is selected. */
  const selectedName = () => {
    const view = editorRef.current;
    if (!view) return '';
    const { from, to } = view.state.selection.main;
    return view.state
      .sliceDoc(from, to)
      .replace(/[\\/:*?"<>|#^[\]]/g, ' ') // not allowed in file names, or meaningful in links
      .replace(/\s+/g, ' ')
      .trim()
      .substring(0, 120)
      .trim();
  };

  const createNote = (folder: string, suggested?: string) =>
    guarded(async () => {
      const name = suggested ?? (await ask('New note', joinPath(folder, selectedName() || 'Untitled'), 'Create'));
      if (!name) return;
      const path = /\.md$/i.test(name) ? name : `${name}.md`;
      await api.create(path, '');
      await refresh();
      openPath(path);
    });

  const createDrawing = (folder: string) =>
    guarded(async () => {
      const name = await ask('New drawing', joinPath(folder, 'Drawing'), 'Create');
      if (!name) return;
      const path = /\.excalidraw$/i.test(name) ? name : `${name}.excalidraw`;
      await api.create(path, EMPTY_DRAWING);
      await refresh();
      openPath(path);
    });

  const createFolder = (folder: string) =>
    guarded(async () => {
      const name = await ask('New folder', joinPath(folder, 'Folder'), 'Create');
      if (!name) return;
      await api.createFolder(name);
      await refresh();
    });

  const move = (from: string, to: string) =>
    guarded(async () => {
      if (from === to || to.startsWith(`${from}/`)) return;
      await flushRef.current?.(); // otherwise the pending save would recreate the old file
      const result = await api.move(from, to);
      remapTabs(from, to);
      await refresh();
      await flushRef.current?.(); // what was typed meanwhile, before the editor is reloaded
      setReloadToken((t) => t + 1);
      if (result.updatedNotes > 0) setStatus(`Updated links in ${result.updatedNotes} note(s)`);
    });

  const rename = (node: TreeNode) =>
    guarded(async () => {
      const to = await ask(`Rename or move "${node.name}"`, node.path, 'Rename');
      if (!to) return;
      const extension = /\.[^./]+$/.exec(node.path)?.[0] ?? '';
      await move(node.path, node.type !== 'folder' && extension && !/\.[^./]+$/.test(to) ? to + extension : to);
    });

  const remove = (node: TreeNode) =>
    guarded(async () => {
      if (!window.confirm(`Delete "${node.path}"?\n\nIt is moved to the vault's .trash folder.`)) return;
      await flushRef.current?.();
      remapTabs(node.path, null);
      await api.remove(node.path);
      await refresh();
    });

  const uploadTo = (files: File[], folder: string) =>
    guarded(async () => {
      setStatus(`Uploading ${files.length} file(s)…`);
      await api.upload(files, folder || undefined);
      await refresh();
      setStatus('Uploaded');
    });

  /** A copy of one file: through the phone's "save as" dialog, or as a plain browser download. */
  const exportFile = async (path: string) => {
    await flushRef.current?.(); // what was typed since the last save belongs in the copy
    if (isNative && window.KbmdNative?.exportFile) {
      window.KbmdNative.exportFile(path);
      return;
    }
    const link = document.createElement('a');
    link.href = api.rawUrl(path);
    link.download = baseName(path);
    link.click();
  };

  const onTreeAction = (action: TreeAction, node: TreeNode) => {
    if (action === 'new-note') void createNote(node.path);
    if (action === 'export') void exportFile(node.path);
    if (action === 'new-drawing') void createDrawing(node.path);
    if (action === 'new-folder') void createFolder(node.path);
    if (action === 'rename') void rename(node);
    if (action === 'delete') void remove(node);
    if (action === 'upload') {
      uploadFolder.current = node.path;
      uploadInput.current?.click();
    }
  };

  const openLink = useCallback(
    (path: string, target: string, heading: string | null) => {
      if (path) {
        openPath(path, heading);
      } else if (target) {
        // following a link to a note that does not exist yet creates it
        void createNote('', target.replace(/^\/+/, ''));
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [openPath],
  );

  const showTag = useCallback((tag: string) => {
    setSearchQuery(`tag:${tag}`);
    setLeftPanel('search');
  }, []);

  const runSync = useCallback(async (): Promise<SyncResult> => {
    setSyncing(true);
    try {
      await flushRef.current?.();
      const result = await api.syncRun();
      await refresh();
      await flushRef.current?.(); // what was typed while the sync ran, before the editor is reloaded
      setReloadToken((t) => t + 1);
      setStatus(result.ok ? 'Synced' : 'Sync failed');
      if (!result.ok) fail(result.message);
      return result;
    } finally {
      setSyncing(false);
      refreshSyncStatus();
    }
  }, [refresh, refreshSyncStatus, fail]);

  const openDaily = () =>
    guarded(async () => {
      const note = await api.daily();
      await refresh();
      openPath(note.path);
    });

  // ------------------------------------------------------------------ Android app (see native.ts)

  useEffect(() => {
    window.__kbmd = {
      back: () => {
        if (lightbox) setLightbox(null);
        else if (prompt) prompt.resolve(null);
        else if (switcherOpen) setSwitcherOpen(false);
        else if (syncOpen) {
          setSyncOpen(false);
          refreshSyncStatus();
        } else if (isNarrow() && (leftPanel || rightOpen)) {
          setLeftPanel(null);
          setRightOpen(false);
        } else return false;
        return true;
      },
      flush: () => void flushRef.current?.(),
      command: (name, argument) => {
        if (name === 'open' && argument) void refresh().then(() => openPath(argument));
        else if (name === 'new-note') void createNote('');
        else if (name === 'daily') void openDaily();
        else if (name === 'search') setLeftPanel('search');
        else if (name === 'habits') openPath(HABITS_TAB);
        else if (name === 'tasks') openPath(TASKS_TAB);
        else if (name === 'kanban') openPath(KANBAN_TAB);
        else if (name === 'calendar') openPath(CALENDAR_TAB);
        else if (name === 'refresh') {
          void refresh().catch(() => undefined);
          refreshSyncStatus();
        }
      },
    };
  });
  useEffect(() => window.KbmdNative?.ready(), []);

  // ------------------------------------------------------------------ keyboard

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey)) return;
      const key = event.key.toLowerCase();
      if (key === 's') {
        event.preventDefault();
        void flushRef.current?.();
      } else if (key === 'o' || key === 'p') {
        event.preventDefault();
        setSwitcherOpen(true);
      } else if (key === 'f' && event.shiftKey) {
        event.preventDefault();
        setLeftPanel('search');
      } else if (key === 'e') {
        event.preventDefault();
        setMode((current) => MODES[(MODES.indexOf(current) + 1) % MODES.length]);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // ------------------------------------------------------------------ stable callbacks for the editor

  const onSaved = useCallback(() => {
    setRevision((r) => r + 1);
    api.names().then(setNames).catch(() => undefined);
  }, []);
  const registerFlush = useCallback((flush: (() => Promise<void>) | null) => {
    flushRef.current = flush;
  }, []);
  const registerEditor = useCallback((view: EditorView | null) => {
    editorRef.current = view;
  }, []);
  const showLightbox = useCallback((images: { src: string; caption: string }[], index: number) => setLightbox({ images, index }), []);

  const goToHeading = (text: string, line: number) => {
    if (!active) return;
    setAnchor({ path: active, text: `${text}`, tick: Date.now() });
    const view = editorRef.current;
    if (view && line <= view.state.doc.lines) {
      const position = view.state.doc.line(line).from;
      view.dispatch({ selection: { anchor: position }, effects: EditorView.scrollIntoView(position, { y: 'start', yMargin: 24 }) });
    }
  };

  // ------------------------------------------------------------------ render

  const activeType =
    active === null
      ? null
      : active === GRAPH_TAB
        ? 'graph'
        : active === CARDS_TAB
          ? 'cards'
          : active === HABITS_TAB
            ? 'habits'
            : active === TASKS_TAB
              ? 'tasks'
              : active === KANBAN_TAB
                ? 'kanban'
                : active === CALENDAR_TAB
                  ? 'calendar'
                  : entryType(active);
  const togglePanel = (panel: LeftPanel) => setLeftPanel((current) => (current === panel ? null : panel));
  const words = content.trim() ? content.trim().split(/\s+/).length : 0;
  const pending = syncStatus?.pendingChanges ?? -1;

  let main = (
    <div className="empty-state">
      <h2>No file is open</h2>
      <p>
        <button className="link-button" onClick={() => void createNote('')}>Create a note</button> ·{' '}
        <button className="link-button" onClick={() => setSwitcherOpen(true)}>Go to a note (Ctrl+O)</button> ·{' '}
        <button className="link-button" onClick={() => openPath(GRAPH_TAB)}>Open the graph</button>
      </p>
    </div>
  );
  if (active && activeType === 'graph') {
    main = <GraphView activePath={null} revision={revision} onOpen={openPath} onCreate={(target) => void createNote('', target)} />;
  } else if (active && activeType === 'cards') {
    main = <FlashcardsView revision={revision} onOpen={openPath} onError={fail} />;
  } else if (active && activeType === 'tasks') {
    // ticking a task edits its note, which an open editor tab must pick up
    main = <TasksView revision={revision} onOpen={openPath} onError={fail} onChanged={() => setReloadToken((t) => t + 1)} />;
  } else if (active && activeType === 'kanban') {
    main = (
      <KanbanView
        revision={revision}
        onOpen={openPath}
        onError={fail}
        onChanged={() => setReloadToken((t) => t + 1)}
        onCreate={(path, content) =>
          void guarded(async () => {
            await api.create(path, content);
            await refresh();
            openPath(path);
          })
        }
      />
    );
  } else if (active && activeType === 'calendar') {
    main = <CalendarView revision={revision} onOpen={openPath} onError={fail} onChanged={() => void refresh()} />;
  } else if (active && activeType === 'habits') {
    main = (
      <HabitsView
        revision={revision}
        onOpen={openPath}
        onError={fail}
        onCreate={(path, content) =>
          void guarded(async () => {
            await api.create(path, content);
            await refresh();
            openPath(path);
          })
        }
      />
    );
  } else if (active && activeType === 'note') {
    main = (
      <NoteView
        key={`${active}:${reloadToken}`}
        path={active}
        mode={mode}
        dark={dark}
        anchor={anchor?.path === active ? anchor : null}
        noteNames={names}
        onOpenLink={openLink}
        onTag={showTag}
        onLightbox={showLightbox}
        onContent={setContent}
        onSaved={onSaved}
        onFilesChanged={refresh}
        onStatus={setStatus}
        onError={fail}
        registerFlush={registerFlush}
        registerEditor={registerEditor}
      />
    );
  } else if (active && activeType === 'drawing') {
    main = (
      <Suspense fallback={<div className="empty-state">Loading drawing tools…</div>}>
        <DrawingEditor key={`${active}:${reloadToken}`} path={active} dark={dark} onStatus={setStatus} registerFlush={registerFlush} />
      </Suspense>
    );
  } else if (active && activeType === 'image') {
    main = (
      <div className="file-viewer">
        <img src={api.rawUrl(active)} alt={baseName(active)} />
        <code>![[{baseName(active)}]]</code>
      </div>
    );
  } else if (active) {
    main = (
      <div className="file-viewer">
        <p>{baseName(active)}</p>
        <a href={api.rawUrl(active)} target="_blank" rel="noopener noreferrer">Open or download</a>
        <code>[[{baseName(active)}]]</code>
      </div>
    );
  }

  return (
    <div className="app">
      <nav className="ribbon">
        <button className={leftPanel === 'files' ? 'on' : ''} title="Files" onClick={() => togglePanel('files')}><Files size={19} /></button>
        <button className={leftPanel === 'search' ? 'on' : ''} title="Search (Ctrl+Shift+F)" onClick={() => togglePanel('search')}><Search size={19} /></button>
        <button className={leftPanel === 'tags' ? 'on' : ''} title="Tags" onClick={() => togglePanel('tags')}><Hash size={19} /></button>
        <button className={active === GRAPH_TAB ? 'on' : ''} title="Graph view" onClick={() => openPath(GRAPH_TAB)}><Waypoints size={19} /></button>
        <button className={active === CARDS_TAB ? 'on' : ''} title="Flashcards" onClick={() => openPath(CARDS_TAB)}><GraduationCap size={19} /></button>
        <button className={active === TASKS_TAB ? 'on' : ''} title="Tasks" onClick={() => openPath(TASKS_TAB)}><ListTodo size={19} /></button>
        <button className={active === KANBAN_TAB ? 'on' : ''} title="Kanban" onClick={() => openPath(KANBAN_TAB)}><SquareKanban size={19} /></button>
        <button className={active === CALENDAR_TAB ? 'on' : ''} title="Calendar" onClick={() => openPath(CALENDAR_TAB)}><Calendar size={19} /></button>
        <button className={active === HABITS_TAB ? 'on' : ''} title="Habits" onClick={() => openPath(HABITS_TAB)}><Repeat size={19} /></button>
        <button title="Quick switcher (Ctrl+O)" onClick={() => setSwitcherOpen(true)}><Zap size={19} /></button>
        <button title="Today's daily note" onClick={() => void openDaily()}><CalendarDays size={19} /></button>
        <span className="ribbon-spacer" />
        <button title="Sync with GitHub / GitLab" onClick={() => setSyncOpen(true)}>
          <RefreshCw size={19} className={syncing ? 'spinning' : ''} />
        </button>
        <a className="ribbon-link" title="Export the whole vault as a zip" href={api.exportUrl} download><Download size={19} /></a>
        <button title={dark ? 'Light theme' : 'Dark theme'} onClick={() => setDark(!dark)}>{dark ? <Sun size={19} /> : <Moon size={19} />}</button>
      </nav>

      {leftPanel && (
        <aside className="sidebar left">
          {leftPanel === 'files' && (
            <>
              <div className="sidebar-actions">
                <button title="New note" onClick={() => void createNote(isFile(active) ? parentOf(active) : '')}><FilePlus size={16} /></button>
                <button title="New drawing" onClick={() => void createDrawing(isFile(active) ? parentOf(active) : '')}><PenTool size={16} /></button>
                <button title="New folder" onClick={() => void createFolder('')}><FolderPlus size={16} /></button>
                <button
                  title="Upload files"
                  onClick={() => {
                    uploadFolder.current = '';
                    uploadInput.current?.click();
                  }}
                >
                  <Upload size={16} />
                </button>
              </div>
              {tree && (
                <FileTree
                  root={tree}
                  activePath={active}
                  onOpen={openPath}
                  onAction={onTreeAction}
                  onMove={(from, folder) => void move(from, joinPath(folder, baseName(from)))}
                  onUploadTo={(files, folder) => void uploadTo(files, folder)}
                />
              )}
            </>
          )}
          {leftPanel === 'search' && <SearchPanel query={searchQuery} onQuery={setSearchQuery} revision={revision} onOpen={openPath} />}
          {leftPanel === 'tags' && <TagsPanel revision={revision} onTag={showTag} />}
        </aside>
      )}

      {(leftPanel || (rightOpen && active && activeType === 'note')) && (
        <div
          className="sidebar-backdrop"
          onClick={() => {
            setLeftPanel(null);
            setRightOpen(false);
          }}
        />
      )}

      <main className="workspace">
        <div className="tab-bar">
          <div className="tabs">
            {tabs.map((tab) => (
              <div
                key={tab}
                className={`tab${tab === active ? ' active' : ''}`}
                title={SPECIAL_TABS[tab] ?? tab}
                onClick={() => openPath(tab)}
                onAuxClick={(e) => e.button === 1 && closeTab(tab)}
              >
                <span className="tab-title">{SPECIAL_TABS[tab] ?? titleOf(tab)}</span>
                <button
                  className="tab-close"
                  title="Close"
                  onClick={(e) => {
                    e.stopPropagation();
                    closeTab(tab);
                  }}
                >
                  <X size={13} />
                </button>
              </div>
            ))}
          </div>
          <div className="tab-bar-actions">
            {activeType === 'note' && (
              <>
                <button className={mode === 'edit' ? 'on' : ''} title="Editor only" onClick={() => setMode('edit')}><Pencil size={16} /></button>
                <button className={mode === 'split' ? 'on' : ''} title="Side by side" onClick={() => setMode('split')}><Columns2 size={16} /></button>
                <button className={mode === 'preview' ? 'on' : ''} title="Reading view" onClick={() => setMode('preview')}><BookOpen size={16} /></button>
                <button title="Export this note" onClick={() => { if (active) void exportFile(active); }}><Download size={16} /></button>
              </>
            )}
            <button className={rightOpen ? 'on' : ''} title="Backlinks and outline" onClick={() => setRightOpen(!rightOpen)}><PanelRight size={16} /></button>
          </div>
        </div>
        <div className="workspace-body">{main}</div>
      </main>

      {rightOpen && active && activeType === 'note' && (
        <aside className="sidebar right">
          <ContextPanel path={active} content={content} revision={revision} onOpen={openPath} onHeading={goToHeading} />
        </aside>
      )}

      <footer className="status-bar">
        <button className="status-button" onClick={() => (syncStatus?.configured ? void runSync().catch((e: Error) => fail(e.message)) : setSyncOpen(true))}>
          <RefreshCw size={12} className={syncing ? 'spinning' : ''} />
          {syncing
            ? 'Syncing…'
            : !syncStatus?.configured
              ? 'Sync not set up'
              : syncStatus.lastResult && !syncStatus.lastResult.ok
                ? 'Last sync failed'
                : pending > 0
                  ? `${pending} change(s) to sync`
                  : 'In sync'}
        </button>
        <span className="status-spacer" />
        {activeType === 'note' && <span>{words} words · {content.length} characters</span>}
        <span>{status}</span>
      </footer>

      <input
        ref={uploadInput}
        type="file"
        multiple
        hidden
        onChange={(e) => {
          const files = Array.from(e.target.files ?? []);
          e.target.value = '';
          if (files.length > 0) void uploadTo(files, uploadFolder.current);
        }}
      />
      {prompt && <PromptDialog request={prompt} />}
      {switcherOpen && (
        <QuickSwitcher notes={names} onOpen={openPath} onCreate={(name) => void createNote('', name)} onClose={() => setSwitcherOpen(false)} />
      )}
      {syncOpen && (
        <SyncDialog
          onClose={() => {
            setSyncOpen(false);
            refreshSyncStatus();
          }}
          onSync={runSync}
        />
      )}
      {lightbox && <Lightbox images={lightbox.images} index={lightbox.index} onClose={() => setLightbox(null)} />}
      {error && (
        <div className="toast" onClick={() => setError(null)}>
          {error}
        </div>
      )}
    </div>
  );
}
