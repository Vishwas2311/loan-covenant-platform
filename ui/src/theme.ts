import {
  ActionIcon, Badge, Button, Card, createTheme, Modal, NavLink, Paper, SegmentedControl, Select, Table, Tabs, TextInput,
  type MantineColorsTuple,
} from '@mantine/core';

// The one data hue (#1d5fbf at index 7): links, focus and the main data element of each chart.
const brand: MantineColorsTuple = [
  '#edf3fc', '#d9e5f8', '#b0c9f0', '#84abe7', '#5f91df', '#447fda', '#2f6ccc', '#1d5fbf', '#174fa1', '#123f81',
];

/**
 * "Achromatic ledger": the chrome is ink and grey, the one hue goes on data, status colours only on
 * state. Flat white cards with a hairline edge on a blue-grey ground, an 8/6 radius pair, Inter with
 * tabular figures, a near-black primary button and a grey secondary.
 */
export const theme = createTheme({
  fontFamily: "'Inter Variable', Inter, 'Segoe UI', system-ui, -apple-system, sans-serif",
  headings: {
    fontFamily: "'Inter Variable', Inter, 'Segoe UI', system-ui, sans-serif",
    fontWeight: '650',
    sizes: {
      h1: { fontSize: '1.5rem', lineHeight: '1.3' },
      h2: { fontSize: '1.5rem', lineHeight: '1.3' },
      h3: { fontSize: '1.125rem', lineHeight: '1.4' },
      h4: { fontSize: '0.95rem', lineHeight: '1.4' },
      h5: { fontSize: '0.875rem', lineHeight: '1.4' },
    },
  },
  colors: { brand },
  primaryColor: 'brand',
  primaryShade: { light: 7, dark: 4 },
  defaultRadius: 'sm',
  radius: { xs: '4px', sm: '6px', md: '8px', lg: '8px', xl: '12px' },
  components: {
    Card: Card.extend({ defaultProps: { radius: 'md', padding: 'lg', withBorder: true, shadow: undefined } }),
    Paper: Paper.extend({ defaultProps: { radius: 'md' } }),
    // Filled = the one near-black primary; light = the grey secondary; subtle stays quiet.
    Button: Button.extend({
      defaultProps: { radius: 'sm', color: 'dark' },
      styles: { label: { fontWeight: 550 } },
    }),
    ActionIcon: ActionIcon.extend({ defaultProps: { radius: 'sm', color: 'gray' } }),
    Badge: Badge.extend({ defaultProps: { radius: 'sm' }, styles: { root: { fontWeight: 600, letterSpacing: '0.01em' } } }),
    TextInput: TextInput.extend({ defaultProps: { radius: 'sm' } }),
    Select: Select.extend({ defaultProps: { radius: 'sm' } }),
    SegmentedControl: SegmentedControl.extend({ defaultProps: { radius: 'sm' } }),
    Modal: Modal.extend({ defaultProps: { radius: 'md', centered: true } }),
    NavLink: NavLink.extend({ defaultProps: { color: 'gray', variant: 'light' } }),
    Tabs: Tabs.extend({ defaultProps: { color: 'dark' }, styles: { tab: { fontWeight: 500 } } }),
    Table: Table.extend({
      defaultProps: { verticalSpacing: 10, horizontalSpacing: 'md', highlightOnHover: true },
      styles: { th: { fontSize: '0.8125rem', fontWeight: 500, color: 'var(--mantine-color-dimmed)' } },
    }),
  },
});
