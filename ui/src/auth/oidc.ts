/**
 * Real sign-in (OpenID Connect, Authorization Code with PKCE): Keycloak locally, Entra ID in production.
 * Turned on when the server's /auth-config.json names an authority; otherwise the UI keeps its
 * development "Act as" picker. Tokens stay in memory (never in browser storage) and are renewed with
 * the refresh token before they expire; after a reload the identity provider's session signs the user
 * straight back in.
 */
import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
import { setAccessToken } from './identity';

export interface AuthConfig {
  authority: string;
  clientId: string;
  scope: string;
}

export const CALLBACK_PATH = '/auth/callback';
let manager: UserManager | null = null;

export async function loadAuthConfig(fetchImpl: typeof fetch = fetch): Promise<AuthConfig | null> {
  try {
    const res = await fetchImpl('/auth-config.json', { cache: 'no-store' });
    if (!res.ok) {
      return null;
    }
    const cfg = (await res.json()) as Partial<AuthConfig>;
    return cfg.authority && cfg.clientId ? { authority: cfg.authority, clientId: cfg.clientId,
      scope: cfg.scope || 'openid profile' } : null;
  } catch {
    return null; // dev server without the endpoint: development mode
  }
}

export function createManager(cfg: AuthConfig, origin: string = window.location.origin): UserManager {
  return new UserManager({
    authority: cfg.authority,
    client_id: cfg.clientId,
    redirect_uri: origin + CALLBACK_PATH,
    post_logout_redirect_uri: origin + '/',
    response_type: 'code',
    scope: cfg.scope,
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  });
}

function use(user: User | null): void {
  setAccessToken(user && !user.expired ? user.access_token : null);
}

/**
 * Signs the user in before the app renders. Returns false while the browser is being sent to the
 * identity provider (nothing to render yet).
 */
export async function startAuth(cfg: AuthConfig | null, location: Location = window.location,
                                history: History = window.history): Promise<boolean> {
  if (!cfg) {
    return true;
  }
  manager = createManager(cfg, location.origin);
  manager.events.addUserLoaded(use);
  manager.events.addUserUnloaded(() => use(null));
  if (location.pathname === CALLBACK_PATH) {
    const user = await manager.signinRedirectCallback();
    use(user);
    const target = typeof user.state === 'string' && user.state.startsWith('/') ? user.state : '/';
    history.replaceState(null, '', target);
    return true;
  }
  const user = await manager.getUser();
  if (user && !user.expired) {
    use(user);
    return true;
  }
  await manager.signinRedirect({ state: location.pathname + location.search });
  return false;
}

export function signedIn(): boolean {
  return manager !== null;
}

export async function signOut(): Promise<void> {
  if (manager) {
    setAccessToken(null);
    await manager.signoutRedirect();
  }
}
