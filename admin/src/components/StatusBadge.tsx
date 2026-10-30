interface Props {
  value?: string
  label: (value: string) => string
  tone?: (value: string) => string
}

export default function StatusBadge({ value, label, tone }: Props) {
  if (!value) {
    return <span className="muted">—</span>
  }
  const cls = tone ? tone(value) : 'gray'
  return <span className={`badge ${cls}`}>{label(value)}</span>
}
