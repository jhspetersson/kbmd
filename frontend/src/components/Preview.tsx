import DOMPurify from 'dompurify';
import { useEffect, useMemo, useRef } from 'react';
import { api } from '../api';
import { renderDrawingSvg } from '../drawing';
import { highlightCodeBlocks } from '../highlight';

interface Props {
  html: string;
  dark: boolean;
  /** Heading text to scroll to once the content is rendered; the tick changes on every request, so repeats scroll again. */
  anchor: { text: string; tick: number } | null;
  onOpenLink: (path: string, target: string, anchor: string | null) => void;
  onTag: (tag: string) => void;
  /** The checkbox's position among all checkboxes, and the source line the server attributed it to. */
  onToggleTask: (index: number, line: number | null) => void;
  onLightbox: (images: { src: string; caption: string }[], index: number) => void;
}

let mermaidCounter = 0;

async function renderMermaid(container: HTMLElement, dark: boolean) {
  const blocks = Array.from(container.querySelectorAll<HTMLElement>('pre.mermaid'));
  if (blocks.length === 0) return;
  const { default: mermaid } = await import('mermaid');
  mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: dark ? 'dark' : 'default' });
  for (const block of blocks) {
    const source = block.textContent ?? '';
    const id = `mermaid-${++mermaidCounter}`;
    try {
      const { svg } = await mermaid.render(id, source);
      const figure = document.createElement('div');
      figure.className = 'mermaid-diagram';
      figure.innerHTML = svg;
      block.replaceWith(figure);
    } catch (error) {
      document.getElementById(`d${id}`)?.remove(); // mermaid leaves its scratch element behind on errors
      document.getElementById(id)?.remove();
      block.classList.add('mermaid-error');
      block.textContent = `${source.trimEnd()}\n\n⚠ ${error instanceof Error ? error.message : String(error)}`;
    }
  }
}

async function renderDrawings(container: HTMLElement, dark: boolean) {
  const embeds = Array.from(container.querySelectorAll<HTMLAnchorElement>('a.drawing-embed'));
  for (const embed of embeds) {
    const path = embed.dataset.path;
    if (!path) continue;
    try {
      const svg = await renderDrawingSvg((await api.read(path)).content, dark);
      svg.removeAttribute('width');
      svg.removeAttribute('height');
      embed.textContent = '';
      embed.appendChild(svg);
      embed.classList.add('drawing-rendered');
      embed.title = 'Open drawing';
    } catch {
      // keep the plain link
    }
  }
}

export function Preview({ html, dark, anchor, onOpenLink, onTag, onToggleTask, onLightbox }: Props) {
  const container = useRef<HTMLDivElement>(null);

  // notes can arrive from a shared repository, so their HTML is not trusted
  const safeHtml = useMemo(() => DOMPurify.sanitize(html, { ADD_ATTR: ['target'] }), [html]);

  useEffect(() => {
    const element = container.current;
    if (!element) return;
    element.innerHTML = safeHtml;
    element.querySelectorAll<HTMLInputElement>('input[type=checkbox]').forEach((box) => box.removeAttribute('disabled'));
    void renderMermaid(element, dark);
    void renderDrawings(element, dark);
    void highlightCodeBlocks(element);
  }, [safeHtml, dark]);

  useEffect(() => {
    if (!anchor || !container.current) return;
    const wanted = anchor.text.trim().toLowerCase();
    const heading = Array.from(container.current.querySelectorAll('h1,h2,h3,h4,h5,h6')).find(
      (h) => h.textContent?.trim().toLowerCase() === wanted,
    );
    heading?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [anchor, safeHtml]);

  const handleClick = (event: React.MouseEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;

    if (target instanceof HTMLInputElement && target.type === 'checkbox') {
      const boxes = Array.from(container.current!.querySelectorAll('input[type=checkbox]'));
      onToggleTask(boxes.indexOf(target), target.dataset.line ? Number(target.dataset.line) : null);
      return;
    }
    if (target instanceof HTMLImageElement) {
      const images = Array.from(container.current!.querySelectorAll<HTMLImageElement>('img'));
      onLightbox(
        images.map((img) => ({ src: img.src, caption: img.closest('figure')?.querySelector('figcaption')?.textContent ?? img.alt })),
        images.indexOf(target),
      );
      return;
    }
    const link = target.closest('a');
    if (!link) return;
    if (link.classList.contains('tag')) {
      event.preventDefault();
      onTag(link.dataset.tag ?? '');
    } else if (link.classList.contains('wikilink')) {
      event.preventDefault();
      onOpenLink(link.dataset.path ?? '', link.dataset.target ?? '', link.dataset.anchor ?? null);
    } else if (link.classList.contains('anchor-link')) {
      event.preventDefault();
      const id = decodeURIComponent(link.getAttribute('href')!.substring(1));
      container.current!.querySelector(`[id="${CSS.escape(id)}"]`)?.scrollIntoView({ behavior: 'smooth' });
    }
  };

  return <div className="preview markdown-body" ref={container} onClick={handleClick} />;
}
