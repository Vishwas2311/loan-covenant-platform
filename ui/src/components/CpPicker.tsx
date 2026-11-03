import { Button, Group, TextInput } from '@mantine/core';
import { useState } from 'react';

/** Free-text CP id entry used by pages reached without a CP in the URL. */
export function CpPicker({ initial = '', onOpen, label = 'Credit proposal (CP id)' }: {
  initial?: string; onOpen: (cpId: string) => void; label?: string;
}) {
  const [value, setValue] = useState(initial);
  const submit = () => {
    const cp = value.trim();
    if (cp) {
      onOpen(cp);
    }
  };
  return (
    <Group align="end">
      <TextInput label={label} value={value} onChange={(e) => setValue(e.currentTarget.value)}
        onKeyDown={(e) => e.key === 'Enter' && submit()} w={260} />
      <Button onClick={submit} disabled={!value.trim()}>Open</Button>
    </Group>
  );
}
