import { ListTree } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { outline } from './Panels';

interface Props {
  content: string;
  onPick: (text: string, line: number) => void;
}

/** A drop-down of the note's headings: the outline within reach in the tab bar, also on a phone where the side panel is hidden. */
export function HeadingMenu({ content, onPick }: Props) {
  const [open, setOpen] = useState(false);
  const wrapper = useRef<HTMLDivElement>(null);
  const headings = outline(content);

  useEffect(() => {
    if (!open) return;
    const onDown = (event: PointerEvent) => {
      if (!wrapper.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    window.addEventListener('pointerdown', onDown);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('pointerdown', onDown);
      window.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const top = Math.min(6, ...headings.map((h) => h.level));
  return (
    <div className="heading-menu" ref={wrapper}>
      <button className={open ? 'on' : ''} title="Jump to heading" disabled={headings.length === 0} onClick={() => setOpen(!open)}>
        <ListTree size={16} />
      </button>
      {open && (
        <div className="heading-menu-list">
          {headings.map((heading) => (
            <button
              key={heading.line}
              style={{ paddingLeft: 10 + (heading.level - top) * 12 }}
              title={heading.text}
              onClick={() => {
                setOpen(false);
                onPick(heading.text, heading.line);
              }}
            >
              {heading.text}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
