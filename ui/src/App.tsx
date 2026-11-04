import { Anchor, AppShell, Badge, Burger, Button, Card, Group, NavLink, Select, Stack, Text, Title, Tooltip } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useEffect } from 'react';
import {
  IconBooks, IconBuildingBank, IconChartBar, IconChecklist, IconDatabase, IconFileText, IconLayoutDashboard, IconLifebuoy,
  IconMathFunction, IconSettings, IconSparkles, IconTool,
} from '@tabler/icons-react';
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { BrandMark } from './components/BrandMark';
import { canOpen, GROUPS, homeFor, PAGES, pageFor, readOnlyOnLoans } from './auth/access';
import { DEV_USERS, getAccessToken, isBorrowerOnly, setIdentity } from './auth/identity';
import type { Identity } from './auth/identity';
import { AlertBell } from './components/AlertBell';
import { ColorSchemeToggle } from './components/ColorSchemeToggle';
import { signedIn, signOut } from './auth/oidc';
import { useIdentity } from './auth/useIdentity';
import { CpWorkspace } from './pages/CpWorkspace';
import { DataSources } from './pages/DataSources';
import { Measures } from './pages/Measures';
import { MyTasks } from './pages/MyTasks';
import { PortalApp } from './pages/Portal';
import { Support } from './pages/Support';
import { Documents } from './pages/Documents';
import { Library } from './pages/Library';
import { Onboarding } from './pages/Onboarding';
import { Jobs } from './pages/Ops';
import { Portfolio } from './pages/Portfolio';
import { Reports } from './pages/Reports';
import { Settings } from './pages/Settings';

const ICONS: Record<string, typeof IconBooks> = {
  '/portfolio': IconLayoutDashboard, '/tasks': IconChecklist, '/cp': IconBuildingBank, '/onboarding': IconSparkles,
  '/documents': IconFileText, '/library': IconBooks, '/reports': IconChartBar, '/measures': IconMathFunction,
  '/ops/jobs': IconTool, '/ops/support': IconLifebuoy, '/data-sources': IconDatabase, '/settings': IconSettings,
};

/** Shown instead of a page the signed-in roles do not use (opened from a link or a typed address). */
export function NoAccess({ identity, pathname }: { identity: Identity; pathname: string }) {
  const page = pageFor(pathname);
  return (
    <Card withBorder padding="xl" maw={640}>
      <Stack gap="sm">
        <Title order={3}>{page ? `${page.label} is not part of your role` : 'Page not found.'}</Title>
        {page && (
          <Text>
            This page is for {page.purpose}. It is used by {page.roles.join(', ')}. You are signed in as{' '}
            {identity.user} ({identity.roles.join(', ') || 'no role'}).
          </Text>
        )}
        <Text c="dimmed" size="sm">If you need it for your work, ask an administrator to add the role.</Text>
        <Anchor component={Link} to={homeFor(identity)}>Go to your start page</Anchor>
      </Stack>
    </Card>
  );
}

/** Lets a page render only for the roles that use it. */
function Guard({ identity, children }: { identity: Identity; children: JSX.Element }) {
  const { pathname } = useLocation();
  const page = pageFor(pathname);
  return page && !canOpen(identity, page) ? <NoAccess identity={identity} pathname={pathname} /> : children;
}

function IdentitySwitcher() {
  const identity = useIdentity();
  if (getAccessToken()) {
    return (
      <Group gap="sm">
        <Text size="sm" visibleFrom="sm">{identity.user} ({identity.roles.join(', ') || 'no role'})</Text>
        {signedIn() && <Button size="xs" variant="default" onClick={() => void signOut()}>Sign out</Button>}
      </Group>
    );
  }
  return (
    <Select
      aria-label="Act as (development)"
      size="xs"
      w={220}
      value={identity.user}
      data={DEV_USERS.map((u) => ({ value: u.user, label: `${u.user} (${u.roles.join(', ')})` }))}
      onChange={(value) => {
        const next = DEV_USERS.find((u) => u.user === value);
        if (next) {
          setIdentity(next);
        }
      }}
      allowDeselect={false}
    />
  );
}

export function App() {
  const identity = useIdentity();
  const location = useLocation();
  const [menuOpen, menu] = useDisclosure(false);
  // On a phone the menu covers the page; following a link closes it.
  useEffect(() => menu.close(), [location.pathname]);
  if (isBorrowerOnly(identity)) {
    return <PortalApp user={identity.user} onSignOut={signedIn() ? () => void signOut() : undefined} />;
  }
  return (
    <AppShell header={{ height: 52 }} navbar={{ width: 220, breakpoint: 'sm', collapsed: { mobile: !menuOpen } }} padding="md">
      {/* First Tab stop: keyboard and screen-reader users jump past the header and menu. */}
      <a className="skip-link" href="#main" onClick={(e) => { e.preventDefault(); document.getElementById('main')?.focus(); }}>
        Skip to content
      </a>
      <AppShell.Header className="app-header">
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="xs" wrap="nowrap">
            <Burger opened={menuOpen} onClick={menu.toggle} hiddenFrom="sm" size="sm"
              aria-label={menuOpen ? 'Close the menu' : 'Open the menu'} />
            <BrandMark />
          </Group>
          <Group gap="sm" wrap="nowrap">
            {readOnlyOnLoans(identity) && (
              <Tooltip label="You can look at every CP but not change covenants, results or documents" multiline w={260}>
                <Badge color="gray" variant="light" visibleFrom="md">Read-only on loans</Badge>
              </Tooltip>
            )}
            <AlertBell />
            <ColorSchemeToggle />
            <IdentitySwitcher />
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="sm" className="app-nav" component="nav" aria-label="Main menu">
        {GROUPS.map((group) => {
          const pages = PAGES.filter((p) => p.group === group && canOpen(identity, p));
          return pages.length > 0 && (
            <div key={group} className="nav-group">
              <Text className="nav-group-label">{group}</Text>
              {pages.map(({ path, label }) => {
                const Icon = ICONS[path];
                return (
                  <NavLink key={path} component={Link} to={path} label={label} leftSection={<Icon size={16} stroke={1.75} />}
                    active={location.pathname === path || location.pathname.startsWith(`${path}/`)} />
                );
              })}
            </div>
          );
        })}
      </AppShell.Navbar>
      <AppShell.Main id="main" tabIndex={-1}>
        <div className="app-main">
          <Routes>
            <Route path="/" element={<Navigate to={homeFor(identity)} replace />} />
            <Route path="/portfolio" element={<Guard identity={identity}><Portfolio /></Guard>} />
            <Route path="/tasks" element={<Guard identity={identity}><MyTasks /></Guard>} />
            <Route path="/cp" element={<Guard identity={identity}><CpWorkspace /></Guard>} />
            <Route path="/cp/:cpId" element={<Guard identity={identity}><CpWorkspace /></Guard>} />
            <Route path="/onboarding" element={<Guard identity={identity}><Onboarding /></Guard>} />
            <Route path="/onboarding/:cpId" element={<Guard identity={identity}><Onboarding /></Guard>} />
            <Route path="/documents" element={<Guard identity={identity}><Documents /></Guard>} />
            <Route path="/documents/:cpId" element={<Guard identity={identity}><Documents /></Guard>} />
            <Route path="/library" element={<Guard identity={identity}><Library /></Guard>} />
            <Route path="/reports" element={<Guard identity={identity}><Reports /></Guard>} />
            <Route path="/ops/jobs" element={<Guard identity={identity}><Jobs /></Guard>} />
            <Route path="/ops/support" element={<Guard identity={identity}><Support /></Guard>} />
            <Route path="/measures" element={<Guard identity={identity}><Measures /></Guard>} />
            <Route path="/data-sources" element={<Guard identity={identity}><DataSources /></Guard>} />
            <Route path="/settings" element={<Guard identity={identity}><Settings /></Guard>} />
            <Route path="*" element={<Text>Page not found.</Text>} />
          </Routes>
        </div>
      </AppShell.Main>
    </AppShell>
  );
}
