import { CalendarClock, ListTodo, MoreHorizontal, Plus } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, type Task } from '../api';
import { isTouch } from '../native';

interface Props {
  revision: number;
  onOpen: (path: string) => void;
  onChanged: () => void;
  onError: (message: string) => void;
}

type Filter = 'open' | 'done' | 'all';

const DRAG_TYPE = 'application/x-kbmd-task';
const keyOf = (task: Task) => `${task.notePath}:${task.line}`;

const today = () => {
  const d = new Date();
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
};

function dueLabel(due: string, now: string): { text: string; state: 'overdue' | 'today' | 'soon' | 'later' } {
  const days = Math.round((new Date(`${due}T00:00:00`).getTime() - new Date(`${now}T00:00:00`).getTime()) / 86_400_000);
  if (days < 0) return { text: days === -1 ? 'yesterday' : `${-days} days ago`, state: 'overdue' };
  if (days === 0) return { text: 'today', state: 'today' };
  if (days === 1) return { text: 'tomorrow', state: 'soon' };
  if (days < 7) return { text: `in ${days} days`, state: 'soon' };
  return { text: due, state: 'later' };
}

/** Every task in the vault, from every note; ticking one edits its line in place. */
export function TasksView({ revision, onOpen, onChanged, onError }: Props) {
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [filter, setFilter] = useState<Filter>('open');
  const [query, setQuery] = useState('');
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  // reordering: rows are dragged with a mouse; with a finger each row has a move menu instead
  const [dragging, setDragging] = useState<string | null>(null);
  const [over, setOver] = useState<string | null>(null);
  const [menu, setMenu] = useState<string | null>(null);
  const touch = isTouch();

  useEffect(() => {
    if (!menu) return;
    const close = () => setMenu(null);
    window.addEventListener('click', close);
    return () => window.removeEventListener('click', close);
  }, [menu]);

  const load = useCallback(() => {
    api
      .tasks()
      .then(setTasks)
      .catch((e: Error) => onError(e.message));
  }, [onError]);
  useEffect(load, [load, revision]);

  const now = today();
  const visible = useMemo(() => {
    if (!tasks) return [];
    const needle = query.trim().toLowerCase();
    return tasks.filter(
      (t) =>
        (filter === 'all' || (filter === 'done') === t.done) &&
        (!needle || t.text.toLowerCase().includes(needle) || t.noteTitle.toLowerCase().includes(needle) || t.tags.some((tag) => `#${tag}`.includes(needle))),
    );
  }, [tasks, filter, query]);

  const scheduled = useMemo(
    () => visible.filter((t) => t.due && !t.done).sort((a, b) => a.due!.localeCompare(b.due!)),
    [visible],
  );
  const byNote = useMemo(() => {
    const groups = new Map<string, Task[]>();
    for (const task of visible) {
      const list = groups.get(task.notePath) ?? [];
      list.push(task);
      groups.set(task.notePath, list);
    }
    return [...groups.entries()];
  }, [visible]);

  const toggle = async (task: Task) => {
    if (!tasks) return;
    setTasks(tasks.map((t) => (t === task ? { ...t, done: !t.done } : t)));
    try {
      await api.toggleTask(task.notePath, task.line);
      onChanged();
    } catch (e) {
      onError((e as Error).message);
      load();
    }
  };

  /** Moves a task before another one (or to the end of a note's tasks when beforeLine is 0); the notes are edited. */
  const move = async (task: Task, targetPath: string, beforeLine: number) => {
    setMenu(null);
    if (task.notePath === targetPath && task.line === beforeLine) return;
    try {
      await api.moveTask(task.notePath, task.line, targetPath, beforeLine);
      onChanged();
      load();
    } catch (e) {
      onError((e as Error).message);
      load();
    }
  };

  const dragOver = (key: string) => (event: React.DragEvent) => {
    if (!event.dataTransfer.types.includes(DRAG_TYPE)) return;
    event.preventDefault();
    event.stopPropagation();
    setOver(key);
  };

  const drop = (targetPath: string, beforeLine: number) => (event: React.DragEvent) => {
    event.preventDefault();
    event.stopPropagation();
    setOver(null);
    setDragging(null);
    const source = tasks?.find((t) => keyOf(t) === event.dataTransfer.getData(DRAG_TYPE));
    if (source) void move(source, targetPath, beforeLine);
  };

  const add = async () => {
    const text = draft.trim();
    if (!text || busy) return;
    setBusy(true);
    try {
      await api.addTask(text);
      setDraft('');
      onChanged();
      load();
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  if (!tasks) return <div className="empty-state">Loading…</div>;

  /** The move menu for a task among its note's visible siblings, plus the other notes that have tasks. */
  const moveMenu = (task: Task, siblings: Task[], index: number) => (
    <div className="context-menu kanban-menu" onClick={(e) => e.stopPropagation()}>
      {index > 0 && <button onClick={() => void move(task, task.notePath, siblings[index - 1].line)}>Move up</button>}
      {index < siblings.length - 1 && (
        <button onClick={() => void move(task, task.notePath, index + 2 < siblings.length ? siblings[index + 2].line : 0)}>Move down</button>
      )}
      {byNote
        .filter(([path]) => path !== task.notePath)
        .map(([path, list]) => (
          <button key={path} onClick={() => void move(task, path, 0)}>
            Move to {list[0].noteTitle}
          </button>
        ))}
    </div>
  );

  /** A task line; inside a note's group (siblings given) it can be dragged or moved through its menu. */
  const row = (task: Task, showNote: boolean, siblings?: Task[], index = 0) => {
    const due = task.due ? dueLabel(task.due, now) : null;
    const key = keyOf(task);
    const movable = siblings !== undefined;
    return (
      <div
        key={key}
        className={`task-row${task.done ? ' done' : ''}${dragging === key ? ' dragging' : ''}${over === key ? ' over' : ''}`}
        draggable={movable && !touch}
        onDragStart={(e) => {
          e.dataTransfer.setData(DRAG_TYPE, key);
          e.dataTransfer.effectAllowed = 'move';
          setDragging(key);
        }}
        onDragEnd={() => {
          setDragging(null);
          setOver(null);
        }}
        onDragOver={movable ? dragOver(key) : undefined}
        onDrop={movable ? drop(task.notePath, task.line) : undefined}
      >
        <input type="checkbox" checked={task.done} onChange={() => void toggle(task)} />
        <span className="task-text">
          {task.text.replace(/#[\p{L}_][\p{L}\p{N}_/-]*/gu, '').trim()}
          {task.tags.map((tag) => (
            <span key={tag} className="tag">
              #{tag}
            </span>
          ))}
          {due && !task.done && (
            <span className={`task-due ${due.state}`} title={task.due!}>
              <CalendarClock size={12} /> {due.text}
            </span>
          )}
        </span>
        {showNote && (
          <button className="link-button task-note" title={task.notePath} onClick={() => onOpen(task.notePath)}>
            {task.noteTitle}
          </button>
        )}
        {movable && (
          <button
            className="icon-button task-menu"
            title="Move"
            onClick={(e) => {
              e.stopPropagation();
              setMenu(menu === key ? null : key);
            }}
          >
            <MoreHorizontal size={15} />
          </button>
        )}
        {movable && menu === key && moveMenu(task, siblings, index)}
      </div>
    );
  };

  const open = tasks.filter((t) => !t.done).length;

  return (
    <div className="tasks">
      <div className="tasks-header">
        <h2>
          <ListTodo size={22} /> Tasks <span className="count">{open} open</span>
        </h2>
        <div className="habits-range">
          {(['open', 'done', 'all'] as Filter[]).map((f) => (
            <button key={f} className={f === filter ? 'on' : ''} onClick={() => setFilter(f)}>
              {f}
            </button>
          ))}
        </div>
      </div>
      <div className="tasks-tools">
        <form
          className="task-add"
          onSubmit={(e) => {
            e.preventDefault();
            void add();
          }}
        >
          <input className="text-input" placeholder="New task… (goes to Tasks.md)" value={draft} onChange={(e) => setDraft(e.target.value)} />
          <button type="submit" className="icon-button" title="Add task" disabled={busy || !draft.trim()}>
            <Plus size={18} />
          </button>
        </form>
        <input className="text-input" placeholder="Filter…" value={query} onChange={(e) => setQuery(e.target.value)} />
      </div>
      <div className="tasks-scroll">
        {scheduled.length > 0 && (
          <section className="task-group">
            <h3>Scheduled</h3>
            {scheduled.map((task) => row(task, true))}
          </section>
        )}
        {byNote.map(([path, list]) => (
          <section
            key={path}
            className={`task-group${over === `group:${path}` ? ' over' : ''}`}
            onDragOver={dragOver(`group:${path}`)}
            onDragLeave={() => setOver((current) => (current === `group:${path}` ? null : current))}
            onDrop={drop(path, 0)}
          >
            <h3>
              <button className="link-button" onClick={() => onOpen(path)} title={path}>
                {list[0].noteTitle}
              </button>
              <span className="count">{list.length}</span>
            </h3>
            {list.map((task, index) => row(task, false, list, index))}
          </section>
        ))}
        {visible.length === 0 && (
          <div className="empty-state">
            {tasks.length === 0 ? (
              <>
                <p>No tasks yet. Write one in any note:</p>
                <pre>- [ ] Water the plants 📅 2026-09-20 #garden</pre>
                <p>or add one above.</p>
              </>
            ) : (
              'Nothing matches.'
            )}
          </div>
        )}
      </div>
    </div>
  );
}
