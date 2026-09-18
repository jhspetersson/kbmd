import { ChevronLeft, ChevronRight, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { NoteRef } from '../api';

export function Modal({ title, onClose, children, wide }: { title?: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => event.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal${wide ? ' wide' : ''}`}>
        {title && (
          <div className="modal-title">
            {title}
            <button className="icon-button" onClick={onClose} title="Close">
              <X size={16} />
            </button>
          </div>
        )}
        {children}
      </div>
    </div>
  );
}

export interface PromptRequest {
  title: string;
  initial: string;
  confirmLabel: string;
  resolve: (value: string | null) => void;
}

export function PromptDialog({ request }: { request: PromptRequest }) {
  const [value, setValue] = useState(request.initial);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    // select the name but not the extension, like a file manager does
    const dot = request.initial.lastIndexOf('.');
    const slash = request.initial.lastIndexOf('/');
    input.current?.setSelectionRange(slash + 1, dot > slash ? dot : request.initial.length);
  }, [request]);

  return (
    <Modal title={request.title} onClose={() => request.resolve(null)}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          request.resolve(value.trim() || null);
        }}
      >
        <input ref={input} className="text-input" autoFocus value={value} onChange={(e) => setValue(e.target.value)} />
        <div className="modal-actions">
          <button type="button" onClick={() => request.resolve(null)}>Cancel</button>
          <button type="submit" className="primary">{request.confirmLabel}</button>
        </div>
      </form>
    </Modal>
  );
}

/** Subsequence match; consecutive and early matches score higher. Returns -1 for no match. */
function fuzzyScore(query: string, text: string): number {
  if (!query) return 0;
  const haystack = text.toLowerCase();
  const direct = haystack.indexOf(query);
  if (direct >= 0) return 1000 - direct;
  let score = 0;
  let position = 0;
  let streak = 0;
  for (const char of query) {
    const found = haystack.indexOf(char, position);
    if (found < 0) return -1;
    streak = found === position ? streak + 1 : 0;
    score += 10 + streak * 5 - Math.min(9, found - position);
    position = found + 1;
  }
  return score;
}

interface SwitcherProps {
  notes: NoteRef[];
  onOpen: (path: string) => void;
  onCreate: (name: string) => void;
  onClose: () => void;
}

export function QuickSwitcher({ notes, onOpen, onCreate, onClose }: SwitcherProps) {
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState(0);
  const list = useRef<HTMLDivElement>(null);

  const results = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return notes
      .map((note, order) => ({ note, order, score: Math.max(fuzzyScore(needle, note.title) + 5, fuzzyScore(needle, note.path)) }))
      .filter((entry) => entry.score >= 0)
      .sort((a, b) => b.score - a.score || a.order - b.order)
      .slice(0, 50)
      .map((entry) => entry.note);
  }, [notes, query]);

  const name = query.trim();
  const canCreate = name !== '' && !results.some((note) => note.title.toLowerCase() === name.toLowerCase());
  const total = results.length + (canCreate ? 1 : 0);

  useEffect(() => setSelected(0), [query]);
  useEffect(() => {
    list.current?.children[selected]?.scrollIntoView({ block: 'nearest' });
  }, [selected]);

  const choose = (index: number) => {
    if (index < results.length) onOpen(results[index].path);
    else if (canCreate) onCreate(name);
    onClose();
  };

  return (
    <Modal onClose={onClose}>
      <input
        className="text-input switcher-input"
        autoFocus
        placeholder="Find or create a note…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            setSelected((s) => (total === 0 ? 0 : (s + 1) % total));
          } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setSelected((s) => (total === 0 ? 0 : (s - 1 + total) % total));
          } else if (e.key === 'Enter' && total > 0) {
            e.preventDefault();
            choose(selected);
          }
        }}
      />
      <div className="switcher-list" ref={list}>
        {results.map((note, i) => (
          <div key={note.path} className={`switcher-row${i === selected ? ' selected' : ''}`} onMouseEnter={() => setSelected(i)} onClick={() => choose(i)}>
            <span>{note.title}</span>
            {note.path.includes('/') && <span className="switcher-path">{note.path.substring(0, note.path.lastIndexOf('/'))}</span>}
          </div>
        ))}
        {canCreate && (
          <div
            className={`switcher-row${selected === results.length ? ' selected' : ''}`}
            onMouseEnter={() => setSelected(results.length)}
            onClick={() => choose(results.length)}
          >
            <span>Create “{name}”</span>
            <span className="switcher-path">new note</span>
          </div>
        )}
      </div>
    </Modal>
  );
}

interface LightboxProps {
  images: { src: string; caption: string }[];
  index: number;
  onClose: () => void;
}

export function Lightbox({ images, index: start, onClose }: LightboxProps) {
  const [index, setIndex] = useState(start);
  const step = (delta: number) => setIndex((i) => (i + delta + images.length) % images.length);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
      if (event.key === 'ArrowRight') step(1);
      if (event.key === 'ArrowLeft') step(-1);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onClose, images.length]);

  const image = images[index];
  if (!image) return null;
  return (
    <div className="lightbox" onClick={onClose}>
      <button className="lightbox-close" title="Close"><X size={22} /></button>
      {images.length > 1 && (
        <button className="lightbox-nav left" onClick={(e) => { e.stopPropagation(); step(-1); }} title="Previous">
          <ChevronLeft size={28} />
        </button>
      )}
      <figure onClick={(e) => e.stopPropagation()}>
        <img src={image.src} alt={image.caption} />
        <figcaption>
          {image.caption}
          {images.length > 1 && <span className="lightbox-count"> {index + 1} / {images.length}</span>}
        </figcaption>
      </figure>
      {images.length > 1 && (
        <button className="lightbox-nav right" onClick={(e) => { e.stopPropagation(); step(1); }} title="Next">
          <ChevronRight size={28} />
        </button>
      )}
    </div>
  );
}
