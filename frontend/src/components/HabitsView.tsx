import { Check, Flame, Repeat } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api, type Habit, type HabitBoard } from '../api';
import { isNarrow } from '../native';

interface Props {
  revision: number;
  onOpen: (path: string) => void;
  onCreate: (path: string, content: string) => void;
  onError: (message: string) => void;
}

const TEMPLATE = `#habits

- Exercise
- Read 20 pages
- Meditate (daily)
- Call a friend (2x/week)

One habit per list item. "(3x/week)" sets a weekly target; without it the habit is daily.
Check days off in the Habits tab; progress is kept in .habits.json and synced with the vault.
`;

const WEEKDAYS = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'];

function dayLabel(date: string): { weekday: string; day: number; monday: boolean } {
  const d = new Date(`${date}T00:00:00`);
  const index = (d.getDay() + 6) % 7;
  return { weekday: WEEKDAYS[index], day: d.getDate(), monday: index === 0 };
}

function rate(habit: Habit, days: string[]): number {
  if (days.length === 0) return 0;
  // a habit with a weekly target is judged against that target, not against every day
  const expected = Math.max(1, Math.round((days.length * habit.weeklyTarget) / 7));
  return Math.min(100, Math.round((habit.done.length / expected) * 100));
}

/** Habits × days grid; tapping a cell checks the day off. Habits come from notes tagged #habits. */
export function HabitsView({ revision, onOpen, onCreate, onError }: Props) {
  const [board, setBoard] = useState<HabitBoard | null>(null);
  const [days, setDays] = useState(() => (isNarrow() ? 7 : 21));
  const [busy, setBusy] = useState<string | null>(null);
  const scroller = useRef<HTMLDivElement>(null);

  const load = useCallback(() => {
    api
      .habits(days)
      .then(setBoard)
      .catch((e: Error) => onError(e.message));
  }, [days, onError]);

  useEffect(load, [load, revision]);
  // the most recent days matter most: start scrolled to today
  useEffect(() => {
    const element = scroller.current;
    if (element) element.scrollLeft = element.scrollWidth;
  }, [board?.days.length]);

  const toggle = async (habit: Habit, date: string) => {
    const key = `${habit.id}@${date}`;
    if (busy || !board) return;
    setBusy(key);
    // shown at once; the server's answer settles it
    setBoard({
      ...board,
      habits: board.habits.map((h) =>
        h.id === habit.id ? { ...h, done: h.done.includes(date) ? h.done.filter((d) => d !== date) : [...h.done, date] } : h,
      ),
    });
    try {
      await api.toggleHabit(habit.id, date);
      load();
    } catch (e) {
      onError((e as Error).message);
      load();
    } finally {
      setBusy(null);
    }
  };

  if (!board) return <div className="empty-state">Loading…</div>;

  const today = board.days[board.days.length - 1];

  if (board.habits.length === 0) {
    return (
      <div className="habits">
        <div className="habits-help">
          <h2>
            <Repeat size={22} /> Habits
          </h2>
          <p className="deck-help">
            Habits are the list items of any note tagged <code>#habits</code>. Add <code>(3x/week)</code> after a habit for a
            weekly target; otherwise it counts daily. Check-ins are stored in <code>.habits.json</code> in the vault and sync
            with it.
          </p>
          <pre>{TEMPLATE.split('\n').slice(0, 6).join('\n')}</pre>
          <button className="primary" onClick={() => onCreate('Habits.md', TEMPLATE)}>
            Create a Habits note
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="habits">
      <div className="habits-header">
        <h2>
          <Repeat size={22} /> Habits
        </h2>
        <div className="habits-range">
          {[7, 14, 21, 30].map((n) => (
            <button key={n} className={n === days ? 'on' : ''} onClick={() => setDays(n)}>
              {n} days
            </button>
          ))}
        </div>
      </div>
      <div className="habits-scroll" ref={scroller}>
        <table className="habit-grid">
          <thead>
            <tr>
              <th className="habit-name" />
              {board.days.map((date) => {
                const label = dayLabel(date);
                return (
                  <th key={date} className={`habit-day${date === today ? ' today' : ''}${label.monday ? ' monday' : ''}`} title={date}>
                    <span className="habit-weekday">{label.weekday}</span>
                    <span className="habit-daynum">{label.day}</span>
                  </th>
                );
              })}
              <th className="habit-stats">streak · week · rate</th>
            </tr>
          </thead>
          <tbody>
            {board.habits.map((habit) => {
              const done = new Set(habit.done);
              const weekTarget = habit.weeklyTarget;
              return (
                <tr key={habit.id}>
                  <td className="habit-name">
                    <button className="link-button" title={`Open ${habit.notePath}`} onClick={() => onOpen(habit.notePath)}>
                      {habit.name}
                    </button>
                    {weekTarget < 7 && <span className="habit-target">{weekTarget}×/week</span>}
                  </td>
                  {board.days.map((date) => {
                    const checked = done.has(date);
                    const label = dayLabel(date);
                    return (
                      <td key={date} className={`habit-cell${date === today ? ' today' : ''}${label.monday ? ' monday' : ''}`}>
                        <button
                          className={`habit-check${checked ? ' done' : ''}`}
                          title={`${habit.name} · ${date}`}
                          aria-pressed={checked}
                          disabled={busy !== null}
                          onClick={() => void toggle(habit, date)}
                        >
                          {checked && <Check size={14} />}
                        </button>
                      </td>
                    );
                  })}
                  <td className="habit-stats">
                    <span className="habit-streak" title="Current streak">
                      <Flame size={13} /> {habit.streak}
                    </span>
                    <span className={habit.week >= weekTarget ? 'met' : ''} title="This week">
                      {habit.week}/{weekTarget}
                    </span>
                    <span title="Completion over the shown days">{rate(habit, board.days)}%</span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="habits-footer">
        {board.habits.length} habit(s) from notes tagged <code>#habits</code>. Weeks start on Monday.
      </p>
    </div>
  );
}
