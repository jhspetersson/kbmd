import { LanguageDescription, type LanguageSupport } from '@codemirror/language';
import { languages } from '@codemirror/language-data';
import { classHighlighter, highlightCode } from '@lezer/highlight';

// Syntax highlighting for rendered code blocks (<pre><code class="language-x">), using the same parsers as the
// editor so both views agree. Grammars are loaded on first use; colours are set in styles.css (.tok-*).

const loaded = new Map<string, Promise<LanguageSupport | null>>();

/** Info strings people write that the catalogue does not list as an alias. */
const ALIASES: Record<string, string> = {
  jsonc: 'json',
  'c#': 'csharp',
  'f#': 'fsharp',
  'objective-c': 'objc',
  zsh: 'shell',
  console: 'shell',
  ps: 'powershell',
  pwsh: 'powershell',
  docker: 'dockerfile',
  make: 'shell',
  makefile: 'shell',
  proto: 'protobuf',
  golang: 'go',
  text: '',
  txt: '',
  plain: '',
  mermaid: '',
};

/** The ```info string, as a language name, an alias (`js`) or a file extension (`py`, `rs`, `kt`). */
function describe(info: string): LanguageDescription | null {
  const name = (ALIASES[info] ?? info).toLowerCase();
  if (!name) return null;
  return (
    LanguageDescription.matchLanguageName(languages, name, false) ??
    LanguageDescription.matchFilename(languages, `file.${name}`) ??
    LanguageDescription.matchLanguageName(languages, name, true)
  );
}

function language(info: string): Promise<LanguageSupport | null> {
  let pending = loaded.get(info);
  if (!pending) {
    const description = describe(info);
    pending = description ? description.load().catch(() => null) : Promise.resolve(null);
    loaded.set(info, pending);
  }
  return pending;
}

/** Replaces the text of every fenced code block in {@code container} with highlighted spans. */
export async function highlightCodeBlocks(container: HTMLElement): Promise<void> {
  const blocks = Array.from(container.querySelectorAll<HTMLElement>('pre > code[class*="language-"]'));
  await Promise.all(
    blocks.map(async (block) => {
      const name = Array.from(block.classList).find((c) => c.startsWith('language-'))?.substring(9) ?? '';
      const support = name ? await language(name) : null;
      if (!support || !block.isConnected) return;
      const code = block.textContent ?? '';
      const tree = support.language.parser.parse(code);
      const fragment = document.createDocumentFragment();
      highlightCode(
        code,
        tree,
        classHighlighter,
        (text, classes) => {
          if (!classes) {
            fragment.appendChild(document.createTextNode(text));
            return;
          }
          const span = document.createElement('span');
          span.className = classes;
          span.textContent = text;
          fragment.appendChild(span);
        },
        () => fragment.appendChild(document.createTextNode('\n')),
      );
      block.replaceChildren(fragment);
    }),
  );
}
