import { AppShell, Button, Group, NavLink, Select, Text, Title } from '@mantine/core';
import {
  IconBooks, IconBuildingBank, IconChartBar, IconFileText, IconLayoutDashboard, IconSettings, IconSparkles, IconTool,
} from '@tabler/icons-react';
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { DEV_USERS, getAccessToken, hasRole, setIdentity } from './auth/identity';
import { signedIn, signOut } from './auth/oidc';
import { useIdentity } from './auth/useIdentity';
import { CpWorkspace } from './pages/CpWorkspace';
import { Documents } from './pages/Documents';
import { Library } from './pages/Library';
import { Onboarding } from './pages/Onboarding';
import { Jobs } from './pages/Ops';
import { Portfolio } from './pages/Portfolio';
import { Reports } from './pages/Reports';
import { Settings } from './pages/Settings';

const NAV = [
  { to: '/portfolio', label: 'Portfolio', icon: IconLayoutDashboard },
  { to: '/cp', label: 'CP workspace', icon: IconBuildingBank },
  { to: '/onboarding', label: 'Onboarding', icon: IconSparkles },
  { to: '/documents', label: 'Documents', icon: IconFileText },
  { to: '/library', label: 'Covenant library', icon: IconBooks },
  { to: '/reports', label: 'Reports', icon: IconChartBar },
];

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
  const admin = hasRole(identity, 'ADMIN');
  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 230, breakpoint: 'sm' }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Title order={4}>Covenant Platform</Title>
          <IdentitySwitcher />
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        {NAV.map(({ to, label, icon: Icon }) => (
          <NavLink key={to} component={Link} to={to} label={label} leftSection={<Icon size={18} />}
            active={location.pathname.startsWith(to)} />
        ))}
        {hasRole(identity, 'SUPPORT', 'ADMIN') && (
          <NavLink component={Link} to="/ops/jobs" label="Document jobs" leftSection={<IconTool size={18} />}
            active={location.pathname.startsWith('/ops')} />
        )}
        {admin && (
          <NavLink component={Link} to="/settings" label="Settings" leftSection={<IconSettings size={18} />}
            active={location.pathname.startsWith('/settings')} />
        )}
      </AppShell.Navbar>
      <AppShell.Main>
        <Routes>
          <Route path="/" element={<Navigate to="/portfolio" replace />} />
          <Route path="/portfolio" element={<Portfolio />} />
          <Route path="/cp" element={<CpWorkspace />} />
          <Route path="/cp/:cpId" element={<CpWorkspace />} />
          <Route path="/onboarding" element={<Onboarding />} />
          <Route path="/onboarding/:cpId" element={<Onboarding />} />
          <Route path="/documents" element={<Documents />} />
          <Route path="/documents/:cpId" element={<Documents />} />
          <Route path="/library" element={<Library />} />
          <Route path="/reports" element={<Reports />} />
          <Route path="/ops/jobs" element={<Jobs />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="*" element={<Text>Page not found.</Text>} />
        </Routes>
      </AppShell.Main>
    </AppShell>
  );
}
