import { useEffect, useState, type ReactNode } from 'react';
import { api, type Hit, type SearchResponse } from '../api';

function escapeRegExp(text: string) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function Highlighted({ text, terms }: { text: string; terms: string[] }) {
  const useful = terms.filter((term) => term.length > 0);
  if (useful.length === 0) return <>{text}</>;
  const pattern = new RegExp(`(${useful.map(escapeRegExp).join('|')})`, 'gi');
  const parts: ReactNode[] = text.split(pattern).map((part, i) => (i % 2 === 1 ? <mark key={i}>{part}</mark> : part));
  return <>{parts}</>;
}

function HitList({ hits, terms, onOpen }: { hits: Hit[]; terms: string[]; onOpen: (path: string) => void }) {
  return (
    <>
      {hits.map((hit) => (
        <div key={hit.path} className="hit" onClick={() => onOpen(hit.path)}>
          <div className="hit-title">
            <Highlighted text={hit.title} terms={terms} />
          </div>
          {hit.path.includes('/') && <div className="hit-path">{hit.path.substring(0, hit.path.lastIndexOf('/'))}</div>}
          {hit.snippets.map((snippet) => (
            <div key={snippet.line} className="hit-snippet">
              <Highlighted text={snippet.text} terms={terms} />
            </div>
          ))}
        </div>
      ))}
    </>
  );
}

interface SearchProps {
  query: string;
  onQuery: (query: string) => void;
  revision: number;
  onOpen: (path: string) => void;
}

export function SearchPanel({ query, onQuery, revision, onOpen }: SearchProps) {
  const [result, setResult] = useState<SearchResponse | null>(null);

  useEffect(() => {
    if (!query.trim()) {
      setResult(null);
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(() => {
      api
        .search(query)
        .then((response) => !cancelled && setResult(response))
        .catch(() => undefined);
    }, 150);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [query, revision]);

  return (
    <div className="panel">
      <input
        className="panel-input"
        autoFocus
        placeholder='Search…  "phrase"  tag:name  path:folder'
        value={query}
        onChange={(e) => onQuery(e.target.value)}
      />
      <div className="panel-scroll">
        {result && <div className="panel-caption">{result.hits.length === 0 ? 'No results' : `${result.hits.length} result(s)`}</div>}
        {result && <HitList hits={result.hits} terms={result.terms} onOpen={onOpen} />}
      </div>
    </div>
  );
}

export function TagsPanel({ revision, onTag }: { revision: number; onTag: (tag: string) => void }) {
  const [tags, setTags] = useState<Record<string, number>>({});

  useEffect(() => {
    api.tags().then(setTags).catch(() => undefined);
  }, [revision]);

  const entries = Object.entries(tags).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  return (
    <div className="panel">
      <div className="panel-scroll">
        {entries.length === 0 && <div className="panel-empty">No tags yet. Write #something in a note.</div>}
        {entries.map(([tag, count]) => (
          <div key={tag} className="tag-row" onClick={() => onTag(tag)}>
            <span className="tag">#{tag}</span>
            <span className="count">{count}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

interface Heading {
  level: number;
  text: string;
  line: number;
}

export function outline(content: string): Heading[] {
  const headings: Heading[] = [];
  let fenced = false;
  content.split('\n').forEach((line, i) => {
    if (/^\s*(```|~~~)/.test(line)) fenced = !fenced;
    const match = !fenced && /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(line);
    if (match) headings.push({ level: match[1].length, text: match[2], line: i + 1 });
  });
  return headings;
}

interface ContextProps {
  path: string;
  content: string;
  revision: number;
  onOpen: (path: string) => void;
  onHeading: (text: string, line: number) => void;
}

/** Right sidebar: what links here, and the headings of the open note. */
export function ContextPanel({ path, content, revision, onOpen, onHeading }: ContextProps) {
  const [backlinks, setBacklinks] = useState<Hit[]>([]);

  useEffect(() => {
    let cancelled = false;
    api
      .backlinks(path)
      .then((hits) => !cancelled && setBacklinks(hits))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [path, revision]);

  const headings = outline(content);
  const top = Math.min(6, ...headings.map((h) => h.level));
  return (
    <div className="panel">
      <div className="panel-scroll">
        <div className="panel-heading">Backlinks · {backlinks.length}</div>
        {backlinks.length === 0 && <div className="panel-empty">No other note links here.</div>}
        <HitList hits={backlinks} terms={[]} onOpen={onOpen} />

        <div className="panel-heading">Outline</div>
        {headings.length === 0 && <div className="panel-empty">No headings.</div>}
        {headings.map((heading) => (
          <div
            key={heading.line}
            className="outline-row"
            style={{ paddingLeft: 12 + (heading.level - top) * 12 }}
            onClick={() => onHeading(heading.text, heading.line)}
          >
            {heading.text}
          </div>
        ))}
      </div>
    </div>
  );
}
