import { Group, Text } from '@mantine/core';

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
