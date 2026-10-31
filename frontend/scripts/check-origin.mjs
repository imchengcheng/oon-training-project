import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import { createServer } from 'vite'
import { chromium } from '@playwright/test'

// Isolated echo backend: never sends login credentials or touches the application database.
const echo = http.createServer((req, res) => {
  res.setHeader('Content-Type', 'application/json')
  res.end(JSON.stringify({ origin: req.headers.origin ?? null, host: req.headers.host }))
})
await new Promise(resolve => echo.listen(0, '127.0.0.1', resolve))
const savedProxy = process.env.API_PROXY
process.env.API_PROXY = `http://127.0.0.1:${echo.address().port}`
let vite, browser
try {
  // Loads the project's actual vite.config.js, including its /api proxy.
  const reservation = net.createServer()
  await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve))
  const port = reservation.address().port
  await new Promise(resolve => reservation.close(resolve))
  vite = await createServer({ server: { host: '127.0.0.1', port, open: false } })
  await vite.listen()
  const frontend = `http://127.0.0.1:${vite.httpServer.address().port}`
  const checks = []
  for (const origin of ['http://127.0.0.1:5173', 'http://127.0.0.1']) {
    const response = await fetch(`${frontend}/api/origin-check`, { method: 'POST', headers: { Origin: origin } })
    const received = await response.json()
    assert.equal(received.origin, origin)
    checks.push({ sentOrigin: origin, backendOrigin: received.origin, preserved: true })
  }
  // Fresh Chrome profile isolates installed extensions from normal browser behavior.
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  const page = await browser.newPage()
  await page.goto(`${frontend}/login`)
  const received = await page.evaluate(async () => {
    const response = await fetch('/api/origin-check', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
    return response.json()
  })
  assert.equal(received.origin, frontend)
  checks.push({ browser: 'Chrome, fresh profile', pageOrigin: frontend, backendOrigin: received.origin, preserved: true })
  process.stdout.write(`${JSON.stringify({ checks }, null, 2)}\n`)
} finally {
  await browser?.close()
  await vite?.close()
  await new Promise(resolve => echo.close(resolve))
  if (savedProxy === undefined) delete process.env.API_PROXY
  else process.env.API_PROXY = savedProxy
}
