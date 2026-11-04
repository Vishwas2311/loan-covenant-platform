import type { Identity, Role } from './identity';

/**
 * Which bank roles use which page. The menu shows only these, and a page opened by link by anyone
 * else explains who it is for instead of loading screens whose calls Core would refuse. Core and DPS
 * still check every call themselves; this only keeps people out of pages that have nothing for them.
 */
export interface PageAccess {
  path: string;
  label: string;
  roles: Role[];
  /** Why these roles: shown on the "not for you" page. */
  purpose: string;
}

export const PAGES: PageAccess[] = [
  { path: '/portfolio', label: 'Portfolio', roles: ['RM', 'APPROVER', 'ADMIN', 'AUDITOR', 'SUPPORT'],
    purpose: 'the covenant position of the CPs you look after' },
  { path: '/tasks', label: 'My tasks', roles: ['RM', 'APPROVER', 'ADMIN'],
    purpose: 'work assigned to relationship managers and approvers' },
  { path: '/cp', label: 'CP workspace', roles: ['RM', 'APPROVER', 'ADMIN', 'AUDITOR', 'SUPPORT'],
    purpose: "one CP's covenant results, breaches and certificates" },
  { path: '/onboarding', label: 'Onboarding', roles: ['RM', 'APPROVER', 'ADMIN'],
    purpose: 'choosing which covenants a CP is monitored for' },
  { path: '/documents', label: 'Documents', roles: ['RM', 'APPROVER', 'ADMIN', 'AUDITOR', 'SUPPORT'],
    purpose: 'loan agreements, statements and evidence for a CP' },
  { path: '/library', label: 'Covenant library', roles: ['RM', 'APPROVER', 'AUTHOR', 'ADMIN', 'AUDITOR', 'SUPPORT'],
    purpose: 'the covenants the bank can apply' },
  { path: '/reports', label: 'Reports', roles: ['RM', 'APPROVER', 'ADMIN', 'AUDITOR'],
    purpose: 'breach rates, open breaches and your own reports' },
  { path: '/measures', label: 'Measures', roles: ['AUTHOR', 'ADMIN'],
    purpose: 'the figures covenant rules are written in' },
  { path: '/ops/jobs', label: 'Document jobs', roles: ['SUPPORT', 'ADMIN'],
    purpose: 'checking and restarting document processing' },
  { path: '/ops/support', label: 'Support tools', roles: ['SUPPORT', 'ADMIN'],
    purpose: 'diagnosing CPs and re-running jobs' },
  { path: '/data-sources', label: 'Data sources', roles: ['ADMIN'],
    purpose: 'connections to the systems figures come from' },
  { path: '/settings', label: 'Settings', roles: ['ADMIN'],
    purpose: 'organisation-wide settings and FX rates' },
];

export function canOpen(identity: Identity, page: PageAccess): boolean {
  return page.roles.some((r) => identity.roles.includes(r));
}

/** The page a path belongs to (the longest matching prefix), or undefined for an unknown path. */
export function pageFor(pathname: string): PageAccess | undefined {
  return PAGES.filter((p) => pathname === p.path || pathname.startsWith(`${p.path}/`))
    .sort((a, b) => b.path.length - a.path.length)[0];
}

/** Where "/" takes this user: the first page they may open. */
export function homeFor(identity: Identity): string {
  return PAGES.find((p) => canOpen(identity, p))?.path ?? '/library';
}

/** Can look but not change loans: auditors and support staff with no RM, approver or admin role. */
export function readOnlyOnLoans(identity: Identity): boolean {
  return identity.roles.some((r) => r === 'AUDITOR' || r === 'SUPPORT')
    && !identity.roles.some((r) => r === 'RM' || r === 'APPROVER' || r === 'ADMIN');
}
