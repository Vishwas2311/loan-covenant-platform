import {
  ActionIcon, Badge, Button, Card, createTheme, Modal, NavLink, NumberInput, Paper, Pill, SegmentedControl, Select, Table,
  Tabs, TextInput,
  type MantineColorsTuple,
} from '@mantine/core';

// The one accent, taken from the Covenexa mark (#5a46d6 at index 7): links, focus, the selected item
// and the main data element of each chart.
const brand: MantineColorsTuple = [
  '#f1eefd', '#e2dcfb', '#c4b8f6', '#a594f1', '#8f7ff0', '#7a68e6', '#6a56dd', '#5a46d6', '#4a38b4', '#3b2c91',
];

/**
 * "Achromatic ledger", set for work: the chrome is ink and grey, the one violet goes on data and the
 * selected item, status colours only on state. Flat white cards with a hairline edge on a blue-grey
 * ground, an 8/6 radius pair, Inter at a 14px base so a screen holds a full list without scrolling.
 */
export const theme = createTheme({
  fontFamily: "'Inter Variable', Inter, 'Segoe UI', system-ui, -apple-system, sans-serif",
  fontSizes: { xs: '0.75rem', sm: '0.8125rem', md: '0.875rem', lg: '1rem', xl: '1.125rem' },
  lineHeights: { xs: '1.4', sm: '1.45', md: '1.5', lg: '1.5', xl: '1.5' },
  spacing: { xs: '0.5rem', sm: '0.625rem', md: '0.875rem', lg: '1.125rem', xl: '1.5rem' },
  headings: {
    fontFamily: "'Inter Variable', Inter, 'Segoe UI', system-ui, sans-serif",
    fontWeight: '650',
    sizes: {
      h1: { fontSize: '1.375rem', lineHeight: '1.3' },
      h2: { fontSize: '1.25rem', lineHeight: '1.3' },
      h3: { fontSize: '1.0625rem', lineHeight: '1.4' },
      h4: { fontSize: '0.9375rem', lineHeight: '1.4' },
      h5: { fontSize: '0.8125rem', lineHeight: '1.4' },
    },
  },
  colors: { brand },
  primaryColor: 'brand',
  primaryShade: { light: 7, dark: 4 },
  defaultRadius: 'sm',
  radius: { xs: '4px', sm: '6px', md: '8px', lg: '8px', xl: '12px' },
  components: {
    Card: Card.extend({ defaultProps: { radius: 'md', padding: 'md', withBorder: true, shadow: undefined } }),
    Paper: Paper.extend({ defaultProps: { radius: 'md' } }),
    // Filled = the one near-black primary; light = the grey secondary; subtle stays quiet.
    Button: Button.extend({
      defaultProps: { radius: 'sm', color: 'dark', size: 'sm' },
      styles: { label: { fontWeight: 550 } },
    }),
    ActionIcon: ActionIcon.extend({ defaultProps: { radius: 'sm', color: 'gray' } }),
    // Sentence case and normal weight: a status is read, not shouted.
    Badge: Badge.extend({
      defaultProps: { radius: 'sm', variant: 'light' },
      styles: { root: { textTransform: 'none', fontWeight: 550, letterSpacing: 0 } },
    }),
    TextInput: TextInput.extend({ defaultProps: { radius: 'sm' } }),
    // Screen readers name every control: the tag remove buttons say what they do, and number fields drop
    // their unlabelled stepper arrows (the keyboard arrows still step the value).
    Pill: Pill.extend({ defaultProps: { removeButtonProps: { 'aria-label': 'Remove' } } }),
    NumberInput: NumberInput.extend({ defaultProps: { hideControls: true } }),
    Select: Select.extend({ defaultProps: { radius: 'sm' } }),
    SegmentedControl: SegmentedControl.extend({ defaultProps: { radius: 'sm', size: 'sm' } }),
    Modal: Modal.extend({ defaultProps: { radius: 'md', centered: true } }),
    NavLink: NavLink.extend({ defaultProps: { color: 'gray', variant: 'light' } }),
    Tabs: Tabs.extend({ defaultProps: { color: 'dark' }, styles: { tab: { fontWeight: 500 } } }),
    Table: Table.extend({
      defaultProps: { verticalSpacing: 7, horizontalSpacing: 'md', highlightOnHover: true },
      styles: { th: { fontSize: '0.75rem', fontWeight: 550, color: 'var(--mantine-color-dimmed)' } },
    }),
  },
});
