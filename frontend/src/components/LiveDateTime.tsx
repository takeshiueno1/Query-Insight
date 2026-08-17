import { useEffect, useState } from 'react'
function label(date: Date) {
  const parts = new Intl.DateTimeFormat('ja-JP', { timeZone: 'Asia/Tokyo', year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(date)
  const value = (type: Intl.DateTimeFormatPartTypes) => parts.find((part) => part.type === type)?.value ?? ''
  return `${value('year')}/${Number(value('month'))}/${Number(value('day'))} ${value('hour')}:${value('minute')}`
}
export function LiveDateTime() { const [current, setCurrent] = useState(() => new Date()); useEffect(() => { const timer = window.setInterval(() => setCurrent(new Date()), 60_000); return () => window.clearInterval(timer) }, []); return <time className="live-datetime" dateTime={current.toISOString()}>{label(current)}</time> }
