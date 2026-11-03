import '@testing-library/jest-dom/vitest';

// Mantine needs these browser APIs, which jsdom does not provide.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false, media: query, onchange: null,
    addListener: () => {}, removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});
class ResizeObserverStub { observe() {} unobserve() {} disconnect() {} }
(window as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub;
window.HTMLElement.prototype.scrollIntoView = () => {};
