export function NotificationBadge({ count }: { count: number }) {
  if (count <= 0) return null
  return (
    <span className="notification-badge" aria-label={`未読通知${count}件`}>
      {count > 99 ? '99+' : count}
    </span>
  )
}
