import '@fontsource-variable/inter';
import '@mantine/core/styles.css';
import '@mantine/notifications/styles.css';
import '@mantine/dropzone/styles.css';
import './app.css';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { theme } from './theme';
import { loadAuthConfig, startAuth } from './auth/oidc';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
});

function render() {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <MantineProvider theme={theme} defaultColorScheme="auto">
        <Notifications />
        <QueryClientProvider client={queryClient}>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </QueryClientProvider>
      </MantineProvider>
    </StrictMode>,
  );
}

// Real sign-in when the server configures it (local Keycloak, Entra ID); otherwise development mode.
loadAuthConfig()
  .then((cfg) => startAuth(cfg))
  .then((ready) => {
    if (ready) {
      render();
    }
  })
  .catch((err: unknown) => {
    document.getElementById('root')!.textContent = `Sign-in failed: ${String(err)}`;
  });
