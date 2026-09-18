import { CalendarClock, ListTodo, Plus } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, type Task } from '../api';

interface Props {
  revision: number;
  onOpen: (path: string) => void;
  onChanged: () => void;
  onError: (message: string) => void;
}

type Filter = 'open' | 'done' | 'all';

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

  const row = (task: Task, showNote: boolean) => {
    const due = task.due ? dueLabel(task.due, now) : null;
    return (
      <div key={`${task.notePath}:${task.line}`} className={`task-row${task.done ? ' done' : ''}`}>
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
          <section key={path} className="task-group">
            <h3>
              <button className="link-button" onClick={() => onOpen(path)} title={path}>
                {list[0].noteTitle}
              </button>
              <span className="count">{list.length}</span>
            </h3>
            {list.map((task) => row(task, false))}
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
