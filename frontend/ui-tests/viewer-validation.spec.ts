import { test, expect, type Request } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { render, pixels } from './viewer-fixture';
for (const viewport of [{ width: 1280, height: 900 }, { width: 1024, height: 768 }]) {
 test(`T33 real OSD pixels and bounded A B lifecycle ${viewport.width}`, async ({ page }, info) => {
  await page.setViewportSize(viewport);
  const pending = new Set<Request>(); let peak = 0, requests = 0;
  page.on('request', r => { if (r.url().includes('/viewer/tiles/')) { pending.add(r); peak = Math.max(peak, pending.size); requests++; } });
  const end = (r: Request) => { pending.delete(r); };
  page.on('requestfinished', end); page.on('requestfailed', end);
  await render(page);
  await expect.poll(async () => (await pixels(page)).pink).toBeGreaterThan(500);
  const colors = await page.getByLabel('合成瓦片画布', { exact: true }).locator('canvas').first().evaluate(node => {
   const c = node as HTMLCanvasElement, context = c.getContext('2d'); if (!context) throw Error('No real canvas');
   const data = context.getImageData(0, 0, c.width, c.height).data;
   const counts = [0, 0, 0], palette = [[32, 80, 110], [160, 72, 155], [245, 210, 230]];
   for (let i = 0; i < data.length; i += 4) for (let j = 0; j < 3; j++) if (palette[j].every((v, k) => v === data[i + k]) && data[i + 3] === 255) counts[j]++;
   return { counts, width: c.width, height: c.height };
  });
  for (const count of colors.counts) expect(count).toBeGreaterThan(100);
  const cycles: { scan: string; canvases: number }[] = [];
  for (let i = 0; i < 6; i++) {
   const b = i % 2 === 0;
   await page.getByLabel('选择精确扫描', { exact: true }).click();
   await page.getByRole('option', { name: `${b ? 'slide-b' : 'slide-a'} / 扫描 0`, exact: true }).click();
   await expect.poll(async () => (await pixels(page))[b ? 'green' : 'pink']).toBeGreaterThan(500);
   const canvases = page.getByLabel('合成瓦片画布', { exact: true }).locator('canvas');
   await expect(canvases).toHaveCount(2); cycles.push({ scan: b ? 'B' : 'A', canvases: await canvases.count() });
  }
  await page.getByRole('button', { name: '打开第二视图' }).click();
  await expect(page.getByRole('button', { name: '显式开启像素恒等同步' })).toBeEnabled();
  await expect(page.getByLabel('合成瓦片画布', { exact: true }).locator('canvas')).toHaveCount(4);
  await page.getByRole('button', { name: '显式开启像素恒等同步' }).click();
  await page.getByRole('region', { name: '主视图', exact: true }).getByRole('button', { name: '放大图像', exact: true }).click();
  await page.screenshot({ path: info.outputPath(`t33-dual-${viewport.width}.png`), fullPage: true });
  await page.getByRole('button', { name: '关闭第二视图' }).click();
  await page.getByRole('button', { name: '停止加载图像' }).click();
  await expect(page.getByLabel('合成瓦片画布', { exact: true }).locator('canvas')).toHaveCount(0);
  await expect.poll(() => pending.size).toBe(0); expect(peak).toBeLessThanOrEqual(8); expect(requests).toBeLessThan(250);
  writeFileSync(info.outputPath(`t33-browser-${viewport.width}.json`), JSON.stringify({ viewport, browser: page.context().browser()?.version(), colors, cycles, peakTileRequests: peak, totalTileRequests: requests, pendingAtStop: pending.size, finalCanvases: 0, heap: 'UNMEASURED: DOM and request counts do not establish heap leak freedom', transport: 'synthetic HTTP fixture; real OpenSeadragon canvas' }, null, 2));
 });
}
test('T33 corrupt binary never becomes a blank success and retry requires current authorization', async ({ page }) => {
 const state = { broken: true }; await render(page, state);
 await expect(page.getByText('瓦片缺失，不能将空白解释为阴性。', { exact: true })).toBeVisible();
 await page.unroute('**/viewer/tiles/**');
 await page.route('**/viewer/tiles/**', async route => {
  const part = new URL(route.request().url()).pathname.match(/tiles\/(\d+)\/(\d+)\/(\d+)$/); if (!part) throw Error('Missing tile coordinates');
  const bytes = readFileSync(new URL(`./fixtures/viewer/a/${part[1]}-${part[2]}-${part[3]}.png`, import.meta.url)); bytes[0] = 0;
  await route.fulfill({ status: 200, contentType: 'image/png', body: bytes, headers: { 'Content-Length': String(bytes.length), 'X-Content-SHA256': '0'.repeat(64) } });
 });
 state.broken = false; await page.getByRole('button', { name: '重新授权并加载' }).click();
 await expect(page.getByRole('alert').filter({ hasText: '瓦片PNG无效' })).toBeVisible();
 await expect(page.getByLabel('合成瓦片画布', { exact: true }).locator('canvas')).toHaveCount(0);
 await page.unroute('**/viewer/tiles/**');
 await page.getByRole('button', { name: '重新授权并加载' }).click();
 await expect.poll(async () => (await pixels(page)).pink).toBeGreaterThan(500);
});
