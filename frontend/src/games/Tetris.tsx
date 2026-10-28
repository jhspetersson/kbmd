import { ChevronsDown, Pause, Play, RotateCcw } from 'lucide-react';
import { isTouch } from '../native';
import { Dpad, KEY_DIRS, Overlay, Stats, useBest, useKeys, useSavedState, useTicker, type Dir } from './common';

const W = 10;
const H = 20;

type Kind = 'I' | 'O' | 'T' | 'S' | 'Z' | 'J' | 'L';
const KINDS: Kind[] = ['I', 'O', 'T', 'S', 'Z', 'J', 'L'];

const SHAPES: Record<Kind, string[]> = {
  I: ['....', 'IIII', '....', '....'],
  O: ['OO', 'OO'],
  T: ['.T.', 'TTT', '...'],
  S: ['.SS', 'SS.', '...'],
  Z: ['ZZ.', '.ZZ', '...'],
  J: ['J..', 'JJJ', '...'],
  L: ['..L', 'LLL', '...'],
};

interface Offset {
  x: number;
  y: number;
}

function rotateMatrix(rows: string[]): string[] {
  const n = rows.length;
  return Array.from({ length: n }, (_, y) => Array.from({ length: n }, (_, x) => rows[n - 1 - x][y]).join(''));
}

/** The four rotations of every piece as cell offsets, computed once. */
const ROTATIONS: Record<Kind, Offset[][]> = Object.fromEntries(
  KINDS.map((kind) => {
    const result: Offset[][] = [];
    let rows = SHAPES[kind];
    for (let r = 0; r < 4; r++) {
      const cells: Offset[] = [];
      rows.forEach((row, y) => [...row].forEach((ch, x) => ch !== '.' && cells.push({ x, y })));
      result.push(cells);
      rows = rotateMatrix(rows);
    }
    return [kind, result];
  }),
) as Record<Kind, Offset[][]>;

interface Piece {
  kind: Kind;
  rot: number;
  x: number;
  y: number;
}

type Board = (Kind | null)[]; // W × H, row-major

interface State {
  board: Board;
  piece: Piece | null;
  bag: Kind[]; // the 7-bag randomizer: every piece once before any repeats
  next: Kind;
  score: number;
  lines: number;
  over: boolean;
  paused: boolean;
}

function shuffled(): Kind[] {
  const bag = KINDS.slice();
  for (let i = bag.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [bag[i], bag[j]] = [bag[j], bag[i]];
  }
  return bag;
}

function draw(bag: Kind[]): { kind: Kind; bag: Kind[] } {
  const rest = bag.length > 0 ? bag.slice() : shuffled();
  return { kind: rest.shift() as Kind, bag: rest };
}

const cells = (piece: Piece): Offset[] => ROTATIONS[piece.kind][piece.rot].map((o) => ({ x: piece.x + o.x, y: piece.y + o.y }));

function fits(board: Board, piece: Piece): boolean {
  return cells(piece).every((c) => c.x >= 0 && c.x < W && c.y < H && (c.y < 0 || board[c.y * W + c.x] === null));
}

function spawn(state: State): State {
  const drawn = draw(state.bag);
  const size = SHAPES[state.next].length;
  const piece: Piece = { kind: state.next, rot: 0, x: Math.floor((W - size) / 2), y: state.next === 'I' ? -1 : 0 };
  return { ...state, piece, next: drawn.kind, bag: drawn.bag, over: !fits(state.board, piece) };
}

function fresh(): State {
  const drawn = draw(shuffled());
  return spawn({ board: new Array<Kind | null>(W * H).fill(null), piece: null, bag: drawn.bag, next: drawn.kind, score: 0, lines: 0, over: false, paused: false });
}

const level = (lines: number) => Math.floor(lines / 10) + 1;
const LINE_SCORES = [0, 100, 300, 500, 800];

function lock(state: State): State {
  if (!state.piece) return state;
  const board = state.board.slice();
  for (const c of cells(state.piece)) if (c.y >= 0) board[c.y * W + c.x] = state.piece.kind;
  const kept: Board = [];
  let cleared = 0;
  for (let y = H - 1; y >= 0; y--) {
    const row = board.slice(y * W, (y + 1) * W);
    if (row.every((cell) => cell !== null)) cleared++;
    else kept.unshift(...row);
  }
  while (kept.length < W * H) kept.unshift(null);
  const lines = state.lines + cleared;
  return spawn({ ...state, board: kept, piece: null, lines, score: state.score + LINE_SCORES[cleared] * level(state.lines) });
}

const tryMove = (state: State, dx: number, dy: number): State | null => {
  if (!state.piece) return null;
  const piece = { ...state.piece, x: state.piece.x + dx, y: state.piece.y + dy };
  return fits(state.board, piece) ? { ...state, piece } : null;
};

/** Gravity or a soft drop: one row down, or the piece locks where it is. */
function step(state: State, bonus = 0): State {
  const moved = tryMove(state, 0, 1);
  return moved ? { ...moved, score: moved.score + bonus } : lock(state);
}

function hardDrop(state: State): State {
  let current = state;
  let dropped = 0;
  for (let next = tryMove(current, 0, 1); next; next = tryMove(current, 0, 1)) {
    current = next;
    dropped++;
  }
  return lock({ ...current, score: current.score + dropped * 2 });
}

function rotate(state: State, turns: 1 | 3): State {
  if (!state.piece) return state;
  const rotated = { ...state.piece, rot: (state.piece.rot + turns) % 4 };
  // wall kicks: nudge sideways (and up, for a piece lying on the floor) until it fits
  for (const dy of [0, -1]) {
    for (const dx of [0, -1, 1, -2, 2]) {
      const piece = { ...rotated, x: rotated.x + dx, y: rotated.y + dy };
      if (fits(state.board, piece)) return { ...state, piece };
    }
  }
  return state;
}

const shadow = (state: State): Piece | null => {
  if (!state.piece) return null;
  let piece = state.piece;
  while (fits(state.board, { ...piece, y: piece.y + 1 })) piece = { ...piece, y: piece.y + 1 };
  return piece;
};

export function Tetris() {
  // a game left half-way comes back paused
  const [state, setState] = useSavedState<State>('tetris', fresh, (saved) => (saved.over ? saved : { ...saved, paused: true }));
  const [best, record] = useBest('tetris');
  const touch = isTouch();

  const act = (change: (current: State) => State) =>
    setState((current) => {
      if (current.over || current.paused) return current;
      const next = change(current);
      if (next.over) record(next.score);
      return next;
    });
  const togglePause = () => setState((current) => (current.over ? current : { ...current, paused: !current.paused }));
  const restart = () => setState(fresh());

  const currentLevel = level(state.lines);
  useTicker(!state.over && !state.paused, Math.max(80, Math.round(800 * Math.pow(0.85, currentLevel - 1))), () => act((s) => step(s)));

  const onDir = (dir: Dir) => {
    if (dir === 'left') act((s) => tryMove(s, -1, 0) ?? s);
    else if (dir === 'right') act((s) => tryMove(s, 1, 0) ?? s);
    else if (dir === 'down') act((s) => step(s, 1));
    else act((s) => rotate(s, 1));
  };

  useKeys((key) => {
    const dir = KEY_DIRS[key];
    if (dir) {
      onDir(dir);
      return true;
    }
    if (key === ' ') {
      act(hardDrop);
      return true;
    }
    if (key === 'x') {
      act((s) => rotate(s, 1));
      return true;
    }
    if (key === 'z') {
      act((s) => rotate(s, 3));
      return true;
    }
    if (key === 'p' || key === 'Escape') {
      togglePause();
      return true;
    }
    if (key === 'r' || (key === 'Enter' && state.over)) {
      restart();
      return true;
    }
  });

  const view: (Kind | null)[] = state.board.slice();
  const ghost = new Set<number>();
  const falling = new Set<number>();
  const shade = shadow(state);
  if (shade) for (const c of cells(shade)) if (c.y >= 0) ghost.add(c.y * W + c.x);
  if (state.piece) {
    for (const c of cells(state.piece)) {
      if (c.y >= 0) {
        view[c.y * W + c.x] = state.piece.kind;
        falling.add(c.y * W + c.x);
      }
    }
  }
  const preview = SHAPES[state.next];

  return (
    <div className="game tetris">
      <div className="game-toolbar">
        <Stats
          items={[
            { label: 'Score', value: state.score },
            { label: 'Lines', value: state.lines },
            { label: 'Level', value: currentLevel },
            { label: 'Best', value: best },
          ]}
        />
        <div className="game-actions">
          <button className="icon-button" title={state.paused ? 'Resume (P)' : 'Pause (P)'} onClick={togglePause} disabled={state.over}>
            {state.paused ? <Play size={18} /> : <Pause size={18} />}
          </button>
          <button className="icon-button" title="New game (R)" onClick={restart}><RotateCcw size={18} /></button>
        </div>
      </div>
      <div className="tetris-layout">
        <div className="game-board-wrap">
          <div className="tetris-board">
            {view.map((kind, index) => (
              <div key={index} className={`t-cell${kind ? ` ${kind}` : ghost.has(index) ? ' ghost' : ''}${falling.has(index) ? ' falling' : ''}`} />
            ))}
          </div>
          {state.over && <Overlay title="Game over" detail={`${state.score} points · ${state.lines} lines`} action="Play again" onAction={restart} />}
          {state.paused && !state.over && <Overlay title="Paused" action="Resume" onAction={togglePause} />}
        </div>
        <div className="tetris-side">
          <span className="game-stat-label">Next</span>
          <div className="tetris-preview" style={{ gridTemplateColumns: `repeat(${preview.length}, 20px)` }}>
            {preview.flatMap((row, y) => [...row].map((ch, x) => <div key={`${x}:${y}`} className={`t-cell${ch !== '.' ? ` ${state.next}` : ' empty'}`} />))}
          </div>
        </div>
      </div>
      {touch && (
        <Dpad
          onDir={onDir}
          center={
            <button aria-label="Hard drop" onPointerDown={(e) => { e.preventDefault(); act(hardDrop); }}>
              <ChevronsDown size={20} />
            </button>
          }
        />
      )}
      <p className="game-help">
        ← → move, ↑ or X rotate, Z rotate back, ↓ soft drop, Space hard drop, P pause.{touch && ' On a phone use the buttons; the middle one drops.'}
      </p>
    </div>
  );
}
