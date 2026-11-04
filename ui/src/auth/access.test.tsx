import { screen } from '@testing-library/react';
import { NoAccess } from '../App';
import { renderAt } from '../test-utils';
import { PAGES, canOpen, homeFor, pageFor, readOnlyOnLoans } from './access';
import type { Identity } from './identity';

const as = (...roles: Identity['roles']): Identity => ({ user: 'u', roles });
const menu = (id: Identity) => PAGES.filter((p) => canOpen(id, p)).map((p) => p.label);

describe('page access by role', () => {
  it('gives each role only the pages it uses', () => {
    expect(menu(as('RM'))).toEqual(['Portfolio', 'My tasks', 'CP workspace', 'Onboarding', 'Documents',
      'Covenant library', 'Reports']);
    expect(menu(as('AUTHOR'))).toEqual(['Covenant library', 'Measures']);
    expect(menu(as('AUDITOR'))).toEqual(['Portfolio', 'CP workspace', 'Documents', 'Covenant library', 'Reports']);
    expect(menu(as('SUPPORT'))).toEqual(['Portfolio', 'CP workspace', 'Documents', 'Covenant library',
      'Document jobs', 'Support tools']);
    expect(menu(as('ADMIN'))).toHaveLength(PAGES.length);
    expect(menu(as('RM', 'AUTHOR'))).toContain('Measures');
  });

  it('starts each role on a page it can open', () => {
    expect(homeFor(as('RM'))).toBe('/portfolio');
    expect(homeFor(as('AUTHOR'))).toBe('/library');
    expect(homeFor(as())).toBe('/library');
  });

  it('matches a path to its page', () => {
    expect(pageFor('/cp/CP-1')?.label).toBe('CP workspace');
    expect(pageFor('/ops/support')?.label).toBe('Support tools');
    expect(pageFor('/cpx')).toBeUndefined();
  });

  it('marks auditors and support as read-only on loans', () => {
    expect(readOnlyOnLoans(as('AUDITOR'))).toBe(true);
    expect(readOnlyOnLoans(as('SUPPORT'))).toBe(true);
    expect(readOnlyOnLoans(as('SUPPORT', 'RM'))).toBe(false);
    expect(readOnlyOnLoans(as('AUTHOR'))).toBe(false);
  });

  it('explains a page that is not for this role', () => {
    renderAt('/settings', '/settings', <NoAccess identity={{ user: 'author-1', roles: ['AUTHOR'] }} pathname="/settings" />);
    expect(screen.getByText('Settings is not part of your role')).toBeInTheDocument();
    expect(screen.getByText(/It is used by ADMIN\. You are signed in as author-1 \(AUTHOR\)/)).toBeInTheDocument();
    expect(screen.getByText('Go to your start page')).toHaveAttribute('href', '/library');
  });

  it('says not found for an unknown path', () => {
    renderAt('/x', '/x', <NoAccess identity={as('RM')} pathname="/x" />);
    expect(screen.getByText('Page not found.')).toBeInTheDocument();
  });
});
