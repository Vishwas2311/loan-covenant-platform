/**
 * Who the UI acts as. Production: an Entra ID access token (set by the sign-in flow with setAccessToken),
 * sent as a Bearer header to Core and DPS. Development: a chosen user and roles sent as X-User / X-Roles,
 * which both services accept only in their DEV_HEADER mode.
 */
export type Role = 'RM' | 'APPROVER' | 'AUTHOR' | 'SUPPORT' | 'AUDITOR' | 'ADMIN' | 'BORROWER';

export interface Identity {
  user: string;
  roles: Role[];
}

const KEY = 'covenant.devIdentity';
let accessToken: string | null = null;
const listeners = new Set<() => void>();

// Matches the Core demo portfolio: an approver only acts on CPs where they are the RM or WRM
// (rm-anil / wrm-meera own CP-1001..1007 and CP-2001..2005; rm-priya / wrm-vikram the rest).
export const DEV_USERS: Identity[] = [
  { user: 'rm-anil', roles: ['RM'] },
  { user: 'wrm-meera', roles: ['APPROVER'] },
  { user: 'rm-priya', roles: ['RM'] },
  { user: 'wrm-vikram', roles: ['APPROVER'] },
  { user: 'author-1', roles: ['AUTHOR'] },
  { user: 'admin-1', roles: ['ADMIN'] },
  { user: 'auditor-1', roles: ['AUDITOR'] },
  { user: 'support-1', roles: ['SUPPORT'] },
  { user: 'borrower-reliance', roles: ['BORROWER'] },
];

const ROLES: Role[] = ['RM', 'APPROVER', 'AUTHOR', 'SUPPORT', 'AUDITOR', 'ADMIN', 'BORROWER'];

/** C1: someone from the borrower, with no bank role: they only ever see the borrower portal. */
export function isBorrowerOnly(identity: Identity): boolean {
  return identity.roles.includes('BORROWER') && identity.roles.every((r) => r === 'BORROWER');
}
let tokenIdentity: Identity | null = null;

/**
 * Who a signed-in token belongs to, for the UI only (menus, buttons). Core and DPS verify the token
 * themselves, so a tampered value here changes nothing they allow.
 */
export function identityFromToken(token: string): Identity | null {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const claims = JSON.parse(decodeURIComponent(escape(window.atob(payload)))) as Record<string, unknown>;
    const user = (claims.preferred_username ?? claims.upn ?? claims.sub) as string | undefined;
    const raw = Array.isArray(claims.roles) ? (claims.roles as unknown[]) : [];
    const roles = raw.map((r) => String(r).toUpperCase()).filter((r): r is Role => ROLES.includes(r as Role));
    return user ? { user, roles } : null;
  } catch {
    return null;
  }
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
  tokenIdentity = token ? identityFromToken(token) : null;
  listeners.forEach((l) => l());
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function getIdentity(): Identity {
  if (tokenIdentity) {
    return tokenIdentity;
  }
  try {
    const raw = window.localStorage.getItem(KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Identity;
      if (parsed && typeof parsed.user === 'string' && Array.isArray(parsed.roles)) {
        return parsed;
      }
    }
  } catch {
    // storage blocked or corrupt: fall back to the default user
  }
  return DEV_USERS[0];
}

export function setIdentity(identity: Identity): void {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(identity));
  } catch {
    // storage blocked: the choice lasts until reload
  }
  listeners.forEach((l) => l());
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function hasRole(identity: Identity, ...roles: Role[]): boolean {
  return identity.roles.some((r) => roles.includes(r));
}

/** Headers that carry the identity on every API call. */
export function authHeaders(): Record<string, string> {
  if (accessToken) {
    return { Authorization: `Bearer ${accessToken}` };
  }
  const id = getIdentity();
  return { 'X-User': id.user, 'X-Roles': id.roles.join(',') };
}
