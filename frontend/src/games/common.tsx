import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp } from 'lucide-react';
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type Dispatch,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
  type RefObject,
  type SetStateAction,
} from 'react';
import { recordScore, useScore } from './scores';

export type Dir = 'up' | 'down' | 'left' | 'right';

export const KEY_DIRS: Record<string, Dir> = {
  ArrowUp: 'up',
  ArrowDown: 'down',
  ArrowLeft: 'left',
  ArrowRight: 'right',
  w: 'up',
  s: 'down',
  a: 'left',
  d: 'right',
};

export const OPPOSITE: Record<Dir, Dir> = { up: 'down', down: 'up', left: 'right', right: 'left' };

export const DELTA: Record<Dir, { dx: number; dy: number }> = {
  up: { dx: 0, dy: -1 },
  down: { dx: 0, dy: 1 },
  left: { dx: -1, dy: 0 },
  right: { dx: 1, dy: 0 },
};

export const randomInt = (n: number) => Math.floor(Math.random() * n);

/** The best score of a game (synced through the vault, see scores.ts); `record` keeps the better one. */
export function useBest(game: string, lowerIsBetter = false): [number, (score: number) => void] {
  const best = useScore(game);
  const record = useCallback((score: number) => recordScore(game, score, lowerIsBetter), [game, lowerIsBetter]);
  return [best, record];
}

/**
 * Game state that survives leaving the tab and reloading the page (localStorage, this device only). `revive`
 * adjusts what was saved, for instance to start a timed game paused.
 */
export function useSavedState<T>(game: string, fresh: () => T, revive: (saved: T) => T = (saved) => saved): [T, Dispatch<SetStateAction<T>>] {
  const key = `kbmd.games.${game}.state`;
  const [state, setState] = useState<T>(() => {
    try {
      const saved = localStorage.getItem(key);
      return saved ? revive(JSON.parse(saved) as T) : fresh();
    } catch {
      return fresh();
    }
  });
  useEffect(() => localStorage.setItem(key, JSON.stringify(state)), [key, state]);
  return [state, setState];
}

/**
 * Keyboard input for a game: plain keys only (the app's Ctrl shortcuts stay untouched), and nothing while the user
 * types in a field somewhere else. The handler returns true for keys it used, so they do not scroll the page.
 */
export function useKeys(handler: (key: string, event: KeyboardEvent) => boolean | void) {
  const latest = useRef(handler);
  latest.current = handler;
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      const target = event.target as HTMLElement | null;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
      if (latest.current(event.key, event)) event.preventDefault();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}

/** Swipes over an element, for finger play; a tap (short move) calls onTap instead. */
export function useSwipe(ref: RefObject<HTMLElement | null>, onSwipe: (dir: Dir) => void, onTap?: () => void) {
  const latest = useRef({ onSwipe, onTap });
  latest.current = { onSwipe, onTap };
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    let start: { x: number; y: number; id: number } | null = null;
    const down = (event: PointerEvent) => {
      if (event.pointerType === 'mouse' && event.button !== 0) return;
      start = { x: event.clientX, y: event.clientY, id: event.pointerId };
    };
    const up = (event: PointerEvent) => {
      if (!start || event.pointerId !== start.id) return;
      const dx = event.clientX - start.x;
      const dy = event.clientY - start.y;
      start = null;
      if (Math.max(Math.abs(dx), Math.abs(dy)) < 24) {
        latest.current.onTap?.();
        return;
      }
      latest.current.onSwipe(Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'right' : 'left') : dy > 0 ? 'down' : 'up');
    };
    const cancel = () => {
      start = null;
    };
    // the finger may well leave the board before it lifts, so the end is watched everywhere
    element.addEventListener('pointerdown', down);
    window.addEventListener('pointerup', up);
    window.addEventListener('pointercancel', cancel);
    return () => {
      element.removeEventListener('pointerdown', down);
      window.removeEventListener('pointerup', up);
      window.removeEventListener('pointercancel', cancel);
    };
  }, [ref]);
}

/** A repeating step while the game runs; the delay may change between steps (faster levels). */
export function useTicker(active: boolean, delay: number, step: () => void) {
  const latest = useRef(step);
  latest.current = step;
  useEffect(() => {
    if (!active) return;
    const timer = window.setInterval(() => latest.current(), delay);
    return () => window.clearInterval(timer);
  }, [active, delay]);
}

/** Score boxes above a board. */
export function Stats({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <div className="game-stats">
      {items.map((item) => (
        <div key={item.label} className="game-stat">
          <span className="game-stat-label">{item.label}</span>
          <span className="game-stat-value">{item.value}</span>
        </div>
      ))}
    </div>
  );
}

/** A message over the board (game over, paused) with a button under it. */
export function Overlay({ title, detail, action, onAction }: { title: string; detail?: ReactNode; action: string; onAction: () => void }) {
  return (
    <div className="game-overlay">
      <strong>{title}</strong>
      {detail && <span>{detail}</span>}
      <button className="primary" onClick={onAction}>
        {action}
      </button>
    </div>
  );
}

/** Arrow buttons for phones; the slot in the middle is for a game-specific action. */
export function Dpad({ onDir, center }: { onDir: (dir: Dir) => void; center?: ReactNode }) {
  const press = (dir: Dir) => (event: ReactPointerEvent) => {
    event.preventDefault();
    onDir(dir);
  };
  return (
    <div className="dpad">
      <button className="dpad-up" aria-label="Up" onPointerDown={press('up')}><ArrowUp size={20} /></button>
      <button className="dpad-left" aria-label="Left" onPointerDown={press('left')}><ArrowLeft size={20} /></button>
      <div className="dpad-center">{center}</div>
      <button className="dpad-right" aria-label="Right" onPointerDown={press('right')}><ArrowRight size={20} /></button>
      <button className="dpad-down" aria-label="Down" onPointerDown={press('down')}><ArrowDown size={20} /></button>
    </div>
  );
}
