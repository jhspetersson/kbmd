import { RotateCcw, Undo2 } from 'lucide-react';
import { useRef } from 'react';
import { KEY_DIRS, Overlay, randomInt, Stats, useBest, useKeys, useSavedState, useSwipe, type Dir } from './common';

const SIZE = 4;
type Board = number[]; // SIZE × SIZE, row-major, 0 is empty

interface State {
  board: Board;
  score: number;
  previous: { board: Board; score: number } | null;
  keepGoing: boolean; // past 2048 and still playing
}

function addTile(board: Board): Board {
  const empty = board.map((value, index) => (value === 0 ? index : -1)).filter((index) => index >= 0);
  if (empty.length === 0) return board;
  const next = board.slice();
  next[empty[randomInt(empty.length)]] = Math.random() < 0.9 ? 2 : 4;
  return next;
}

function fresh(): State {
  return { board: addTile(addTile(new Array<number>(SIZE * SIZE).fill(0))), score: 0, previous: null, keepGoing: false };
}

/** Slides one line towards its start, merging equal neighbours once. */
function slide(line: number[]): { line: number[]; gained: number } {
  const tiles = line.filter((value) => value !== 0);
  const out: number[] = [];
  let gained = 0;
  for (let i = 0; i < tiles.length; i++) {
    if (i + 1 < tiles.length && tiles[i] === tiles[i + 1]) {
      out.push(tiles[i] * 2);
      gained += tiles[i] * 2;
      i++;
    } else {
      out.push(tiles[i]);
    }
  }
  while (out.length < SIZE) out.push(0);
  return { line: out, gained };
}

/** The cell indexes of every line in the order they slide for a direction. */
function lines(dir: Dir): number[][] {
  const result: number[][] = [];
  for (let i = 0; i < SIZE; i++) {
    const line: number[] = [];
    for (let j = 0; j < SIZE; j++) {
      line.push(dir === 'left' || dir === 'right' ? i * SIZE + j : j * SIZE + i);
    }
    if (dir === 'right' || dir === 'down') line.reverse();
    result.push(line);
  }
  return result;
}

function move(board: Board, dir: Dir): { board: Board; gained: number; moved: boolean } {
  const next = board.slice();
  let gained = 0;
  let moved = false;
  for (const indexes of lines(dir)) {
    const slid = slide(indexes.map((index) => board[index]));
    gained += slid.gained;
    indexes.forEach((index, position) => {
      if (next[index] !== slid.line[position]) moved = true;
      next[index] = slid.line[position];
    });
  }
  return { board: next, gained, moved };
}

function canMove(board: Board): boolean {
  if (board.includes(0)) return true;
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      const value = board[y * SIZE + x];
      if (x + 1 < SIZE && board[y * SIZE + x + 1] === value) return true;
      if (y + 1 < SIZE && board[(y + 1) * SIZE + x] === value) return true;
    }
  }
  return false;
}

export function Game2048() {
  const [state, setState] = useSavedState<State>('2048', fresh);
  const [best, record] = useBest('2048');
  const boardRef = useRef<HTMLDivElement>(null);

  const play = (dir: Dir) => {
    setState((current) => {
      const result = move(current.board, dir);
      if (!result.moved) return current;
      const score = current.score + result.gained;
      record(score);
      return { ...current, board: addTile(result.board), score, previous: { board: current.board, score: current.score } };
    });
  };
  const undo = () => setState((current) => (current.previous ? { ...current, ...current.previous, previous: null } : current));
  const restart = () => setState(fresh());

  useKeys((key) => {
    const dir = KEY_DIRS[key];
    if (dir) {
      play(dir);
      return true;
    }
    if (key === 'z' || key === 'Backspace') {
      undo();
      return true;
    }
    if (key === 'r' || key === 'Enter') {
      restart();
      return true;
    }
  });
  useSwipe(boardRef, play);

  const over = !canMove(state.board);
  const won = !state.keepGoing && state.board.some((value) => value >= 2048);
  const highest = Math.max(...state.board);

  return (
    <div className="game g2048">
      <div className="game-toolbar">
        <Stats
          items={[
            { label: 'Score', value: state.score },
            { label: 'Best', value: best },
          ]}
        />
        <div className="game-actions">
          <button className="icon-button" title="Undo (Z)" disabled={!state.previous} onClick={undo}><Undo2 size={18} /></button>
          <button className="icon-button" title="New game (R)" onClick={restart}><RotateCcw size={18} /></button>
        </div>
      </div>
      <div className="game-board-wrap">
        <div className="g2048-board" ref={boardRef}>
          {state.board.map((value, index) => (
            <div key={index} className={`g2048-cell${value ? ' tile' : ''}`} data-value={value > 2048 ? 'super' : value}>
              {value || ''}
            </div>
          ))}
        </div>
        {over && <Overlay title="Game over" detail={`Score ${state.score} · highest tile ${highest}`} action="Try again" onAction={restart} />}
        {!over && won && <Overlay title="You made 2048!" detail={`Score ${state.score}`} action="Keep going" onAction={() => setState((current) => ({ ...current, keepGoing: true }))} />}
      </div>
      <p className="game-help">Arrow keys or swipe to slide the tiles. Equal tiles merge. Z undoes a move.</p>
    </div>
  );
}
