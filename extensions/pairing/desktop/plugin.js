import { ROUTES_AREA, SIDEBAR_NAV_AREA } from '@hermes/plugin-sdk'
import { useEffect, useRef, useState } from 'react'
import { jsx as h, jsxs as hs } from 'react/jsx-runtime'

export default {
  id: 'hermes-companion-pairing',
  name: 'Connect phone',
  defaultEnabled: true,
  register(ctx) {
    function PairPage() {
      const [url, setUrl] = useState('')
      const [pair, setPair] = useState(null)
      const [error, setError] = useState('')
      const [working, setWorking] = useState(false)
      const [ready, setReady] = useState(false)
      const [message, setMessage] = useState('')
      const current = useRef(null)
      const mounted = useRef(false)
      const secret = useRef('')
      useEffect(() => {
        mounted.current = true
        const bytes = new Uint8Array(32)
        crypto.getRandomValues(bytes)
        secret.current = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
        ctx.files.writeText('data', 'pair-capability.txt', secret.current).then(result => {
          if (!result.ok) throw new Error('Desktop pairing authorization unavailable')
          return ctx.rest('/pair/address', { method: 'POST', body: { secret: secret.current } })
        }).then(r => { if (mounted.current) { if (r.url) setUrl(r.url); setReady(true) } }).catch(e => { if (mounted.current) setError(String(e.message || e)) })
        return () => {
          mounted.current = false
          if (current.current) void ctx.rest('/pair/cancel/' + current.current, { method: 'POST', body: { secret: secret.current } }).catch(() => {})
          current.current = null
        }
      }, [])
      useEffect(() => {
        if (!pair) return
        const timer = setInterval(() => {
          ctx.rest('/pair/status/' + pair.device_id, { method: 'POST', body: { secret: secret.current } }).then(r => {
            if (mounted.current && r.state === 'used' && current.current === pair.device_id) {
              current.current = null; setPair(null)
              setMessage('Code used. Check your phone; if it did not connect, create a new code.')
            }
          }).catch(() => {})
        }, 2000)
        return () => clearInterval(timer)
      }, [pair])
      async function create() {
        if (working) return
        setWorking(true); setError(''); setMessage('')
        try {
          if (current.current) await ctx.rest('/pair/cancel/' + current.current, { method: 'POST', body: { secret: secret.current } })
          const result = await ctx.rest('/pair/start', { method: 'POST', body: { url, secret: secret.current } })
          if (!mounted.current) {
            void ctx.rest('/pair/cancel/' + result.device_id, { method: 'POST', body: { secret: secret.current } }).catch(() => {})
            return
          }
          current.current = result.device_id
          setPair(result)
          setTimeout(() => {
            if (current.current === result.device_id) {
              current.current = null; setPair(null)
              setMessage('Code expired. Create another to connect.')
              void ctx.rest('/pair/cancel/' + result.device_id, { method: 'POST', body: { secret: secret.current } }).catch(() => {})
            }
          }, result.expires_in * 1000)
        } catch (e) { if (mounted.current) { setError(String(e.message || e)); setPair(null) } }
        finally { if (mounted.current) setWorking(false) }
      }
      return hs('div', { style: { padding: 32, maxWidth: 560, color: 'var(--ui-text-primary)' }, children: [
        h('h1', { children: 'Connect your phone' }),
        h('p', { children: 'Open Hermes Companion on your phone and scan the code. Keep the code private.' }),
        h('label', { htmlFor: 'mobile-url', children: 'Secure dashboard address' }),
        h('input', { id: 'mobile-url', type: 'url', placeholder: 'https://your-hermes-address', value: url,
          onChange: e => setUrl(e.target.value), style: { display: 'block', width: '100%', margin: '8px 0 16px', padding: 9, background: 'var(--ui-bg-base)', color: 'var(--ui-text-primary)', border: '1px solid var(--ui-border)' } }),
        h('button', { type: 'button', disabled: working || !url || !ready, onClick: create, children: working ? 'Creating…' : 'Create pairing code' }),
        error && h('p', { role: 'alert', children: error }),
        message && h('p', { role: 'status', children: message }),
        pair && hs('div', { children: [ h('img', { alt: 'Pairing QR code', src: 'data:image/svg+xml;base64,' + pair.image, style: { display: 'block', width: 280, height: 280, marginTop: 20 } }),
          h('p', { children: 'This code expires after two minutes. If pairing fails, create a new code.' }) ] })
      ] })
    }
    ctx.register({ id: 'page', area: ROUTES_AREA, data: { path: '/connect-phone' }, render: () => h(PairPage, {}) })
    ctx.register({ id: 'nav', area: SIDEBAR_NAV_AREA, data: { path: '/connect-phone', label: 'Connect phone', codicon: 'device-mobile' } })
  }
}
