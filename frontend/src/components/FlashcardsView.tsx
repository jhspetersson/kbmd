import DOMPurify from 'dompurify';
import { Download, GraduationCap } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, titleOf, type Deck, type Rating, type StudyCard } from '../api';
import { highlightCodeBlocks } from '../highlight';

interface Props {
  revision: number;
  onOpen: (path: string) => void;
  onError: (message: string) => void;
}

const RATINGS: { rating: Rating; label: string }[] = [
  { rating: 'AGAIN', label: 'Again' },
  { rating: 'HARD', label: 'Hard' },
  { rating: 'GOOD', label: 'Good' },
  { rating: 'EASY', label: 'Easy' },
];

function intervalLabel(days: number): string {
  if (days === 0) return 'soon';
  if (days < 30) return `${days} d`;
  if (days < 365) return `${Math.round(days / 30)} mo`;
  return `${(days / 365).toFixed(1)} y`;
}

function CardSide({ html }: { html: string }) {
  const safe = useMemo(() => DOMPurify.sanitize(html), [html]);
  const side = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (side.current) void highlightCodeBlocks(side.current);
  }, [safe]);
  return <div className="markdown-body card-side" ref={side} dangerouslySetInnerHTML={{ __html: safe }} />;
}

/** Spaced-repetition review of the cards found in notes tagged #flashcards. */
export function FlashcardsView({ revision, onOpen, onError }: Props) {
  const [decks, setDecks] = useState<Deck[] | null>(null);
  const [session, setSession] = useState<{ deck: string | null; queue: StudyCard[]; done: number } | null>(null);
  const [revealed, setRevealed] = useState(false);

  const loadDecks = useCallback(() => {
    api.decks().then(setDecks).catch((e: Error) => onError(e.message));
  }, [onError]);

  useEffect(loadDecks, [loadDecks, revision]);

  const start = (deck: string | null) => {
    api
      .dueCards(deck)
      .then((queue) => {
        setSession({ deck, queue, done: 0 });
        setRevealed(false);
      })
      .catch((e: Error) => onError(e.message));
  };

  const finish = () => {
    setSession(null);
    loadDecks();
  };

  const current = session?.queue[0];

  const rate = useCallback(
    (rating: Rating) => {
      if (!session || !current) return;
      api
        .review(current.id, rating)
        .then((state) => {
          const rest = session.queue.slice(1);
          // a forgotten card comes back at the end of this session
          const queue = state.interval === 0 ? [...rest, { ...current, fresh: false }] : rest;
          setSession({ ...session, queue, done: session.done + (state.interval === 0 ? 0 : 1) });
          setRevealed(false);
        })
        .catch((e: Error) => onError(e.message));
    },
    [session, current, onError],
  );

  useEffect(() => {
    if (!current) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      // typing in the quick switcher, search box or a dialog must not reveal or rate the card
      const target = event.target as HTMLElement | null;
      if (target && (target.closest('input, textarea, select, [contenteditable="true"]') || target.closest('.modal-backdrop'))) return;
      if (!revealed && (event.key === ' ' || event.key === 'Enter')) {
        event.preventDefault();
        setRevealed(true);
      } else if (revealed && event.key >= '1' && event.key <= '4') {
        rate(RATINGS[Number(event.key) - 1].rating);
      } else if (revealed && event.key === ' ') {
        event.preventDefault();
        rate('GOOD');
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [current, revealed, rate]);

  if (session) {
    if (!current) {
      return (
        <div className="empty-state">
          <h2>Done for now 🎉</h2>
          <p>{session.done} card(s) reviewed.</p>
          <button className="link-button" onClick={finish}>Back to decks</button>
        </div>
      );
    }
    return (
      <div className="flashcards review">
        <div className="review-header">
          <button className="link-button" onClick={finish}>← Decks</button>
          <span>
            {current.deck} · {session.queue.length} left{current.fresh ? ' · new' : ''}
          </span>
          <button className="link-button" onClick={() => onOpen(current.notePath)}>Open {titleOf(current.notePath)}</button>
        </div>
        <div className="review-card">
          <CardSide html={current.frontHtml} />
          {revealed && (
            <>
              <hr />
              <CardSide html={current.backHtml} />
            </>
          )}
        </div>
        <div className="review-actions">
          {!revealed ? (
            <button className="primary" onClick={() => setRevealed(true)}>Show answer <kbd>Space</kbd></button>
          ) : (
            RATINGS.map(({ rating, label }, i) => (
              <button key={rating} className={`rating ${rating.toLowerCase()}`} onClick={() => rate(rating)}>
                <span className="rating-interval">{intervalLabel(current.intervals[rating])}</span>
                {label} <kbd>{i + 1}</kbd>
              </button>
            ))
          )}
        </div>
      </div>
    );
  }

  const totalDue = decks?.reduce((sum, deck) => sum + deck.due + deck.fresh, 0) ?? 0;
  return (
    <div className="flashcards">
      <div className="decks">
        <h2><GraduationCap size={22} /> Flashcards</h2>
        {decks && decks.length === 0 && (
          <div className="deck-help">
            <p>No cards yet. Tag a note with <code>#flashcards</code> (or <code>#flashcards/deck-name</code>) and write cards in it:</p>
            <pre>{`#flashcards/spanish

hola::hello
gato:::cat                  (also asks cat → gato)

A longer question
?
Its answer, on as many lines as needed

The capital of Spain is ==Madrid==.   (cloze)`}</pre>
          </div>
        )}
        {decks && decks.length > 0 && (
          <>
            <table className="deck-table">
              <thead>
                <tr><th>Deck</th><th>New</th><th>Due</th><th>Total</th><th /></tr>
              </thead>
              <tbody>
                {decks.map((deck) => (
                  <tr key={deck.name}>
                    <td>
                      {deck.name}
                      <span className="deck-notes">
                        {deck.notes.map((path) => (
                          <button key={path} className="link-button" title={`Open ${path}`} onClick={() => onOpen(path)}>
                            {titleOf(path)}
                          </button>
                        ))}
                      </span>
                    </td>
                    <td className="fresh">{deck.fresh}</td>
                    <td className="due">{deck.due}</td>
                    <td>{deck.total}</td>
                    <td className="deck-actions">
                      <button disabled={deck.due + deck.fresh === 0} onClick={() => start(deck.name)}>Study</button>
                      <a href={api.ankiExportUrl(deck.name)} download title="Export this deck for Anki (File > Import)"><Download size={15} /></a>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="deck-footer">
              <button className="primary" disabled={totalDue === 0} onClick={() => start(null)}>
                Study everything ({totalDue})
              </button>
              <a href={api.ankiExportUrl(null)} download>Export all for Anki</a>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
