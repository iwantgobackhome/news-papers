import { useEffect, useRef, useState, type JSX, type ReactNode, type RefObject } from 'react';
import { t, useLanguage } from '../i18n';
import type { PageBox } from '../lib/sync';
import { pageForScroll, scrollbarGeometry, scrollForPage } from './page-navigation';

interface Props {
  bodyRef: RefObject<HTMLDivElement | null>;
  onScroll(): void;
  onScrollbarPage(page: number): void;
  pageCount: number;
  testId: string;
  children: ReactNode;
}

/** A persistent, touch-sized scroll rail shared by original and structured pages. */
export function PageScrollPane({ bodyRef, onScroll, onScrollbarPage, pageCount, testId, children }: Props): JSX.Element {
  useLanguage();
  const railRef = useRef<HTMLDivElement>(null);
  const drag = useRef<{ pointer: number; offset: number } | null>(null);
  const [metrics, setMetrics] = useState({ viewport: 0, content: 0, top: 0, track: 0 });
  const [preview, setPreview] = useState<{ page: number; y: number } | null>(null);
  const pages = (): PageBox[] => {
    const body = bodyRef.current;
    if (!body) return [];
    const top = body.getBoundingClientRect().top;
    return Array.from(body.querySelectorAll<HTMLElement>(':scope > [data-page]'), (node) => ({
      page: Number(node.dataset.page),
      top: node.getBoundingClientRect().top - top + body.scrollTop,
      height: node.offsetHeight,
    }));
  };
  useEffect(() => {
    const body = bodyRef.current;
    const rail = railRef.current;
    if (!body || !rail) return;
    let frame = 0;
    const measure = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const next = { viewport: body.clientHeight, content: body.scrollHeight, top: body.scrollTop, track: rail.clientHeight };
        setMetrics((old) => (Object.keys(next).every((key) => old[key as keyof typeof old] === next[key as keyof typeof next]) ? old : next));
      });
    };
    const resize = new ResizeObserver(measure);
    const observePages = () => {
      resize.disconnect();
      resize.observe(body);
      resize.observe(rail);
      for (const child of body.children) resize.observe(child);
      measure();
    };
    const mutation = new MutationObserver(observePages);
    mutation.observe(body, { childList: true });
    observePages();
    body.addEventListener('scroll', measure, { passive: true });
    return () => {
      cancelAnimationFrame(frame);
      resize.disconnect();
      mutation.disconnect();
      body.removeEventListener('scroll', measure);
    };
  }, [bodyRef]);
  const maxScroll = Math.max(0, metrics.content - metrics.viewport);
  const thumb = scrollbarGeometry(metrics.viewport, metrics.content, metrics.top, metrics.track);
  const move = (clientY: number, commit: boolean) => {
    const rail = railRef.current;
    const body = bodyRef.current;
    if (!rail || !body) return;
    const y = Math.max(0, Math.min(rail.clientHeight, clientY - rail.getBoundingClientRect().top));
    const range = Math.max(0, rail.clientHeight - thumb.height);
    const top = range > 0 ? Math.max(0, Math.min(1, (y - (drag.current?.offset ?? thumb.height / 2)) / range)) * maxScroll : 0;
    const page = pageForScroll(top, pages(), maxScroll);
    setPreview({ page, y });
    if (commit) {
      onScrollbarPage(page);
      // Pointer moves already supply continuous animation; smooth scroll would lag behind the hand.
      body.scrollTo({ top, behavior: 'instant' });
    }
  };
  const finish = (event: React.PointerEvent<HTMLDivElement>) => {
    if (drag.current?.pointer !== event.pointerId) return;
    drag.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    setPreview(null);
  };
  return (
    <div className="page-scroll-pane">
      <div className="pane-body" ref={bodyRef} onScroll={onScroll} id={testId} data-testid={testId}>
        {children}
      </div>
      <div
        ref={railRef}
        className="page-scrollbar"
        role="scrollbar"
        tabIndex={maxScroll > 0 ? 0 : -1}
        aria-label={t('reader.pages')}
        aria-controls={testId}
        aria-orientation="vertical"
        aria-valuemin={1}
        aria-valuemax={Math.max(1, pageCount)}
        aria-valuenow={pageForScroll(metrics.top, pages(), maxScroll)}
        aria-valuetext={`${pageForScroll(metrics.top, pages(), maxScroll)} / ${pageCount}`}
        onPointerDown={(event) => {
          if (event.button !== 0 || maxScroll <= 0) return;
          event.preventDefault();
          event.currentTarget.focus();
          event.currentTarget.setPointerCapture(event.pointerId);
          const y = event.clientY - event.currentTarget.getBoundingClientRect().top;
          drag.current = {
            pointer: event.pointerId,
            offset: (event.target as HTMLElement).closest('.page-scrollbar__thumb') ? y - thumb.top : thumb.height / 2,
          };
          move(event.clientY, true);
        }}
        onPointerMove={(event) => move(event.clientY, drag.current?.pointer === event.pointerId)}
        onPointerUp={finish}
        onPointerCancel={finish}
        onLostPointerCapture={() => {
          drag.current = null;
          setPreview(null);
        }}
        onPointerLeave={() => {
          if (!drag.current) setPreview(null);
        }}
        onKeyDown={(event) => {
          const body = bodyRef.current;
          if (!body) return;
          const liveMaxScroll = Math.max(0, body.scrollHeight - body.clientHeight);
          const pageBoxes = pages();
          const current = pageForScroll(body.scrollTop, pageBoxes, liveMaxScroll);
          const delta = { ArrowDown: 1, ArrowUp: -1, PageDown: 5, PageUp: -5 }[event.key];
          const target = event.key === 'Home' ? 1 : event.key === 'End' ? pageCount : delta !== undefined ? current + delta : null;
          if (target === null) return;
          event.preventDefault();
          event.stopPropagation();
          const page = Math.max(1, Math.min(pageCount, target));
          onScrollbarPage(page);
          bodyRef.current?.scrollTo({
            top: event.key === 'End' ? liveMaxScroll : event.key === 'Home' ? 0 : scrollForPage(page, pageBoxes, liveMaxScroll),
            behavior: 'instant',
          });
        }}
      >
        <div className="page-scrollbar__track" aria-hidden="true">
          {Array.from({ length: Math.min(pageCount, 80) }, (_, index) => (
            <i key={index} style={{ top: `${pageCount <= 1 ? 0 : (index / (Math.min(pageCount, 80) - 1)) * 100}%` }} />
          ))}
        </div>
        <div className="page-scrollbar__thumb" data-testid={`${testId}-thumb`} style={{ height: thumb.height, top: thumb.top }} />
        {preview && (
          <span className="page-scrollbar__tooltip" style={{ top: Math.max(16, Math.min(metrics.track - 16, preview.y)) }} role="tooltip">
            {preview.page} / {pageCount}
          </span>
        )}
      </div>
    </div>
  );
}
