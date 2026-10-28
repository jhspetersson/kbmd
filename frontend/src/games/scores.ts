import { useSyncExternalStore } from 'react';
import { api } from '../api';

/**
 * Best scores. The vault's .games.json (through the API, synced with the vault) is the truth; a copy in
 * localStorage shows at once and stands in while the server cannot be reached.
 */

const CACHE = 'kbmd.games.scores';

type Scores = Record<string, number>;

function cached(): Scores {
  try {
    return JSON.parse(localStorage.getItem(CACHE) ?? '{}') as Scores;
  } catch {
    return {};
  }
}

let scores: Scores = cached();
const listeners = new Set<() => void>();

function publish(next: Scores) {
  scores = next;
  localStorage.setItem(CACHE, JSON.stringify(next));
  listeners.forEach((listener) => listener());
}

/** Fetches the synced scores; after a sync another device's records show up. */
export async function loadScores(): Promise<void> {
  try {
    publish(await api.gameScores());
  } catch {
    // offline or no backend: the cached copy stays
  }
}

/** Keeps a new record locally and sends it to the vault; the server answers with the best it knows. */
export function recordScore(game: string, score: number, lowerIsBetter = false): void {
  const current = scores[game];
  if (score <= 0) return; // nothing achieved, nothing to keep
  if (current !== undefined && (lowerIsBetter ? score >= current : score <= current)) return;
  publish({ ...scores, [game]: score });
  api
    .recordScore(game, score, lowerIsBetter)
    .then((result) => {
      if (scores[game] !== result.best) publish({ ...scores, [game]: result.best });
    })
    .catch(() => undefined);
}

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

/** The best score of a game, 0 when it was never played. */
export function useScore(game: string): number {
  return useSyncExternalStore(subscribe, () => scores[game] ?? 0);
}
