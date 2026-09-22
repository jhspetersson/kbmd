import { Maximize, ZoomIn, ZoomOut } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { api, type GraphData } from '../api';

interface Props {
  activePath: string | null;
  /** Bumped whenever notes change, to reload the graph. */
  revision: number;
  onOpen: (path: string) => void;
  onCreate: (target: string) => void;
}

interface SimNode {
  id: string;
  label: string;
  exists: boolean;
  tags: string[];
  x: number;
  y: number;
  vx: number;
  vy: number;
  degree: number;
  neighbors: Set<SimNode>;
  pinned: boolean;
}

const radius = (node: SimNode) => 4 + Math.min(10, Math.sqrt(node.degree) * 2);

/** Force-directed graph of notes and the links between them, drawn on a canvas. */
export function GraphView({ activePath, revision, onOpen, onCreate }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [data, setData] = useState<GraphData | null>(null);
  const [filter, setFilter] = useState('');
  const [showUnresolved, setShowUnresolved] = useState(true);
  const [showOrphans, setShowOrphans] = useState(true);

  // kept in refs so the animation loop reads fresh values without restarting
  const filterRef = useRef(filter);
  filterRef.current = filter.trim().toLowerCase();
  const activeRef = useRef(activePath);
  activeRef.current = activePath;
  const callbacks = useRef({ onOpen, onCreate });
  callbacks.current = { onOpen, onCreate };
  const positions = useRef(new Map<string, { x: number; y: number }>());
  // the zoom buttons reach into the running animation through this
  const view = useRef<{ zoomBy: (factor: number) => void; reset: () => void } | null>(null);

  useEffect(() => {
    api.graph().then(setData).catch(() => setData({ nodes: [], edges: [] }));
  }, [revision]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !data) return;
    const context = canvas.getContext('2d')!;

    // ---- build the simulation
    const byId = new Map<string, SimNode>();
    data.nodes.forEach((node, i) => {
      if (!node.exists && !showUnresolved) return;
      const known = positions.current.get(node.id);
      const angle = i * 2.399963; // golden angle: an even spiral as the starting layout
      const distance = 18 * Math.sqrt(i + 1);
      byId.set(node.id, {
        ...node,
        x: known?.x ?? Math.cos(angle) * distance,
        y: known?.y ?? Math.sin(angle) * distance,
        vx: 0,
        vy: 0,
        degree: 0,
        neighbors: new Set(),
        pinned: false,
      });
    });
    const links: [SimNode, SimNode][] = [];
    for (const edge of data.edges) {
      const source = byId.get(edge.source);
      const target = byId.get(edge.target);
      if (!source || !target) continue;
      links.push([source, target]);
      source.degree++;
      target.degree++;
      source.neighbors.add(target);
      target.neighbors.add(source);
    }
    const nodes = Array.from(byId.values()).filter((node) => showOrphans || node.degree > 0);

    let alpha = 1;
    const tick = () => {
      for (let i = 0; i < nodes.length; i++) {
        const a = nodes[i];
        for (let j = i + 1; j < nodes.length; j++) {
          const b = nodes[j];
          let dx = b.x - a.x;
          let dy = b.y - a.y;
          let d2 = dx * dx + dy * dy;
          if (d2 < 0.01) {
            dx = Math.random() - 0.5;
            dy = Math.random() - 0.5;
            d2 = 0.01;
          }
          if (d2 > 250_000) continue;
          const force = (1400 * alpha) / d2;
          const d = Math.sqrt(d2);
          a.vx -= (dx / d) * force;
          a.vy -= (dy / d) * force;
          b.vx += (dx / d) * force;
          b.vy += (dy / d) * force;
        }
      }
      for (const [a, b] of links) {
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const d = Math.sqrt(dx * dx + dy * dy) || 1;
        const force = (d - 70) * 0.04 * alpha;
        a.vx += (dx / d) * force;
        a.vy += (dy / d) * force;
        b.vx -= (dx / d) * force;
        b.vy -= (dy / d) * force;
      }
      for (const node of nodes) {
        node.vx -= node.x * 0.012 * alpha;
        node.vy -= node.y * 0.012 * alpha;
        if (!node.pinned) {
          node.x += node.vx;
          node.y += node.vy;
        }
        node.vx *= 0.6;
        node.vy *= 0.6;
      }
      alpha = Math.max(0, alpha * 0.985 - 0.0005);
    };

    // ---- view state
    let scale = 1;
    let offsetX = 0;
    let offsetY = 0;
    let width = 0;
    let height = 0;
    let hovered: SimNode | null = null;
    let dragged: SimNode | null = null;
    let panning = false;
    let moved = false;
    let lastX = 0;
    let lastY = 0;

    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      const ratio = window.devicePixelRatio || 1;
      width = rect.width;
      height = rect.height;
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
    };
    const observer = new ResizeObserver(resize);
    observer.observe(canvas);
    resize();

    const toWorld = (x: number, y: number) => ({ x: (x - width / 2 - offsetX) / scale, y: (y - height / 2 - offsetY) / scale });
    /** Zooms so that the world point under (x, y) stays put. */
    const zoomAt = (x: number, y: number, factor: number) => {
      const before = toWorld(x, y);
      scale = Math.min(6, Math.max(0.1, scale * factor));
      offsetX = x - width / 2 - before.x * scale;
      offsetY = y - height / 2 - before.y * scale;
    };
    view.current = {
      zoomBy: (factor) => zoomAt(width / 2, height / 2, factor),
      reset: () => {
        scale = 1;
        offsetX = 0;
        offsetY = 0;
      },
    };
    const nodeAt = (x: number, y: number) => {
      const point = toWorld(x, y);
      let best: SimNode | null = null;
      let bestDistance = Infinity;
      for (const node of nodes) {
        const d = Math.hypot(node.x - point.x, node.y - point.y);
        if (d < radius(node) + 4 / scale && d < bestDistance) {
          best = node;
          bestDistance = d;
        }
      }
      return best;
    };

    const draw = () => {
      const style = getComputedStyle(canvas);
      const color = (name: string) => style.getPropertyValue(name).trim();
      const query = filterRef.current;
      const matches = (node: SimNode) =>
        !query || node.label.toLowerCase().includes(query) || node.tags.some((tag) => `#${tag}`.includes(query));
      const focus = hovered ?? dragged;
      const lit = (node: SimNode) => (focus ? node === focus || focus.neighbors.has(node) : matches(node));

      context.clearRect(0, 0, width, height);
      context.save();
      context.translate(width / 2 + offsetX, height / 2 + offsetY);
      context.scale(scale, scale);

      context.lineWidth = 1 / scale;
      for (const [a, b] of links) {
        const on = focus ? a === focus || b === focus : matches(a) && matches(b);
        context.strokeStyle = on && focus ? color('--accent') : color('--graph-edge');
        context.globalAlpha = on ? 0.9 : 0.15;
        context.beginPath();
        context.moveTo(a.x, a.y);
        context.lineTo(b.x, b.y);
        context.stroke();
      }

      for (const node of nodes) {
        const on = lit(node);
        context.globalAlpha = on ? 1 : 0.2;
        context.fillStyle =
          node.id === activeRef.current || node === focus
            ? color('--accent')
            : node.exists
              ? color('--graph-node')
              : color('--graph-unresolved');
        context.beginPath();
        context.arc(node.x, node.y, radius(node), 0, Math.PI * 2);
        context.fill();
      }

      // labels fade in when zoomed in, and are always shown for highlighted nodes
      const labelAlpha = Math.min(1, Math.max(0, (scale - 0.55) * 2.5));
      context.font = `${12 / scale}px ${color('--font-ui') || 'sans-serif'}`;
      context.textAlign = 'center';
      context.textBaseline = 'top';
      context.fillStyle = color('--text');
      for (const node of nodes) {
        const emphasised = (focus || query) && lit(node);
        const a = emphasised ? 1 : lit(node) ? labelAlpha : labelAlpha * 0.2;
        if (a <= 0.02) continue;
        context.globalAlpha = a;
        context.fillText(node.label, node.x, node.y + radius(node) + 3 / scale);
      }
      context.restore();
    };

    let frame = 0;
    const loop = () => {
      if (alpha > 0.002) tick();
      draw();
      frame = requestAnimationFrame(loop);
    };
    frame = requestAnimationFrame(loop);

    // ---- interaction
    const local = (event: MouseEvent) => {
      const rect = canvas.getBoundingClientRect();
      return { x: event.clientX - rect.left, y: event.clientY - rect.top };
    };
    // fingers on the canvas: two of them pinch-zoom instead of dragging or panning
    const pointers = new Map<number, { x: number; y: number }>();
    let pinchDistance = 0;
    const pinch = () => {
      const [a, b] = [...pointers.values()];
      return { distance: Math.hypot(b.x - a.x, b.y - a.y), x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
    };
    const onDown = (event: PointerEvent) => {
      const p = local(event);
      pointers.set(event.pointerId, p);
      canvas.setPointerCapture(event.pointerId);
      if (pointers.size === 2) {
        if (dragged) dragged.pinned = false;
        dragged = null;
        panning = false;
        moved = true; // lifting the fingers must not open a node
        pinchDistance = pinch().distance;
        return;
      }
      if (pointers.size > 2) return;
      dragged = nodeAt(p.x, p.y);
      if (dragged) dragged.pinned = true;
      else panning = true;
      moved = false;
      lastX = p.x;
      lastY = p.y;
    };
    const onMove = (event: PointerEvent) => {
      const p = local(event);
      if (pointers.has(event.pointerId)) pointers.set(event.pointerId, p);
      if (pointers.size >= 2) {
        const now = pinch();
        if (pinchDistance > 0 && now.distance > 0) zoomAt(now.x, now.y, now.distance / pinchDistance);
        pinchDistance = now.distance;
        return;
      }
      if (dragged) {
        const point = toWorld(p.x, p.y);
        dragged.x = point.x;
        dragged.y = point.y;
        alpha = Math.max(alpha, 0.3);
      } else if (panning) {
        offsetX += p.x - lastX;
        offsetY += p.y - lastY;
      } else {
        hovered = nodeAt(p.x, p.y);
        canvas.style.cursor = hovered ? 'pointer' : 'grab';
      }
      if (Math.abs(p.x - lastX) + Math.abs(p.y - lastY) > 2) moved = true;
      lastX = p.x;
      lastY = p.y;
    };
    const onUp = (event: PointerEvent) => {
      pointers.delete(event.pointerId);
      pinchDistance = 0;
      if (dragged && !moved) {
        if (dragged.exists) callbacks.current.onOpen(dragged.id);
        else callbacks.current.onCreate(dragged.label);
      }
      if (dragged) dragged.pinned = false;
      dragged = null;
      panning = false;
    };
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      const p = local(event);
      zoomAt(p.x, p.y, Math.exp(-event.deltaY * 0.0015));
    };
    const onLeave = () => {
      hovered = null;
    };
    canvas.addEventListener('pointerdown', onDown);
    canvas.addEventListener('pointermove', onMove);
    canvas.addEventListener('pointerup', onUp);
    canvas.addEventListener('pointercancel', onUp);
    canvas.addEventListener('pointerleave', onLeave);
    canvas.addEventListener('wheel', onWheel, { passive: false });

    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      canvas.removeEventListener('pointerdown', onDown);
      canvas.removeEventListener('pointermove', onMove);
      canvas.removeEventListener('pointerup', onUp);
      canvas.removeEventListener('pointercancel', onUp);
      canvas.removeEventListener('pointerleave', onLeave);
      canvas.removeEventListener('wheel', onWheel);
      view.current = null;
      // remember the layout so a reload does not reshuffle everything
      nodes.forEach((node) => positions.current.set(node.id, { x: node.x, y: node.y }));
    };
  }, [data, showUnresolved, showOrphans]);

  return (
    <div className="graph-view">
      <canvas ref={canvasRef} />
      <div className="graph-controls">
        <input placeholder="Filter by name or #tag" value={filter} onChange={(e) => setFilter(e.target.value)} />
        <label>
          <input type="checkbox" checked={showUnresolved} onChange={(e) => setShowUnresolved(e.target.checked)} /> Not yet created
        </label>
        <label>
          <input type="checkbox" checked={showOrphans} onChange={(e) => setShowOrphans(e.target.checked)} /> Orphans
        </label>
        <span className="graph-stats">
          {data ? `${data.nodes.filter((n) => n.exists).length} notes · ${data.edges.length} links` : 'Loading…'}
        </span>
      </div>
      <div className="graph-zoom">
        <button className="icon-button" title="Zoom in" onClick={() => view.current?.zoomBy(1.3)}><ZoomIn size={18} /></button>
        <button className="icon-button" title="Zoom out" onClick={() => view.current?.zoomBy(1 / 1.3)}><ZoomOut size={18} /></button>
        <button className="icon-button" title="Reset zoom" onClick={() => view.current?.reset()}><Maximize size={18} /></button>
      </div>
    </div>
  );
}
