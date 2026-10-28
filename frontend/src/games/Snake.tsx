import { Pause, Play, RotateCcw } from 'lucide-react';
import { useRef } from 'react';
import { isTouch } from '../native';
import { DELTA, Dpad, KEY_DIRS, OPPOSITE, Overlay, randomInt, Stats, useBest, useKeys, useSavedState, useSwipe, useTicker, type Dir } from './common';

const W = 20;
const H = 20;

interface State {
  snake: number[]; // cell indexes, head first
  dir: Dir;
  queue: Dir[]; // turns typed ahead of the next steps
  food: number;
  score: number;
  started: boolean;
  paused: boolean;
  over: boolean;
}

function placeFood(snake: number[]): number {
  const taken = new Set(snake);
  const free: number[] = [];
  for (let i = 0; i < W * H; i++) if (!taken.has(i)) free.push(i);
  return free.length > 0 ? free[randomInt(free.length)] : -1;
}

function fresh(): State {
  const middle = Math.floor(H / 2) * W + Math.floor(W / 2);
  const snake = [middle, middle - 1, middle - 2];
  return { snake, dir: 'right', queue: [], food: placeFood(snake), score: 0, started: false, paused: false, over: false };
}

function step(state: State): State {
  const queue = state.queue.slice();
  const dir = queue.shift() ?? state.dir;
  const head = state.snake[0];
  const x = (head % W) + DELTA[dir].dx;
  const y = Math.floor(head / W) + DELTA[dir].dy;
  if (x < 0 || x >= W || y < 0 || y >= H) return { ...state, dir, queue, over: true };
  const next = y * W + x;
  const eating = next === state.food;
  // the tail moves away in the same step, so running onto it is fine unless the snake grows
  const body = eating ? state.snake : state.snake.slice(0, -1);
  if (body.includes(next)) return { ...state, dir, queue, over: true };
  const snake = [next, ...body];
  return {
    ...state,
    dir,
    queue,
    snake,
    score: eating ? state.score + 1 : state.score,
    food: eating ? placeFood(snake) : state.food,
    over: eating && snake.length === W * H,
  };
}

/** A turn: never straight back into the body, and at most two turns queued. */
function turn(state: State, dir: Dir): State {
  const last = state.queue[state.queue.length - 1] ?? state.dir;
  if (dir === last || dir === OPPOSITE[last] || state.queue.length >= 2) return state;
  return { ...state, queue: [...state.queue, dir], started: true, paused: false };
}

export function Snake() {
  // a game left half-way comes back paused
  const [state, setState] = useSavedState<State>('snake', fresh, (saved) => (saved.started && !saved.over ? { ...saved, paused: true } : saved));
  const [best, record] = useBest('snake');
  const boardRef = useRef<HTMLDivElement>(null);
  const touch = isTouch();

  const running = state.started && !state.paused && !state.over;
  useTicker(running, Math.max(60, 170 - state.score * 4), () =>
    setState((current) => {
      const next = step(current);
      if (next.over) record(next.score);
      return next;
    }),
  );

  const onDir = (dir: Dir) => setState((current) => (current.over ? current : turn(current, dir)));
  const togglePause = () => setState((current) => (current.over ? current : { ...current, started: true, paused: current.started && !current.paused }));
  const restart = () => setState(fresh());

  useKeys((key) => {
    const dir = KEY_DIRS[key];
    if (dir) {
      onDir(dir);
      return true;
    }
    if (key === ' ' || key === 'p' || key === 'Escape') {
      togglePause();
      return true;
    }
    if (key === 'r' || (key === 'Enter' && state.over)) {
      restart();
      return true;
    }
  });
  useSwipe(boardRef, onDir, togglePause);

  const body = new Set(state.snake.slice(1));
  const head = state.snake[0];

  return (
    <div className="game snake">
      <div className="game-toolbar">
        <Stats
          items={[
            { label: 'Score', value: state.score },
            { label: 'Length', value: state.snake.length },
            { label: 'Best', value: best },
          ]}
        />
        <div className="game-actions">
          <button className="icon-button" title={running ? 'Pause (Space)' : 'Play (Space)'} onClick={togglePause} disabled={state.over}>
            {running ? <Pause size={18} /> : <Play size={18} />}
          </button>
          <button className="icon-button" title="New game (R)" onClick={restart}><RotateCcw size={18} /></button>
        </div>
      </div>
      <div className="game-board-wrap">
        <div className="snake-board" ref={boardRef}>
          {Array.from({ length: W * H }, (_, index) => (
            <div
              key={index}
              className={`s-cell${index === head ? ' head' : body.has(index) ? ' body' : index === state.food ? ' food' : ''}`}
            />
          ))}
        </div>
        {state.over && <Overlay title={state.snake.length === W * H ? 'Perfect!' : 'Game over'} detail={`${state.score} eaten`} action="Play again" onAction={restart} />}
        {!state.over && !state.started && <Overlay title="Snake" detail="Arrow keys or a swipe to start" action="Start" onAction={togglePause} />}
        {!state.over && state.started && state.paused && <Overlay title="Paused" action="Resume" onAction={togglePause} />}
      </div>
      {touch && <Dpad onDir={onDir} />}
      <p className="game-help">Arrow keys or swipe to steer; eat to grow, do not hit the walls or yourself. Space pauses.</p>
    </div>
  );
}
