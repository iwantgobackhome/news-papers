import type { PageBox } from '../lib/sync';

/** Accept whole page numbers only; invalid drafts leave the reader in place. */
export function parsePageNumber(draft: string, pageCount: number): number | null {
  if (pageCount < 1 || !/^[+-]?\d+$/.test(draft.trim())) return null;
  const value = Number(draft.trim());
  return Number.isFinite(value) ? Math.max(1, Math.min(pageCount, value)) : null;
}

/** Use laid-out page tops, including placeholders and unequal translated page heights. */
export function pageForScroll(top: number, pages: readonly PageBox[], maxScroll: number): number {
  if (!pages.length) return 1;
  if (maxScroll > 0 && top >= maxScroll - 1) return pages[pages.length - 1].page;
  let page = pages[0].page;
  for (const box of pages) {
    if (box.top > top + 1) break;
    page = box.page;
  }
  return page;
}

export function scrollForPage(page: number, pages: readonly PageBox[], maxScroll: number): number {
  return Math.max(0, Math.min(maxScroll, pages.find((box) => box.page === page)?.top ?? 0));
}

export function scrollbarGeometry(viewport: number, content: number, top: number, track: number): { height: number; top: number } {
  const height = Math.min(track, Math.max(28, content > 0 ? (track * viewport) / content : track));
  const maxScroll = Math.max(0, content - viewport);
  return { height, top: maxScroll > 0 ? Math.max(0, Math.min(1, top / maxScroll)) * (track - height) : 0 };
}
