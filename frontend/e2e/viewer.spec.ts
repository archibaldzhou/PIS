import { expect } from '@playwright/test';
import { createHash } from 'node:crypto';
import {safeResultBody} from './result-diagnostics';
import { test, forbidden } from './viewer-fixture';
test('viewer RGB pixels ROI and QC revocation protect warmed resources', async ({page,browser,source},info)=>{
  const {headers,scanPath,scanId,qcPath,viewerPath}=source;
  const original = await page.getByText(/图像相对缩放/).textContent(); await page.getByRole('button', { name: '放大图像' }).click(); await expect(page.getByText(/图像相对缩放/)).not.toHaveText(original ?? '');
  await page.getByRole('button', { name: '适配窗口' }).click(); await page.screenshot({ path: info.outputPath('real-service-synthetic-viewer.png'), fullPage: true });
  const roiResponse=page.waitForResponse(r=>r.url().includes(`${scanPath}/${scanId}/roi?publicationVersion=2`)&&r.request().method()==='GET',{timeout:10_000});
  await page.getByRole('button', { name: '打开ROI编辑', exact: true }).click();
  const roiOpened=await roiResponse;console.log('T36_ROI_HTTP '+JSON.stringify({status:roiOpened.status(),body:safeResultBody(await roiOpened.body())}));expect(roiOpened.status()).toBe(200);
  await expect(page.getByText(/集合版本 -1/)).toBeVisible();
  await page.getByRole('button', { name: '新建ROI并点击画布' }).click();
  const layer = page.getByLabel('ROI图像叠加层', { exact: true });
  await layer.click({ position: { x: 300, y: 170 } }); await layer.click({ position: { x: 460, y: 280 } });
  await page.getByLabel('ROI人工原因', { exact: true }).fill('Synthetic E2E rectangle');
  await page.getByRole('button', { name: '保存ROI修订', exact: true }).click(); await expect(page.getByText(/集合版本 0/)).toBeVisible();
  const roiPath = `${scanPath}/${scanId}/roi`; const roiView = await (await page.request.get(`${roiPath}?publicationVersion=2`)).json() as { items: { roiId: string; measurement: { unit: string; area: number } }[] };
  expect(roiView.items).toHaveLength(1); expect(roiView.items[0].measurement.unit).toBe('px'); expect(roiView.items[0].measurement.area).toBeGreaterThan(0);
  await page.getByRole('button', { name: '旋转90度' }).click(); await page.getByRole('button', { name: '水平翻转' }).click(); await expect(page.locator('[data-roi-shape]')).toHaveCount(1);
  await page.screenshot({ path: info.outputPath('real-service-synthetic-roi.png'), fullPage: true });
  const tilePath = `${viewerPath}/tiles/9/0/0?publicationVersion=2`; const tile = await page.request.get(tilePath); expect(tile.status()).toBe(200); expect((await tile.body()).subarray(0, 8)).toEqual(Buffer.from([137,80,78,71,13,10,26,10])); expect(createHash('sha256').update(await tile.body()).digest('hex')).toBe(tile.headers()['x-content-sha256']);
  // T33: actual authorized binary decode, fixed RGB channels and bounded concurrent reads.
  const rgb = await page.evaluate(async (bytes: number[]) => {
    const image = await createImageBitmap(new Blob([new Uint8Array(bytes)], { type: 'image/png' }));
    try { const canvas = document.createElement('canvas'); canvas.width = image.width; canvas.height = image.height;
      const ctx = canvas.getContext('2d'); if (!ctx) throw Error('No decoder canvas'); ctx.drawImage(image, 0, 0);
      return { width: image.width, height: image.height, cell: Array.from(ctx.getImageData(32, 32, 1, 1).data), background: Array.from(ctx.getImageData(4, 4, 1, 1).data) };
    } finally { image.close(); }
  }, Array.from(await tile.body()));
  expect(rgb).toEqual({ width: 128, height: 128, cell: [160, 72, 157, 255], background: [238, 210, 232, 255] });
  const concurrent = await Promise.all(Array.from({ length: 4 }, () => page.request.get(tilePath)));
  for (const response of concurrent) { expect(response.status()).toBe(200); expect(await response.body()).toEqual(await tile.body()); expect(response.headers()['cache-control']).toBe('private, no-store'); }
  expect((await page.request.post(`${qcPath}/REVOKE`, { headers: headers(), data: { expectedVersion: 2, assessmentVersion: 1, reason: 'Synthetic viewer revoke' } })).status()).toBe(200);
  expect((await page.request.get(`${scanPath}/${scanId}/ai?publicationVersion=2`)).status()).toBe(409);
  for (const resource of [tilePath, `${viewerPath}?publicationVersion=2`, `${viewerPath}/thumbnail?publicationVersion=2`]) expect((await page.request.get(resource)).status()).toBe(409);
  await expect(page.getByLabel('合成瓦片画布', { exact: true }).locator('canvas')).toHaveCount(0, { timeout: 6000 });
  expect((await page.request.get(`${roiPath}?publicationVersion=2`)).status()).toBe(409);
  expect((await page.request.get(`${roiPath}/${roiView.items[0].roiId}/history`)).status()).toBe(200);
  await expect(page.getByLabel('ROI图像叠加层', { exact: true })).toHaveCount(0);
  await forbidden(browser,[qcPath,`${roiPath}/${roiView.items[0].roiId}/history`,tilePath,`${qcPath}/bytes?publicationVersion=2`]);
});
