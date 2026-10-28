import { Bomb, Flag, RotateCcw } from 'lucide-react';
import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react';
import { isTouch } from '../native';
import { Overlay, randomInt, Stats, useBest, useSavedState } from './common';

interface Level {
  id: string;
  name: string;
  w: number;
  h: number;
  mines: number;
}

const LEVELS: Level[] = [
  { id: 'beginner', name: 'Beginner', w: 9, h: 9, mines: 10 },
  { id: 'intermediate', name: 'Intermediate', w: 16, h: 16, mines: 40 },
  { id: 'expert', name: 'Expert', w: 30, h: 16, mines: 99 },
];

interface Cell {
  mine: boolean;
  open: boolean;
  flag: boolean;
  count: number;
}

interface State {
  level: Level;
  cells: Cell[];
  placed: boolean; // mines are placed on the first click, so it is never a mine
  status: 'play' | 'won' | 'lost';
  hit: number; // the mine that was stepped on
  seconds: number;
}

function fresh(level: Level): State {
  return {
    level,
    cells: Array.from({ length: level.w * level.h }, () => ({ mine: false, open: false, flag: false, count: 0 })),
    placed: false,
    status: 'play',
    hit: -1,
    seconds: 0,
  };
}

function neighbours(level: Level, index: number): number[] {
  const x = index % level.w;
  const y = Math.floor(index / level.w);
  const result: number[] = [];
  for (let dy = -1; dy <= 1; dy++) {
    for (let dx = -1; dx <= 1; dx++) {
      if (dx === 0 && dy === 0) continue;
      const nx = x + dx;
      const ny = y + dy;
      if (nx >= 0 && nx < level.w && ny >= 0 && ny < level.h) result.push(ny * level.w + nx);
    }
  }
  return result;
}

/** Puts the mines anywhere except the first clicked cell and its neighbours, then counts. */
function place(state: State, safe: number): State {
  const { level } = state;
  const cells = state.cells.map((cell) => ({ ...cell }));
  const forbidden = new Set([safe, ...neighbours(level, safe)]);
  let remaining = level.mines;
  while (remaining > 0) {
    const index = randomInt(cells.length);
    if (forbidden.has(index) || cells[index].mine) continue;
    cells[index].mine = true;
    remaining--;
  }
  cells.forEach((cell, index) => {
    cell.count = neighbours(level, index).filter((n) => cells[n].mine).length;
  });
  return { ...state, cells, placed: true };
}

function open(state: State, start: number[]): State {
  const cells = state.cells.map((cell) => ({ ...cell }));
  const stack = start.filter((index) => !cells[index].open && !cells[index].flag);
  let hit = -1;
  while (stack.length > 0) {
    const index = stack.pop() as number;
    const cell = cells[index];
    if (cell.open || cell.flag) continue;
    cell.open = true;
    if (cell.mine) {
      if (hit < 0) hit = index;
      continue;
    }
    if (cell.count === 0) {
      for (const n of neighbours(state.level, index)) if (!cells[n].open && !cells[n].flag) stack.push(n);
    }
  }
  if (hit >= 0) {
    // the end: show every mine, and which flags were wrong
    cells.forEach((cell) => {
      if (cell.mine) cell.open = true;
    });
    return { ...state, cells, status: 'lost', hit };
  }
  const won = cells.every((cell) => cell.mine || cell.open);
  if (won) cells.forEach((cell) => (cell.flag = cell.mine));
  return { ...state, cells, status: won ? 'won' : 'play' };
}

function toggleFlag(state: State, index: number): State {
  const cells = state.cells.slice();
  if (cells[index].open) return state;
  cells[index] = { ...cells[index], flag: !cells[index].flag };
  return { ...state, cells };
}

/** A reveal: an unopened cell opens; an open number with all its mines flagged opens the rest around it (chord). */
function reveal(state: State, index: number): State {
  const cell = state.cells[index];
  if (cell.flag) return state;
  if (!cell.open) return open(state.placed ? state : place(state, index), [index]);
  const around = neighbours(state.level, index);
  const flags = around.filter((n) => state.cells[n].flag).length;
  return flags === cell.count ? open(state, around) : state;
}

const LONG_PRESS = 350;

export function Minesweeper() {
  const [state, setState] = useSavedState<State>('minesweeper', () => fresh(LEVELS[0]));
  const [flagMode, setFlagMode] = useState(false);
  const [best, record] = useBest(`mines-${state.level.id}`, true);
  const press = useRef<{ index: number; timer: number; held: boolean } | null>(null);
  const mouse = useRef(!isTouch()); // a right-click flags only from a mouse: on a phone the long press already did

  const { seconds } = state;
  const running = state.status === 'play' && state.placed;
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => setState((s) => ({ ...s, seconds: Math.min(999, s.seconds + 1) })), 1000);
    return () => window.clearInterval(timer);
  }, [running, setState]);
  useEffect(() => {
    if (state.status === 'won') record(Math.max(1, seconds));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.status]);

  const restart = (level: Level = state.level) => setState(fresh(level));

  const act = (index: number, flag: boolean) => {
    if (state.status !== 'play') return;
    setState((current) => (flag ? toggleFlag(current, index) : reveal(current, index)));
  };

  // a long press flags, like on every phone; a short tap follows the flag mode switch
  const onPointerDown = (index: number) => (event: ReactPointerEvent) => {
    mouse.current = event.pointerType === 'mouse';
    if (mouse.current) return;
    if (press.current) window.clearTimeout(press.current.timer);
    const timer = window.setTimeout(() => {
      if (press.current && press.current.index === index) {
        press.current.held = true;
        act(index, true);
        if (navigator.vibrate) navigator.vibrate(20);
      }
    }, LONG_PRESS);
    press.current = { index, timer, held: false };
  };
  const onPointerEnd = () => {
    if (press.current) window.clearTimeout(press.current.timer);
  };
  const onClick = (index: number) => () => {
    if (press.current?.index === index && press.current.held) {
      press.current = null;
      return;
    }
    press.current = null;
    act(index, flagMode);
  };

  const flagged = state.cells.filter((cell) => cell.flag).length;
  const { level } = state;

  return (
    <div className="game minesweeper">
      <div className="game-toolbar">
        <Stats
          items={[
            { label: 'Mines', value: level.mines - flagged },
            { label: 'Time', value: seconds },
            { label: 'Best', value: best ? `${best}s` : '–' },
          ]}
        />
        <div className="game-actions">
          <select value={level.id} onChange={(e) => restart(LEVELS.find((l) => l.id === e.target.value) ?? LEVELS[0])} title="Difficulty">
            {LEVELS.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name} · {l.w}×{l.h}, {l.mines} mines
              </option>
            ))}
          </select>
          <button
            className={`icon-button${flagMode ? ' on' : ''}`}
            title={flagMode ? 'Tapping places flags; tap again to dig' : 'Tapping digs; tap to place flags instead'}
            aria-pressed={flagMode}
            onClick={() => setFlagMode(!flagMode)}
          >
            <Flag size={18} />
          </button>
          <button className="icon-button" title="New game" onClick={() => restart()}><RotateCcw size={18} /></button>
        </div>
      </div>
      <div className="game-board-wrap mines-wrap">
        <div
          className={`mines-board${state.status !== 'play' ? ' finished' : ''}`}
          style={{ gridTemplateColumns: `repeat(${level.w}, var(--mine-cell))` }}
          onContextMenu={(e) => e.preventDefault()}
        >
          {state.cells.map((cell, index) => {
            let className = 'mine-cell';
            let content: ReactNode = null;
            if (cell.open) {
              className += ' open';
              if (cell.mine) {
                className += index === state.hit ? ' hit' : ' mine';
                content = <Bomb size={14} />;
              } else if (cell.count > 0) {
                className += ` n${cell.count}`;
                content = cell.count;
              }
            } else if (cell.flag) {
              className += state.status === 'lost' && !cell.mine ? ' wrong' : ' flagged';
              content = <Flag size={13} />;
            }
            return (
              <button
                key={index}
                className={className}
                onClick={onClick(index)}
                onContextMenu={() => mouse.current && act(index, true)}
                onPointerDown={onPointerDown(index)}
                onPointerUp={onPointerEnd}
                onPointerLeave={onPointerEnd}
                onPointerCancel={onPointerEnd}
              >
                {content}
              </button>
            );
          })}
        </div>
        {state.status === 'lost' && <Overlay title="Boom!" detail={`${seconds}s`} action="Try again" onAction={() => restart()} />}
        {state.status === 'won' && (
          <Overlay title="Cleared!" detail={`${seconds}s${seconds <= best ? ' · best time' : ''}`} action="Play again" onAction={() => restart()} />
        )}
      </div>
      <p className="game-help">Click to dig, right-click or long-press to flag. Click an open number with all its mines flagged to open the rest around it.</p>
    </div>
  );
}
