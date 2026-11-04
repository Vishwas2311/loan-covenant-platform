import { Alert, Badge, Center, Loader } from '@mantine/core';
import { errorMessage } from '../api/client';

const RESULT_COLOR: Record<string, string> = {
  COMPLIANT: 'green', COMPLIED: 'green', BREACHED: 'red', ERROR: 'orange', WAIVER: 'violet', EXTENSION: 'blue',
  REQUESTED: 'yellow', APPROVED: 'green', REJECTED: 'gray', PUBLISHED: 'green', NEEDS_REVIEW: 'yellow',
  FAILED: 'red', QUEUED: 'gray', RUNNING: 'blue', DONE: 'green', PROPOSED: 'gray', PENDING_REVIEW: 'yellow',
  NA: 'gray', ACTIVE: 'green', SUSPENDED: 'gray', DUE_SOON: 'yellow', OVERDUE: 'red', UP_TO_DATE: 'green',
  WITHDRAWN: 'gray', REMOVED: 'gray', AT_RISK: 'orange',
};

const LABEL: Record<string, string> = { NA: 'N/A' };

/** NEEDS_REVIEW -> Needs review. */
function sentence(v: string): string {
  const s = v.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function StatusBadge({ value }: { value: string | null | undefined }) {
  if (!value) {
    return <Badge color="gray" variant="light">Not assessed</Badge>;
  }
  return (
    <Badge color={RESULT_COLOR[value] ?? 'gray'} variant="light">
      {LABEL[value] ?? sentence(value)}
    </Badge>
  );
}

export function Loading() {
  return (
    <Center py="xl">
      <Loader aria-label="Loading" />
    </Center>
  );
}

export function ErrorNote({ error }: { error: unknown }) {
  return (
    <Alert color="red" title="Something went wrong">
      {errorMessage(error)}
    </Alert>
  );
}
