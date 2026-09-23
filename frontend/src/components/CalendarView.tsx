import { Cake, CalendarPlus, ChevronLeft, ChevronRight, Download, ListTodo, Repeat, Smartphone } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { api, type Occurrence } from '../api';
import { isNarrow, isNative } from '../native';

interface Props {
  revision: number;
  onOpen: (path: string) => void;
  onChanged: () => void;
  onError: (message: string) => void;
}

type RepeatChoice = 'none' | 'daily' | 'weekly' | 'monthly' | 'yearly' | 'birthday';

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

const iso = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
const parse = (date: string) => new Date(`${date}T00:00:00`);
const addDays = (date: string, days: number) => {
  const d = parse(date);
  d.setDate(d.getDate() + days);
  return iso(d);
};

/** Dates of the 6-week grid that shows a month, Monday first. */
function gridDays(year: number, month: number): string[] {
  const first = new Date(year, month, 1);
  const offset = (first.getDay() + 6) % 7;
  const start = new Date(year, month, 1 - offset);
  return Array.from({ length: 42 }, (_, i) => iso(new Date(start.getFullYear(), start.getMonth(), start.getDate() + i)));
}

/** Occurrences that span days are listed on each of them. */
function byDay(items: Occurrence[], days: string[]): Map<string, Occurrence[]> {
  const map = new Map<string, Occurrence[]>();
  for (const day of days) map.set(day, []);
  for (const item of items) {
    const last = item.endDate ?? item.date;
    for (let day = item.date; day <= last; day = addDays(day, 1)) {
      map.get(day)?.push(item);
    }
  }
  return map;
}

function label(item: Occurrence): string {
  const age = item.kind === 'birthday' && item.detail ? ` (${item.detail})` : '';
  return `${item.time ? `${item.time} ` : ''}${item.title}${age}`;
}

/** Month view of everything dated in the vault, plus the phone's own calendars in the Android app. */
export function CalendarView({ revision, onOpen, onChanged, onError }: Props) {
  const today = iso(new Date());
  const [cursor, setCursor] = useState(() => ({ year: Number(today.slice(0, 4)), month: Number(today.slice(5, 7)) - 1 }));
  const [selected, setSelected] = useState(today);
  const [items, setItems] = useState<Occurrence[] | null>(null);
  const [phone, setPhone] = useState<boolean>(() => {
    try {
      return isNative && localStorage.getItem('kbmd.phoneCalendar') === 'true';
    } catch {
      return false;
    }
  });
  const [form, setForm] = useState<{ title: string; time: string; repeat: RepeatChoice; end: string } | null>(null);
  /** The days swept over while a finger or mouse button is held down on the grid. */
  const [drag, setDrag] = useState<{ start: string; end: string } | null>(null);
  const dragRef = useRef(drag);
  dragRef.current = drag;
  const [busy, setBusy] = useState(false);

  const days = useMemo(() => gridDays(cursor.year, cursor.month), [cursor]);
  const from = days[0];
  const to = days[days.length - 1];

  const load = useCallback(() => {
    api
      .calendar(from, to)
      .then((loaded) => {
        let all = loaded;
        if (phone && window.KbmdNative?.phoneEvents) {
          const raw = window.KbmdNative.phoneEvents(from, to);
          if (raw === null) {
            window.KbmdNative.requestCalendarAccess?.(); // answered with a refresh command
          } else {
            try {
              const events = JSON.parse(raw) as { date: string; endDate: string | null; time: string | null; endTime: string | null; title: string; calendar: string }[];
              all = [
                ...loaded,
                ...events.map((e) => ({
                  date: e.date,
                  endDate: e.endDate,
                  time: e.time,
                  endTime: e.endTime,
                  title: e.title,
                  notePath: '',
                  line: 0,
                  kind: 'phone' as const,
                  recurring: false,
                  detail: e.calendar,
                  rrule: null,
                })),
              ];
            } catch {
              // a phone answer we cannot read: skip it
            }
          }
        }
        setItems(all);
      })
      .catch((e: Error) => onError(e.message));
  }, [from, to, phone, onError]);
  useEffect(load, [load, revision]);

  const perDay = useMemo(() => (items ? byDay(items, days) : new Map<string, Occurrence[]>()), [items, days]);

  const step = (months: number) => {
    const d = new Date(cursor.year, cursor.month + months, 1);
    setCursor({ year: d.getFullYear(), month: d.getMonth() });
  };
  const goToday = () => {
    setCursor({ year: Number(today.slice(0, 4)), month: Number(today.slice(5, 7)) - 1 });
    setSelected(today);
  };

  const togglePhone = () => {
    const next = !phone;
    setPhone(next);
    try {
      localStorage.setItem('kbmd.phoneCalendar', String(next));
    } catch {
      // storage unavailable: the choice lasts for this session
    }
    if (next && window.KbmdNative?.hasCalendarAccess && !window.KbmdNative.hasCalendarAccess()) {
      window.KbmdNative.requestCalendarAccess?.();
    }
  };

  const dayAt = (x: number, y: number): string | null => {
    const cell = document.elementFromPoint(x, y)?.closest<HTMLElement>('.calendar-cell');
    return cell?.dataset.day ?? null;
  };

  // one swipe (or mouse drag) across cells selects a span of days and opens the form for a multi-day event
  const dragStart = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return;
    const day = (event.target as HTMLElement).closest<HTMLElement>('.calendar-cell')?.dataset.day;
    if (day) setDrag({ start: day, end: day });
  };
  const dragMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const active = dragRef.current;
    if (!active || (event.buttons & 1) === 0) return;
    const day = dayAt(event.clientX, event.clientY);
    if (day && day !== active.end) setDrag({ ...active, end: day });
  };
  const dragging = drag !== null;
  useEffect(() => {
    if (!dragging) return;
    const end = (event: globalThis.PointerEvent) => {
      const active = dragRef.current;
      setDrag(null);
      if (!active || event.type === 'pointercancel') return; // the browser took the gesture over for scrolling
      const last = dayAt(event.clientX, event.clientY) ?? active.end;
      if (last === active.start) return; // a plain tap: the cell's own click selects it
      const [first, final] = last < active.start ? [last, active.start] : [active.start, last];
      setSelected(first);
      setForm({ title: '', time: '', repeat: 'none', end: final });
    };
    window.addEventListener('pointerup', end);
    window.addEventListener('pointercancel', end);
    return () => {
      window.removeEventListener('pointerup', end);
      window.removeEventListener('pointercancel', end);
    };
  }, [dragging]);
  const range = drag && drag.start !== drag.end ? (drag.end < drag.start ? [drag.end, drag.start] : [drag.start, drag.end]) : null;

  const spec = (): string => {
    if (!form) return '';
    const time = form.time.trim() ? ` ${form.time.trim()}` : '';
    const d = parse(selected);
    switch (form.repeat) {
      case 'none':
        return `${selected}${form.end > selected ? `..${form.end}` : ''}${time}`;
      case 'daily':
        return `every day${time} from ${selected}`;
      case 'weekly':
        return `every ${DOW[d.getDay()]}${time} from ${selected}`;
      case 'monthly':
        return `every month ${d.getDate()}${time} from ${selected}`;
      case 'yearly':
        return `every year ${selected.slice(5)}${time}`;
      case 'birthday':
        return `birthday ${selected}`;
    }
  };

  const add = async () => {
    if (!form || !form.title.trim() || busy) return;
    setBusy(true);
    try {
      await api.addEvent(spec(), form.title.trim());
      setForm(null);
      onChanged();
      load();
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const selectedItems = perDay.get(selected) ?? [];
  const selectedDate = parse(selected);

  return (
    <div className="calendar">
      <div className="calendar-header">
        <h2>
          <button className="icon-button" title="Previous month" onClick={() => step(-1)}>
            <ChevronLeft size={18} />
          </button>
          <span className="calendar-month">
            {MONTHS[cursor.month]} {cursor.year}
          </span>
          <button className="icon-button" title="Next month" onClick={() => step(1)}>
            <ChevronRight size={18} />
          </button>
          <button className="calendar-today" onClick={goToday}>
            Today
          </button>
        </h2>
        <div className="calendar-tools">
          {isNative && (
            <label className="calendar-phone" title="Show the phone's calendar events">
              <input type="checkbox" checked={phone} onChange={togglePhone} /> <Smartphone size={14} /> Phone calendar
            </label>
          )}
          <a className="link-button" href={api.calendarExportUrl} download title="Download all events as .ics (with repeats)">
            <Download size={14} /> .ics
          </a>
        </div>
      </div>

      <div className="calendar-body">
        <div className="calendar-grid" onPointerDown={dragStart} onPointerMove={dragMove}>
          {WEEKDAYS.map((d) => (
            <div key={d} className="calendar-weekday">
              {d}
            </div>
          ))}
          {days.map((day) => {
            const list = perDay.get(day) ?? [];
            const inMonth = Number(day.slice(5, 7)) - 1 === cursor.month;
            const shown = list.slice(0, isNarrow() ? 2 : 3);
            const inRange = range !== null && day >= range[0] && day <= range[1];
            return (
              <button
                key={day}
                data-day={day}
                className={`calendar-cell${inMonth ? '' : ' outside'}${day === today ? ' today' : ''}${day === selected ? ' selected' : ''}${inRange ? ' in-range' : ''}`}
                onClick={() => setSelected(day)}
              >
                <span className="calendar-daynum">{Number(day.slice(8, 10))}</span>
                <span className="calendar-pills">
                  {shown.map((item, i) => (
                    <span key={i} className={`calendar-pill ${item.kind}`} title={label(item)}>
                      {isNarrow() ? '' : item.kind === 'daily' ? 'Daily note' : item.title}
                    </span>
                  ))}
                  {list.length > shown.length && <span className="calendar-more">+{list.length - shown.length}</span>}
                </span>
              </button>
            );
          })}
        </div>

        <aside className="calendar-day">
          <h3>
            {WEEKDAYS[(selectedDate.getDay() + 6) % 7]}, {selectedDate.getDate()} {MONTHS[selectedDate.getMonth()]}
            {selected === today && <span className="count">today</span>}
          </h3>
          {items === null ? (
            <p className="panel-empty">Loading…</p>
          ) : selectedItems.length === 0 ? (
            <p className="panel-empty">Nothing planned.</p>
          ) : (
            <ul className="calendar-list">
              {selectedItems.map((item, i) => (
                <li key={i} className={`calendar-item ${item.kind}`}>
                  <span className="calendar-time">{item.time ? `${item.time}${item.endTime ? `–${item.endTime}` : ''}` : 'all day'}</span>
                  <span className="calendar-item-body">
                    <span className="calendar-title">
                      {item.kind === 'birthday' && <Cake size={13} />}
                      {item.kind === 'task' && <ListTodo size={13} />}
                      {item.kind === 'phone' && <Smartphone size={13} />}
                      {item.recurring && <Repeat size={12} />}
                      {item.title}
                      {item.kind === 'birthday' && item.detail && <span className="count"> turns {item.detail}</span>}
                      {item.endDate && <span className="count"> until {item.endDate}</span>}
                    </span>
                    <span className="calendar-item-actions">
                      {item.notePath && (
                        <button className="link-button" onClick={() => onOpen(item.notePath)}>
                          {item.kind === 'daily' ? 'Open daily note' : 'Open note'}
                        </button>
                      )}
                      {item.kind === 'phone' && item.detail && <span className="count">{item.detail}</span>}
                      {isNative && (item.kind === 'event' || item.kind === 'birthday' || item.kind === 'task') && (
                        <button
                          className="link-button"
                          onClick={() => window.KbmdNative?.addToCalendar?.(item.title, item.date, item.time ?? '', item.endTime ?? '', item.rrule ?? '')}
                        >
                          Add to phone calendar
                        </button>
                      )}
                    </span>
                  </span>
                </li>
              ))}
            </ul>
          )}

          {form ? (
            <form
              className="calendar-form"
              onSubmit={(e) => {
                e.preventDefault();
                void add();
              }}
            >
              <input className="text-input" autoFocus placeholder="What?" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
              <div className="form-row">
                <input className="text-input narrow" placeholder="HH:MM" value={form.time} onChange={(e) => setForm({ ...form, time: e.target.value })} />
                {form.repeat === 'none' && (
                  <label className="calendar-until" title="Last day of a multi-day event; drag across the grid to pick a span">
                    to
                    <input className="text-input" type="date" min={selected} value={form.end} onChange={(e) => setForm({ ...form, end: e.target.value })} />
                  </label>
                )}
                <select className="text-input" value={form.repeat} onChange={(e) => setForm({ ...form, repeat: e.target.value as RepeatChoice })}>
                  <option value="none">once</option>
                  <option value="daily">every day</option>
                  <option value="weekly">every {DOW[selectedDate.getDay()]}</option>
                  <option value="monthly">every month on the {selectedDate.getDate()}.</option>
                  <option value="yearly">every year</option>
                  <option value="birthday">birthday (born this day)</option>
                </select>
              </div>
              <div className="field-help">
                Written to <code>Calendar.md</code> as <code>- {spec()} …</code>
              </div>
              <div className="modal-actions">
                <button type="button" onClick={() => setForm(null)}>
                  Cancel
                </button>
                <button type="submit" className="primary" disabled={busy || !form.title.trim()}>
                  Add
                </button>
              </div>
            </form>
          ) : (
            <button className="kanban-add-button" onClick={() => setForm({ title: '', time: '', repeat: 'none', end: '' })}>
              <CalendarPlus size={14} /> Add event on this day
            </button>
          )}
          <p className="habits-footer">
            Events come from notes tagged <code>#calendar</code>: <code>- 2026-09-25 14:30 Dentist</code>, <code>- 2026-10-03..2026-10-05 Trip</code>,{' '}
            <code>- every Mon,Wed 07:00 Gym</code>, <code>- every month 1 Rent</code>, <code>- birthday 1990-03-14 Mom</code>. Daily notes and tasks with a
            due date show up too. Drag across days in the grid to add a multi-day event.
          </p>
        </aside>
      </div>
    </div>
  );
}
