import { ArrowLeft, Blocks, Bomb, Gamepad2, Grid3x3, Worm, type LucideIcon } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { Game2048 } from './Game2048';
import { Minesweeper } from './Minesweeper';
import { loadScores, useScore } from './scores';
import { Snake } from './Snake';
import { Tetris } from './Tetris';

type GameId = '2048' | 'minesweeper' | 'tetris' | 'snake';

interface Game {
  id: GameId;
  name: string;
  blurb: string;
  icon: LucideIcon;
  /** The score key (see scores.ts) and how to show it on the card. */
  best: { key: string; format: (value: number) => string };
  component: () => ReactNode;
}

const GAMES: Game[] = [
  {
    id: '2048',
    name: '2048',
    blurb: 'Slide and merge the tiles up to 2048.',
    icon: Grid3x3,
    best: { key: '2048', format: (n) => `${n} points` },
    component: () => <Game2048 />,
  },
  {
    id: 'minesweeper',
    name: 'Minesweeper',
    blurb: 'Clear the field without stepping on a mine.',
    icon: Bomb,
    best: { key: 'mines-beginner', format: (n) => `${n}s on Beginner` },
    component: () => <Minesweeper />,
  },
  {
    id: 'tetris',
    name: 'Tetris',
    blurb: 'Stack the falling pieces and clear lines.',
    icon: Blocks,
    best: { key: 'tetris', format: (n) => `${n} points` },
    component: () => <Tetris />,
  },
  {
    id: 'snake',
    name: 'Snake',
    blurb: 'Eat, grow, and stay away from the walls.',
    icon: Worm,
    best: { key: 'snake', format: (n) => `${n} eaten` },
    component: () => <Snake />,
  },
];

const CURRENT = 'kbmd.games.current';

interface Props {
  /** Bumped after a sync: the scores file may have changed. */
  revision: number;
}

function GameCard({ game, onOpen }: { game: Game; onOpen: () => void }) {
  const best = useScore(game.best.key);
  return (
    <button className="game-card" onClick={onOpen}>
      <span className="game-card-icon"><game.icon size={28} /></span>
      <span className="game-card-name">{game.name}</span>
      <span className="game-card-blurb">{game.blurb}</span>
      <span className="game-card-best">{best > 0 ? `Best: ${game.best.format(best)}` : 'Not played yet'}</span>
    </button>
  );
}

/**
 * A short break: four classics, all in the browser. Each game keeps its state on this device while you are away;
 * best scores live in the vault's .games.json and sync with it.
 */
export function GamesView({ revision }: Props) {
  const [current, setCurrent] = useState<Game | null>(() => GAMES.find((game) => game.id === localStorage.getItem(CURRENT)) ?? null);

  useEffect(() => void loadScores(), [revision]);
  useEffect(() => {
    if (current) localStorage.setItem(CURRENT, current.id);
    else localStorage.removeItem(CURRENT);
  }, [current]);

  if (!current) {
    return (
      <div className="games">
        <h2>
          <Gamepad2 size={22} /> Games
        </h2>
        <div className="games-menu">
          {GAMES.map((game) => (
            <GameCard key={game.id} game={game} onOpen={() => setCurrent(game)} />
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="games">
      <div className="games-header">
        <button className="link-button games-back" onClick={() => setCurrent(null)}>
          <ArrowLeft size={16} /> All games
        </button>
        <h2>
          <current.icon size={22} /> {current.name}
        </h2>
      </div>
      {current.component()}
    </div>
  );
}
