import type * as React from 'react';

/** Shared tone names. accent follows data-accent; the rest are semantic. */
export type Tone = 'accent' | 'ok' | 'warn' | 'err' | 'info' | 'muted';
export type IconName =
  | 'dashboard' | 'queue' | 'failures' | 'warning' | 'activity' | 'reports' | 'flows' | 'systems' | 'contracts'
  | 'rules' | 'mappings' | 'mapping-json' | 'mapping-xml' | 'document' | 'rows' | 'menu' | 'trend' | 'check'
  | 'plus' | 'close' | 'refresh' | 'search' | 'upload' | 'sign-out' | 'sign-in' | 'grid-4' | 'chevron-down'
  | 'arrow' | 'moon' | 'sun' | 'palette';
export type Accent = 'indigo' | 'emerald' | 'rose' | 'amber' | 'cyan' | 'violet';
export type Mode = 'light' | 'dark';

// ── Brand ──
export interface LogoMarkProps { /** 32 in the sidebar, 44 on login. */ size?: number; label?: string; className?: string }
export declare function LogoMark(props: LogoMarkProps): React.ReactElement;
export interface IconProps { name: IconName; /** px, or [width, height]. */ size?: number | [number, number]; strokeWidth?: number; /** Gives the icon an accessible name; omit for decorative icons. */ title?: string; className?: string; style?: React.CSSProperties }
export declare function Icon(props: IconProps): React.ReactElement | null;

// ── Actions ──
export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  /** secondary (default) · primary (one per header or footer, last) · danger (text in err-t). */
  variant?: "secondary" | "primary" | "danger" | "ghost" | "link";
  /** sm = table row actions · md · lg = full-width login buttons. */
  size?: 'sm' | 'md' | 'lg';
  icon?: IconName;
  block?: boolean;
}
export declare function Button(props: ButtonProps): React.ReactElement;
export interface IconButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> { icon?: IconName; /** Required accessible name. */ label: string; children?: React.ReactNode }
export declare function IconButton(props: IconButtonProps): React.ReactElement;

// ── Shell ──
export interface AppShellProps { sidebar: React.ReactNode; topbar: React.ReactNode; children?: React.ReactNode; /** ≤800px: show the sidebar as an off-canvas panel. */ navOpen?: boolean; onNavClose?: () => void; className?: string }
export declare function AppShell(props: AppShellProps): React.ReactElement;
export interface NavEntry { id: string; label: string; icon: IconName; count?: number; countTone?: 'neutral' | 'danger'; href?: string }
export interface NavGroup { title: string; items: NavEntry[] }
export interface SidebarProps { product?: string; version?: string; /** Defaults to Operations + Configuration. */ groups?: NavGroup[]; active?: string; onNavigate?: (id: string) => void; footer?: React.ReactNode; className?: string }
export declare function Sidebar(props: SidebarProps): React.ReactElement;
export interface NavItemProps extends React.AnchorHTMLAttributes<HTMLAnchorElement> { icon?: IconName; label: string; count?: number; countTone?: 'neutral' | 'danger'; active?: boolean }
export declare function NavItem(props: NavItemProps): React.ReactElement;
export interface TopbarProps { product?: string; /** Usually <CommandSearch/>. */ search?: React.ReactNode; /** Right cluster: EnvBadge, ModeToggle, ThemePicker, UserChip. */ children?: React.ReactNode; /** Shows the ≤800px menu button. */ onMenu?: () => void; className?: string }
export declare function Topbar(props: TopbarProps): React.ReactElement;
export interface CommandSearchProps { placeholder?: string; /** Defaults to "Ctrl K" or "⌘K". */ shortcut?: string; label?: string; defaultValue?: string; onChange?: (value: string) => void; onSubmit?: (value: string) => void; className?: string }
export declare function CommandSearch(props: CommandSearchProps): React.ReactElement;
export interface EnvBadgeProps { env?: string; /** Production ok · Staging warn · Dev info. */ tone?: 'ok' | 'warn' | 'info'; className?: string }
export declare function EnvBadge(props: EnvBadgeProps): React.ReactElement;
export interface UserChipProps { name: string; initials?: string; onClick?: () => void; className?: string }
export declare function UserChip(props: UserChipProps): React.ReactElement;
export interface ModeToggleProps { mode?: Mode; onToggle?: (next: Mode) => void; className?: string }
export declare function ModeToggle(props: ModeToggleProps): React.ReactElement;
export interface ThemePickerProps { accent?: Accent; mode?: Mode; onAccentChange?: (a: Accent) => void; onModeChange?: (m: Mode) => void; defaultOpen?: boolean; /** Render the popover in flow (no trigger). */ inline?: boolean }
export declare function ThemePicker(props: ThemePickerProps): React.ReactElement;
export interface PageHeaderProps { title: string; subtitle?: string; actions?: React.ReactNode; className?: string }
export declare function PageHeader(props: PageHeaderProps): React.ReactElement;

// ── Surfaces ──
export interface CardProps extends React.HTMLAttributes<HTMLElement> { as?: keyof JSX.IntrinsicElements; padded?: boolean; interactive?: boolean }
export declare function Card(props: CardProps): React.ReactElement;
export interface PanelProps { title?: string; /** Right of the title: a link or Segmented. */ aside?: React.ReactNode; padded?: boolean; className?: string; children?: React.ReactNode }
export declare function Panel(props: PanelProps): React.ReactElement;
export interface DrawerProps { open?: boolean; title: string; onClose?: () => void; /** Cancel/Close first, at most one primary, last. */ footer?: React.ReactNode; children?: React.ReactNode }
export declare function Drawer(props: DrawerProps): React.ReactElement;

// ── Data ──
export interface StatTileProps { tone?: 'accent' | 'ok' | 'warn' | 'err'; icon?: IconName; label: string; value: React.ReactNode; delta?: string; deltaDirection?: 'up' | 'down'; sub?: React.ReactNode; /** 9 values, 0–100. */ spark?: number[]; className?: string }
export declare function StatTile(props: StatTileProps): React.ReactElement;
export interface KpiCardProps { value: React.ReactNode; label: string; tone?: Tone; /** lg 28px · md 24px · sm 18px (drawers). */ size?: 'lg' | 'md' | 'sm'; className?: string }
export declare function KpiCard(props: KpiCardProps): React.ReactElement;
export interface SegmentedProps { options?: string[]; value?: string; defaultValue?: string; onChange?: (v: string) => void; label?: string; className?: string }
export declare function Segmented(props: SegmentedProps): React.ReactElement;
export interface LineChartProps { data: number[]; /** Top of the scale; defaults to the data max rounded up to 10. */ max?: number; /** Defaults to hours 00 04 08 12 16 20 placed at their index. */ xLabels?: { at: number; text: string }[]; height?: number; yTicks?: number; label?: string }
export declare function LineChart(props: LineChartProps): React.ReactElement;
export interface DonutSegment { label: string; value: number; tone: Tone }
export interface DonutChartProps { segments: DonutSegment[]; total?: number; label?: string; size?: number }
export declare function DonutChart(props: DonutChartProps): React.ReactElement;
export interface DataTableColumn<R = any> { key: string; header: React.ReactNode; align?: 'left' | 'right'; mono?: boolean; numeric?: boolean; width?: number | string; render?: (row: R) => React.ReactNode }
export interface DataTableProps<R = any> { columns: DataTableColumn<R>[]; rows: R[]; rowKey?: string; onRowClick?: (row: R) => void; caption?: string; className?: string }
export declare function DataTable<R = any>(props: DataTableProps<R>): React.ReactElement;
export interface DecisionTableColumn { key: string; header: string; kind?: 'cond' | 'out' | 'plain' }
export interface DecisionTableProps { columns: DecisionTableColumn[]; rows: Record<string, React.ReactNode>[]; caption?: string }
export declare function DecisionTable(props: DecisionTableProps): React.ReactElement;
export interface KeyValueProps { label: React.ReactNode; value: React.ReactNode; mono?: boolean; tone?: Tone; className?: string }
export declare function KeyValue(props: KeyValueProps): React.ReactElement;
export interface TimelineItem { time: string; text: React.ReactNode; tone?: 'ok' | 'err' | 'warn' | 'info' }
export interface TimelineProps { items: TimelineItem[]; compact?: boolean; label?: string }
export declare function Timeline(props: TimelineProps): React.ReactElement;
export interface HealthRowProps { name: string; latency: string; status?: 'Healthy' | 'Slow' | 'Down' }
export declare function HealthRow(props: HealthRowProps): React.ReactElement;
export interface QuickActionProps { icon: IconName; title: string; description: string; tone?: Tone; onClick?: () => void }
export declare function QuickAction(props: QuickActionProps): React.ReactElement;
export interface FlowMeta { label: string; value: React.ReactNode; tone?: Tone }
export interface FlowCardProps { name: string; status?: 'Active' | 'Draft' | 'Error' | 'Paused'; description?: string; meta?: FlowMeta[]; /** 0–100; meaning to be confirmed with product. */ progress?: number; onOpen?: () => void }
export declare function FlowCard(props: FlowCardProps): React.ReactElement;
export interface EntityRow { label: string; value: React.ReactNode; tone?: Tone; mono?: boolean }
export interface EntityCardProps { icon: IconName; tone?: Tone; name: string; typeLine?: string; rows?: EntityRow[]; onOpen?: () => void }
export declare function EntityCard(props: EntityCardProps): React.ReactElement;
export interface PipelineStage { label: string; role: 'source' | 'validate' | 'transform' | 'target' }
export interface PipelineProps { stages: PipelineStage[]; label?: string }
export declare function Pipeline(props: PipelineProps): React.ReactElement;
export interface CodePreviewProps { code: string; language?: 'xml' | 'json' | 'plain'; maxHeight?: number; label?: string }
export declare function CodePreview(props: CodePreviewProps): React.ReactElement;
export interface MappingCanvasProps { source?: string; target?: string; transform?: string; height?: number }
export declare function MappingCanvas(props: MappingCanvasProps): React.ReactElement;

// ── Feedback ──
export interface StatusBadgeProps { /** A vocabulary word: Active, Draft, Error, Paused, Pending, Processing, Retry #n, High, Normal, OK, Healthy, Slow, Down, XML, JSON, EDIFACT. */ status: string; tone?: Tone; dot?: boolean; size?: 'md' | 'xs'; children?: React.ReactNode; className?: string }
export declare function StatusBadge(props: StatusBadgeProps): React.ReactElement;
export interface TagListProps { tags: string[]; mono?: boolean; label?: string }
export declare function TagList(props: TagListProps): React.ReactElement;
export interface CalloutProps { tone?: Tone; title?: React.ReactNode; children?: React.ReactNode; className?: string }
export declare function Callout(props: CalloutProps): React.ReactElement;

// ── Forms ──
export interface FieldProps { label: string; help?: string; id?: string; /** Exactly one control: Input or Select. */ children: React.ReactElement }
export declare function Field(props: FieldProps): React.ReactElement;
export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> { /** Mono + uppercase, for identifiers. */ code?: boolean }
export declare const Input: React.ForwardRefExoticComponent<InputProps & React.RefAttributes<HTMLInputElement>>;
export interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> { options: (string | { value: string; label: string })[] }
export declare function Select(props: SelectProps): React.ReactElement;
export interface SwitchProps { checked?: boolean; defaultChecked?: boolean; onChange?: (on: boolean) => void; label: string; id?: string; disabled?: boolean }
export declare function Switch(props: SwitchProps): React.ReactElement;
export interface DropzoneProps { title?: string; hint?: string; accept?: string; multiple?: boolean; onFiles?: (files: File[]) => void }
export declare function Dropzone(props: DropzoneProps): React.ReactElement;

// ── Screens ──
export interface LoginFeature { icon: IconName; label: string }
export interface LoginScreenProps { /** Use \n for the line break. */ headline?: string; copy?: string; features?: LoginFeature[]; title?: string; subtitle?: string; ssoLabel?: string; onSSO?: () => void; altLabel?: string; altIcon?: IconName; onAlt?: () => void }
export declare function LoginScreen(props: LoginScreenProps): React.ReactElement;

declare global {
  interface Window {
    IpaasConsole: {
      LogoMark: typeof LogoMark; Icon: typeof Icon; Button: typeof Button; IconButton: typeof IconButton;
      AppShell: typeof AppShell; Sidebar: typeof Sidebar; NavItem: typeof NavItem; Topbar: typeof Topbar; CommandSearch: typeof CommandSearch;
      EnvBadge: typeof EnvBadge; UserChip: typeof UserChip; ModeToggle: typeof ModeToggle; ThemePicker: typeof ThemePicker; PageHeader: typeof PageHeader;
      Card: typeof Card; Panel: typeof Panel; Drawer: typeof Drawer;
      StatTile: typeof StatTile; KpiCard: typeof KpiCard; Segmented: typeof Segmented; LineChart: typeof LineChart; DonutChart: typeof DonutChart;
      DataTable: typeof DataTable; DecisionTable: typeof DecisionTable; KeyValue: typeof KeyValue; Timeline: typeof Timeline; HealthRow: typeof HealthRow;
      QuickAction: typeof QuickAction; FlowCard: typeof FlowCard; EntityCard: typeof EntityCard; Pipeline: typeof Pipeline; CodePreview: typeof CodePreview; MappingCanvas: typeof MappingCanvas;
      StatusBadge: typeof StatusBadge; TagList: typeof TagList; Callout: typeof Callout;
      Field: typeof Field; Input: typeof Input; Select: typeof Select; Switch: typeof Switch; Dropzone: typeof Dropzone;
      LoginScreen: typeof LoginScreen;
    };
  }
}
