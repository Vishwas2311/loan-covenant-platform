import { Anchor, AppShell, Badge, Button, Card, Group, NavLink, Select, Stack, Text, Title, Tooltip } from '@mantine/core';
import {
  IconBooks, IconBuildingBank, IconChartBar, IconChecklist, IconDatabase, IconFileText, IconLayoutDashboard, IconLifebuoy,
  IconMathFunction, IconSettings, IconSparkles, IconTool,
} from '@tabler/icons-react';
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { canOpen, homeFor, PAGES, pageFor, readOnlyOnLoans } from './auth/access';
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

/** The product name with a small chart mark, as on the GenBI-style boards. */
/** The Covenexa mark (same drawing as public/covenexa.svg) and the product name. */
export function BrandMark({ label = 'Covenexa' }: { label?: string }) {
  return (
    <Group gap={10} wrap="nowrap">
      <svg width="28" height="28" viewBox="0 0 160 160" aria-hidden="true">
        <rect width="160" height="160" rx="32" fill="#0b0b12" />
        <path d="M62 34 100 16v62L82 87v31l-60 28V100l40-19z" fill="#ffffff" />
        <path d="M86 92 104 82 140 101v42L86 119z" fill="#7353ea" />
        <path d="M86 92 104 82 140 101 122 111z" fill="#9a82f5" />
      </svg>
      <Text component="span" className="app-brand">{label}</Text>
    </Group>
  );
}

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
        <Text size="sm">{identity.user} ({identity.roles.join(', ') || 'no role'})</Text>
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
  if (isBorrowerOnly(identity)) {
    return <PortalApp user={identity.user} onSignOut={signedIn() ? () => void signOut() : undefined} />;
  }
  return (
    <AppShell header={{ height: 60 }} navbar={{ width: 236, breakpoint: 'sm' }} padding="lg">
      <AppShell.Header className="app-header">
        <Group h="100%" px="lg" justify="space-between">
          <BrandMark />
          <Group gap="sm">
            {readOnlyOnLoans(identity) && (
              <Tooltip label="You can look at every CP but not change covenants, results or documents" multiline w={260}>
                <Badge color="gray" variant="light">Read-only on loans</Badge>
              </Tooltip>
            )}
            <AlertBell />
            <ColorSchemeToggle />
            <IdentitySwitcher />
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="sm" className="app-nav">
        {PAGES.filter((p) => canOpen(identity, p)).map(({ path, label }) => {
          const Icon = ICONS[path];
          return (
            <NavLink key={path} component={Link} to={path} label={label} leftSection={<Icon size={18} />}
              active={location.pathname === path || location.pathname.startsWith(`${path}/`)} />
          );
        })}
      </AppShell.Navbar>
      <AppShell.Main>
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
      </AppShell.Main>
    </AppShell>
  );
}
