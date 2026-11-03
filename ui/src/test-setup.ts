import '@testing-library/jest-dom/vitest';
import { configure } from '@testing-library/react';

// Some flows chain two requests before a notification appears; 1 s (the default) is tight on a busy runner.
configure({ asyncUtilTimeout: 4000 });

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
