import { ChevronDown, ChevronRight, File, FileText, Image, PenTool } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import type { TreeNode } from '../api';
import { isTouch } from '../native';

export type TreeAction = 'new-note' | 'new-drawing' | 'new-folder' | 'rename' | 'delete' | 'upload' | 'export';

interface Props {
  root: TreeNode;
  activePath: string | null;
  onOpen: (path: string) => void;
  onAction: (action: TreeAction, node: TreeNode) => void;
  onMove: (from: string, toFolder: string) => void;
  onUploadTo: (files: File[], folder: string) => void;
}

const EXPANDED_KEY = 'kbmd.expanded';
const DRAG_TYPE = 'application/x-kbmd-path';

function icon(node: TreeNode) {
  switch (node.type) {
    case 'note':
      return <FileText size={14} />;
    case 'drawing':
      return <PenTool size={14} />;
    case 'image':
      return <Image size={14} />;
    default:
      return <File size={14} />;
  }
}

export function FileTree({ root, activePath, onOpen, onAction, onMove, onUploadTo }: Props) {
  const [expanded, setExpanded] = useState<Set<string>>(() => {
    try {
      return new Set(JSON.parse(localStorage.getItem(EXPANDED_KEY) ?? '[]') as string[]);
    } catch {
      return new Set();
    }
  });
  const [menu, setMenu] = useState<{ x: number; y: number; node: TreeNode } | null>(null);
  const [dropTarget, setDropTarget] = useState<string | null>(null);
  const longPress = useRef<number | undefined>(undefined);

  useEffect(() => {
    localStorage.setItem(EXPANDED_KEY, JSON.stringify([...expanded]));
  }, [expanded]);

  // reveal the active note
  useEffect(() => {
    if (!activePath) return;
    const parts = activePath.split('/').slice(0, -1);
    if (parts.length === 0) return;
    setExpanded((current) => {
      const next = new Set(current);
      parts.forEach((_, i) => next.add(parts.slice(0, i + 1).join('/')));
      return next.size === current.size ? current : next;
    });
  }, [activePath]);

  useEffect(() => {
    if (!menu) return;
    const close = () => setMenu(null);
    window.addEventListener('click', close);
    window.addEventListener('contextmenu', close);
    return () => {
      window.removeEventListener('click', close);
      window.removeEventListener('contextmenu', close);
    };
  }, [menu]);

  const toggle = (path: string) =>
    setExpanded((current) => {
      const next = new Set(current);
      if (!next.delete(path)) next.add(path);
      return next;
    });

  const dropHandlers = (folder: string) => ({
    onDragOver: (event: React.DragEvent) => {
      const types = event.dataTransfer.types;
      if (!types.includes(DRAG_TYPE) && !types.includes('Files')) return;
      event.preventDefault();
      event.stopPropagation();
      setDropTarget(folder);
    },
    onDragLeave: () => setDropTarget((current) => (current === folder ? null : current)),
    onDrop: (event: React.DragEvent) => {
      event.preventDefault();
      event.stopPropagation();
      setDropTarget(null);
      const from = event.dataTransfer.getData(DRAG_TYPE);
      if (from) onMove(from, folder);
      else if (event.dataTransfer.files.length > 0) onUploadTo(Array.from(event.dataTransfer.files), folder);
    },
  });

  const renderNode = (node: TreeNode, depth: number) => {
    const isFolder = node.type === 'folder';
    const open = expanded.has(node.path);
    const row = (
      <div
        className={`tree-row${node.path === activePath ? ' active' : ''}${dropTarget === node.path && isFolder ? ' drop-target' : ''}`}
        style={{ paddingLeft: 8 + depth * 14 }}
        // with a finger, a long press opens the menu; dragging would start on the same gesture
        draggable={!isTouch()}
        title={node.path}
        onDragStart={(event) => {
          event.dataTransfer.setData(DRAG_TYPE, node.path);
          event.dataTransfer.effectAllowed = 'move';
        }}
        onClick={() => (isFolder ? toggle(node.path) : onOpen(node.path))}
        onContextMenu={(event) => {
          event.preventDefault();
          event.stopPropagation();
          setMenu({ x: event.clientX, y: event.clientY, node });
        }}
        // not every touch browser turns a long press into a contextmenu event
        onTouchStart={(event) => {
          const { clientX: x, clientY: y } = event.touches[0];
          window.clearTimeout(longPress.current);
          longPress.current = window.setTimeout(() => setMenu({ x, y, node }), 600);
        }}
        onTouchMove={() => window.clearTimeout(longPress.current)}
        onTouchEnd={() => window.clearTimeout(longPress.current)}
        onTouchCancel={() => window.clearTimeout(longPress.current)}
        {...(isFolder ? dropHandlers(node.path) : {})}
      >
        <span className="tree-icon">{isFolder ? open ? <ChevronDown size={14} /> : <ChevronRight size={14} /> : icon(node)}</span>
        <span className="tree-name">{node.type === 'note' ? node.name.replace(/\.md$/i, '') : node.name}</span>
      </div>
    );
    return (
      <div key={node.path}>
        {row}
        {isFolder && open && node.children?.map((child) => renderNode(child, depth + 1))}
      </div>
    );
  };

  return (
    <div
      className={`file-tree${dropTarget === '' ? ' drop-target' : ''}`}
      onContextMenu={(event) => {
        event.preventDefault();
        setMenu({ x: event.clientX, y: event.clientY, node: root });
      }}
      {...dropHandlers('')}
    >
      {root.children?.length ? root.children.map((child) => renderNode(child, 0)) : <div className="panel-empty">The vault is empty.</div>}

      {menu && (
        <div
          className="context-menu"
          style={{ left: Math.max(4, Math.min(menu.x, window.innerWidth - 200)), top: Math.max(4, Math.min(menu.y, window.innerHeight - 290)) }}
        >
          {menu.node.type === 'folder' && (
            <>
              <button onClick={() => onAction('new-note', menu.node)}>New note</button>
              <button onClick={() => onAction('new-drawing', menu.node)}>New drawing</button>
              <button onClick={() => onAction('new-folder', menu.node)}>New folder</button>
              <button onClick={() => onAction('upload', menu.node)}>Upload files here…</button>
            </>
          )}
          {menu.node.path !== '' && (
            <>
              {menu.node.type === 'folder' && <hr />}
              <button onClick={() => onAction('rename', menu.node)}>Rename / move…</button>
              {menu.node.type !== 'folder' && <button onClick={() => onAction('export', menu.node)}>Export…</button>}
              <button className="danger" onClick={() => onAction('delete', menu.node)}>Delete</button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
