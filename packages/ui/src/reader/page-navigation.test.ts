import { describe, expect, it } from 'vitest';
import { pageForScroll, parsePageNumber, scrollbarGeometry, scrollForPage } from './page-navigation';

const pages = [
  { page: 1, top: 12, height: 800 },
  { page: 2, top: 828, height: 1200 },
  { page: 3, top: 2044, height: 400 },
];

describe('page number drafts', () => {
  it('accepts integers and clamps to the document', () => {
    expect(parsePageNumber(' 12 ', 40)).toBe(12);
    expect(parsePageNumber('0', 40)).toBe(1);
    expect(parsePageNumber('-5', 40)).toBe(1);
    expect(parsePageNumber('999', 40)).toBe(40);
  });
  it('ignores invalid drafts and unavailable documents', () => {
    for (const draft of ['', ' ', '12abc', '1.5', 'NaN', '1e2', 'Infinity', '9'.repeat(400)]) expect(parsePageNumber(draft, 40)).toBeNull();
    expect(parsePageNumber('1', 0)).toBeNull();
  });
});

describe('page scrollbar mapping', () => {
  it('maps unequal page heights, initial padding, and gaps', () => {
    expect(pageForScroll(0, pages, 2200)).toBe(1);
    expect(pageForScroll(820, pages, 2200)).toBe(1);
    expect(pageForScroll(828, pages, 2200)).toBe(2);
    expect(pageForScroll(1800, pages, 2200)).toBe(2);
    for (const page of [1, 2, 3]) expect(pageForScroll(scrollForPage(page, pages, 2200), pages, 2200)).toBe(page);
  });
  it('makes the last page reachable when its top exceeds maximum scroll', () => {
    expect(scrollForPage(3, pages, 1900)).toBe(1900);
    expect(pageForScroll(1900, pages, 1900)).toBe(3);
    expect(pageForScroll(0, [], 0)).toBe(1);
    expect(scrollForPage(99, [], 0)).toBe(0);
  });
  it('sizes a proportional thumb, caps tiny thumbs, and maps both ends', () => {
    expect(scrollbarGeometry(500, 2000, 750, 400)).toEqual({ height: 100, top: 150 });
    expect(scrollbarGeometry(500, 2000, 1500, 400)).toEqual({ height: 100, top: 300 });
    expect(scrollbarGeometry(500, 100000, 99500, 400)).toEqual({ height: 28, top: 372 });
    expect(scrollbarGeometry(500, 400, 0, 400)).toEqual({ height: 400, top: 0 });
  });
});
