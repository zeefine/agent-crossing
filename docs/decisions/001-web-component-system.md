# ADR-001: Use shadcn/ui for the collaboration workspace

## Status

Accepted, 2026-10-04. The user selected shadcn/ui.

## Context

The frontend uses Next.js 14 and React 18. Its three-column workspace has custom
controls and a large global stylesheet. React Flow provides the task graph and
Lucide provides icons. Consistent controls, accessible dialogs, responsive layouts
and theme ownership are needed without changing backend contracts.

## Decision

Use official shadcn/ui registry components with Tailwind CSS 4. Keep component
source in `services/platform-web/components/ui`; keep product compositions in
`components/workspace`. Retain Next.js, React 18, React Flow, Dagre and Lucide.
Do not introduce another general-purpose component library.

The visual contract is a restrained developer workspace, not a marketing page:

- Preserve the brand wordmark, root route, new-task action and three-column desktop layout.
- Use warm neutral surfaces, a muted green primary action and semantic error/warning colors.
- Keep 8px control radii and 12px panel/composer radii; status badges use a tighter 6px radius.
- Use local Geist fonts, with system Chinese fallbacks. Do not fetch fonts at build time.
- Define shared light/dark semantic variables in `app/globals.css`. React Flow must consume them too.
- Theme defaults to the system and is manually selectable via `next-themes`.
- Below 1280px, tasks use a Sheet. Below 768px, history also uses a Sheet.
- Use stable layouts, minimal state-change motion and reduced-motion support.
- Layer product CSS below Tailwind utilities. Put per-instance shadcn overrides in `className`;
  a layered custom class cannot override a higher-priority utility by specificity alone.
- Overlay/menu components use the registry layer level 50; the focused skip link uses 60.

## Consequences

Components are owned source, not an opaque package. Updates must be reviewed;
blind registry overwrites can remove project changes. The registry currently emits
React 19-style refs: Button and portal overlays have explicit `forwardRef` adapters
for React 18. Browser tests guard against ref warnings and focus regressions.

The platform API, WebSocket protocol, session lifecycle and agent execution remain
unchanged. Message content remains escaped plain text; Markdown rendering is a
separate change. No extra global state library or animation engine is introduced.

Radix Themes would require less setup but was not the user's choice. Handwritten
controls retain too much interaction and styling maintenance. A framework upgrade
is not required for this migration and is intentionally separate.

## Verification

Run `npm run lint`, `npm run typecheck`, `npm test`, and `npm run build` from
`services/platform-web`. Playwright intercepts API and WebSocket requests so tests
never create real agent work or delete real records. It covers confirmation,
failure recovery, keyboard behavior, sends/stops, themes, graph resizing and
320/375/768/1024/1440px layouts. Populated light/dark screens run axe WCAG checks.
Screenshots in `test-results` contain synthetic test content only.

These are frontend integration checks, not a real CLI/backend end-to-end test or
a production performance benchmark.
