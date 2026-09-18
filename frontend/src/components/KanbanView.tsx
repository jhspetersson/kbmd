import { CalendarClock, MoreHorizontal, Plus, SquareKanban } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { api, type Board, type Card, type Column } from '../api';
import { isTouch } from '../native';

interface Props {
  revision: number;
  onOpen: (path: string) => void;
  onCreate: (path: string, content: string) => void;
  onChanged: () => void;
  onError: (message: string) => void;
}

const TEMPLATE = `#kanban

## To do

- [ ] First card
- [ ] Another card 📅 2026-10-01

## Doing

## Done

Every "## heading" is a column, every list item under it a card. Drag cards between columns (or use a card's menu);
a card moved into "Done" is checked off. Cards are ordinary tasks, so they show up in the Tasks tab too.
`;

const DRAG_TYPE = 'application/x-kbmd-card';

/** Kanban boards: notes tagged #kanban, one column per "## heading". Moving a card rewrites the note. */
export function KanbanView({ revision, onOpen, onCreate, onChanged, onError }: Props) {
  const [boards, setBoards] = useState<Board[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState<string | null>(null);
  const [menu, setMenu] = useState<number | null>(null);
  const [adding, setAdding] = useState<{ column: string; text: string } | null>(null);
  const touch = isTouch();

  const load = useCallback(() => {
    api
      .boards()
      .then((loaded) => {
        setBoards(loaded);
        setMenu(null);
      })
      .catch((e: Error) => onError(e.message));
  }, [onError]);
  useEffect(load, [load, revision]);

  useEffect(() => {
    if (menu === null) return;
    const close = () => setMenu(null);
    window.addEventListener('click', close);
    return () => window.removeEventListener('click', close);
  }, [menu]);

  if (!boards) return <div className="empty-state">Loading…</div>;

  const board = boards.find((b) => b.path === selected) ?? boards[0];

  if (!board) {
    return (
      <div className="kanban">
        <div className="habits-help">
          <h2>
            <SquareKanban size={22} /> Kanban
          </h2>
          <p className="deck-help">
            A board is a note tagged <code>#kanban</code>: every <code>## heading</code> is a column and the list items under it
            are its cards. Moving a card moves its lines in the note; a card moved into a column called <em>Done</em> is
            checked off.
          </p>
          <pre>{TEMPLATE.split('\n').slice(0, 10).join('\n')}</pre>
          <button className="primary" onClick={() => onCreate('Board.md', TEMPLATE)}>
            Create a board
          </button>
        </div>
      </div>
    );
  }

  const apply = (updated: Board) => {
    setBoards(boards.map((b) => (b.path === updated.path ? updated : b)));
    setMenu(null); // lines shift after an edit, so a menu keyed by line must not survive it
    onChanged();
  };

  const move = async (line: number, column: string, position: number) => {
    try {
      apply(await api.moveCard(board.path, line, column, position));
    } catch (e) {
      onError((e as Error).message);
      load();
    }
  };

  const toggle = async (card: Card) => {
    try {
      await api.toggleTask(board.path, card.line);
      load();
      onChanged();
    } catch (e) {
      onError((e as Error).message);
      load();
    }
  };

  const addCard = async () => {
    if (!adding || !adding.text.trim()) {
      setAdding(null);
      return;
    }
    try {
      apply(await api.addCard(board.path, adding.column, adding.text));
      setAdding({ column: adding.column, text: '' });
    } catch (e) {
      onError((e as Error).message);
    }
  };

  /** Position among the column's cards other than the one being moved. */
  const positionOf = (column: Column, beforeLine: number | null, moving: number) => {
    const others = column.cards.filter((c) => c.line !== moving);
    if (beforeLine === null) return others.length;
    const index = others.findIndex((c) => c.line === beforeLine);
    return index < 0 ? others.length : index;
  };

  const drop = (column: Column, beforeLine: number | null) => (event: React.DragEvent) => {
    event.preventDefault();
    event.stopPropagation();
    setOver(null);
    const line = Number(event.dataTransfer.getData(DRAG_TYPE));
    setDragging(null);
    if (!line) return;
    void move(line, column.name, positionOf(column, beforeLine, line));
  };

  const dragOver = (key: string) => (event: React.DragEvent) => {
    if (!event.dataTransfer.types.includes(DRAG_TYPE)) return;
    event.preventDefault();
    event.stopPropagation();
    setOver(key);
  };

  const cardMenu = (column: Column, card: Card, index: number) => (
    <div className="context-menu kanban-menu" onClick={(e) => e.stopPropagation()}>
      {index > 0 && <button onClick={() => void move(card.line, column.name, index - 1)}>Move up</button>}
      {index < column.cards.length - 1 && <button onClick={() => void move(card.line, column.name, index + 1)}>Move down</button>}
      {board.columns
        .filter((c) => c !== column)
        .map((c) => (
          <button key={c.name} onClick={() => void move(card.line, c.name, c.cards.length)}>
            Move to {c.name}
          </button>
        ))}
    </div>
  );

  return (
    <div className="kanban">
      <div className="kanban-header">
        <h2>
          <SquareKanban size={22} />
          {boards.length > 1 ? (
            <select value={board.path} onChange={(e) => setSelected(e.target.value)}>
              {boards.map((b) => (
                <option key={b.path} value={b.path}>
                  {b.title}
                </option>
              ))}
            </select>
          ) : (
            board.title
          )}
        </h2>
        <button className="link-button" onClick={() => onOpen(board.path)}>
          Open note
        </button>
      </div>
      <div className="kanban-columns">
        {board.columns.map((column) => (
          <section
            key={column.line}
            className={`kanban-column${over === column.name ? ' over' : ''}`}
            onDragOver={dragOver(column.name)}
            onDragLeave={() => setOver((current) => (current === column.name ? null : current))}
            onDrop={drop(column, null)}
          >
            <h3>
              {column.name} <span className="count">{column.cards.length}</span>
            </h3>
            <div className="kanban-cards">
              {column.cards.map((card, index) => {
                const key = `${column.name}:${card.line}`;
                return (
                  <div
                    key={card.line}
                    className={`kanban-card${card.done ? ' done' : ''}${dragging === card.line ? ' dragging' : ''}${over === key ? ' over' : ''}`}
                    draggable={!touch}
                    onDragStart={(e) => {
                      e.dataTransfer.setData(DRAG_TYPE, String(card.line));
                      e.dataTransfer.effectAllowed = 'move';
                      setDragging(card.line);
                    }}
                    onDragEnd={() => {
                      setDragging(null);
                      setOver(null);
                    }}
                    onDragOver={dragOver(key)}
                    onDrop={drop(column, card.line)}
                  >
                    {card.task && <input type="checkbox" checked={card.done} onChange={() => void toggle(card)} />}
                    <span className="kanban-card-text">
                      {card.text}
                      {card.due && (
                        <span className="task-due later">
                          <CalendarClock size={12} /> {card.due}
                        </span>
                      )}
                    </span>
                    <button
                      className="icon-button kanban-card-menu"
                      title="Move"
                      onClick={(e) => {
                        e.stopPropagation();
                        setMenu(menu === card.line ? null : card.line);
                      }}
                    >
                      <MoreHorizontal size={15} />
                    </button>
                    {menu === card.line && cardMenu(column, card, index)}
                  </div>
                );
              })}
            </div>
            {adding?.column === column.name ? (
              <form
                className="kanban-add"
                onSubmit={(e) => {
                  e.preventDefault();
                  void addCard();
                }}
              >
                <input
                  className="text-input"
                  autoFocus
                  placeholder="Card text"
                  value={adding.text}
                  onChange={(e) => setAdding({ column: column.name, text: e.target.value })}
                  onKeyDown={(e) => e.key === 'Escape' && setAdding(null)}
                  onBlur={() => !adding.text.trim() && setAdding(null)}
                />
              </form>
            ) : (
              <button className="kanban-add-button" onClick={() => setAdding({ column: column.name, text: '' })}>
                <Plus size={14} /> Add card
              </button>
            )}
          </section>
        ))}
        {board.columns.length === 0 && (
          <div className="empty-state">
            This board has no columns yet: add <code>## To do</code>, <code>## Doing</code>, <code>## Done</code> headings to the note.
          </div>
        )}
      </div>
    </div>
  );
}
