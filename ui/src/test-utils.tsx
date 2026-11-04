import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { theme } from './theme';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

export function renderAt(path: string, route: string, element: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <MantineProvider theme={theme}>
      <Notifications />
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path={route} element={element} />
            <Route path="*" element={<div>navigated</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

type Handler = (url: string, init: RequestInit) => { status?: number; body?: unknown } | undefined;

/** Replaces fetch with a router of (method + path prefix) handlers; records every call. */
export function mockFetch(handler: Handler) {
  const calls: { url: string; init: RequestInit }[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input);
    calls.push({ url, init });
    const res = handler(url, init) ?? { status: 404, body: { code: 'NOT_FOUND', message: 'no stub' } };
    const status = res.status ?? 200;
    return new Response(res.body === undefined ? null : JSON.stringify(res.body), {
      status, headers: { 'content-type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fn);
  return calls;
}
