import {test,expect,type Page} from '@playwright/test';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {render,manifest,pixels} from './viewer-fixture';
import {resultFixture,resultId} from '../test-fixtures/result';
const bytes=readFileSync(new URL('./fixtures/result.png',import.meta.url)),hash=createHash('sha256').update(bytes).digest('hex');
function gate(){let resolve:()=>void=()=>{throw Error('Uninitialized');};const promise=new Promise<void>(r=>{resolve=r;});return{promise,resolve};}
async function setup(page:Page,state:{revoked?:boolean;corrupt?:boolean;delay?:Promise<void>;captured?:()=>void;tileDelay?:Promise<void>;tileCaptured?:()=>void;reject?:401|409|500}={},base:{revoked?:boolean}={}){
 const data=resultFixture(manifest('scan-qc'),hash);
 await page.route('**/synthetic-results/**',async r=>{const tile=r.request().url().includes('/tiles/');if(state.reject){await r.fulfill({status:state.reject,json:{code:state.reject===401?'UNAUTHENTICATED':'AI_RESULT_INVALIDATED'}});return;}if(state.revoked){await r.fulfill({status:409,json:{code:'AI_RESULT_INVALIDATED'}});return;}if(tile){state.tileCaptured?.();if(state.tileDelay)await state.tileDelay;const b=Buffer.from(bytes);if(state.corrupt)b[40]^=1;await r.fulfill({body:b,contentType:'image/png',headers:{'X-Content-SHA256':hash,'X-Result-Epoch':data.epoch,'Cache-Control':'private, no-store'}});return;}state.captured?.();if(state.delay)await state.delay;await r.fulfill({json:data});});
 await page.route('**/roi?*',r=>r.fulfill({json:{requestId:manifest('scan-qc').content.requestId,scanId:'scan-qc',manifestHash:'c'.repeat(64),version:-1,actorId:'actor',calibration:null,items:[],historyOnly:false}}));
 await render(page,base);await expect.poll(async()=>(await pixels(page)).pink).toBeGreaterThan(500);
 await page.getByLabel('精确合成结果ID',{exact:true}).fill(resultId);await page.getByRole('button',{name:'核验并加载合成叠加',exact:true}).click();
}
async function actualColors(page:Page){return page.getByLabel('实际合成强度图层',{exact:true}).locator('image').evaluate(async el=>{const image=new Image();image.src=(el as SVGImageElement).href.baseVal;await image.decode();const c=document.createElement('canvas');c.width=64;c.height=64;const ctx=c.getContext('2d');if(!ctx)throw Error('Canvas unavailable');ctx.drawImage(image,0,0);const a=Array.from(ctx.getImageData(8,8,1,1).data),b=Array.from(ctx.getImageData(56,56,1,1).data);return{a,b,width:image.width,height:image.height};});}
test('actual result PNG overlays real OSD with opacity blend rotation flip crop and cancellation',async({page},info)=>{
 await setup(page);const layer=page.getByLabel('实际合成强度图层',{exact:true});await expect(layer).toBeVisible();expect(await actualColors(page)).toEqual({a:[0,255,64,255],b:[240,15,64,255],width:64,height:64});
 const canvas=page.getByLabel('合成瓦片画布',{exact:true});await canvas.scrollIntoViewIfNeeded();const before=await canvas.locator('..').screenshot({path:info.outputPath('synthetic-overlay.png')});await info.attach('actual synthetic result overlay',{body:before,contentType:'image/png'});
 await page.getByRole('button',{name:'隐藏合成叠加',exact:true}).click();await expect(layer).toHaveCount(0);const hidden=await canvas.locator('..').screenshot();expect(createHash('sha256').update(hidden).digest('hex')).not.toBe(createHash('sha256').update(before).digest('hex'));await page.getByRole('button',{name:'显示合成叠加',exact:true}).click();await expect(layer).toBeVisible();
 await page.getByRole('slider',{name:'合成透明度',exact:true}).focus();await page.getByRole('slider',{name:'合成透明度',exact:true}).press('ArrowRight');await expect(layer).toHaveCSS('opacity','0.6');
 await page.getByRole('combobox',{name:'合成混合方式',exact:true}).click();await page.getByRole('option',{name:'正片叠底',exact:true}).click();await expect(layer).toHaveCSS('mix-blend-mode','multiply');
 await page.getByRole('button',{name:'打开ROI编辑',exact:true}).click();await page.getByRole('button',{name:'旋转90度',exact:true}).click();await expect(layer).toBeVisible();const rotated=await layer.locator('image').getAttribute('transform');await page.getByRole('button',{name:'水平翻转',exact:true}).click();await expect(layer).toBeVisible();await expect(layer.locator('image')).not.toHaveAttribute('transform',rotated??'');const matrix=await layer.locator('image').getAttribute('transform');const numbers=(matrix??'').slice(7,-1).split(' ').map(Number);expect(numbers[0]*numbers[3]-numbers[1]*numbers[2]).toBeLessThan(0);
 await page.getByRole('button',{name:'仅显示中心裁剪',exact:true}).click();await expect(layer).toBeVisible();await canvas.scrollIntoViewIfNeeded();await canvas.locator('..').screenshot({path:info.outputPath('synthetic-overlay-rotated-flipped-cropped.png')});
 await page.getByRole('button',{name:'取消合成叠加',exact:true}).click();await expect(layer).toHaveCount(0);
});
test('result model revocation clears delivered layer and corrupted PNG never renders',async({page})=>{const state={revoked:false,corrupt:false};await setup(page,state);await expect(page.getByLabel('实际合成强度图层',{exact:true})).toBeVisible();state.revoked=true;await expect(page.getByRole('alert').filter({hasText:'合成结果资格或依据版本已失效'})).toBeVisible({timeout:7000});await expect(page.getByLabel('实际合成强度图层',{exact:true})).toHaveCount(0);state.revoked=false;state.corrupt=true;await page.getByRole('button',{name:'核验并加载合成叠加',exact:true}).click();await expect(page.getByRole('alert').filter({hasText:'合成瓦片损坏'})).toBeVisible();await expect(page.getByLabel('实际合成强度图层',{exact:true})).toHaveCount(0);state.corrupt=false;await page.getByRole('button',{name:'核验并加载合成叠加',exact:true}).click();await expect(page.getByLabel('实际合成强度图层',{exact:true})).toBeVisible();});
test('late result response after image switch is aborted and never enters the second image',async({page})=>{const pending=gate(),captured=gate();await setup(page,{delay:pending.promise,captured:captured.resolve});await captured.promise;await page.getByLabel('选择精确扫描',{exact:true}).click();await page.getByRole('option',{name:'slide-b / 扫描 0',exact:true}).click();await expect.poll(async()=>(await pixels(page)).green).toBeGreaterThan(500);pending.resolve();await expect(page.getByLabel('实际合成强度图层',{exact:true})).toHaveCount(0);await expect(page.getByLabel('精确合成结果ID',{exact:true})).toHaveValue('');await page.getByLabel('精确合成结果ID',{exact:true}).fill(resultId);await page.getByRole('button',{name:'核验并加载合成叠加',exact:true}).click();await expect(page.getByRole('alert').filter({hasText:'与当前精确输入'})).toBeVisible();});

test('QC removal destroys result resources and retains exactly one authorized history panel',async({page})=>{const base={revoked:false};await setup(page,{},base);await expect(page.getByLabel('实际合成强度图层',{exact:true})).toBeVisible();await expect(page.getByLabel('历史ROI ID',{exact:true})).toHaveCount(1);base.revoked=true;await expect(page.getByLabel('合成瓦片画布',{exact:true}).locator('canvas')).toHaveCount(0,{timeout:6000});await expect(page.getByLabel('实际合成强度图层',{exact:true})).toHaveCount(0);await expect(page.getByLabel('历史ROI ID',{exact:true})).toHaveCount(1);});

// Both transport stages are independently gated; rendering must await verified bytes.
test('delayed metadata then PNG render only after both exact responses complete',async({page},info)=>{
 const metadata=gate(),metadataSeen=gate(),tile=gate(),tileSeen=gate();
 await setup(page,{delay:metadata.promise,captured:metadataSeen.resolve,tileDelay:tile.promise,tileCaptured:tileSeen.resolve});
 await metadataSeen.promise;
 const panel=page.getByRole('region',{name:'非诊断合成结果叠加'}),layer=page.getByLabel('实际合成强度图层',{exact:true});
 await expect(panel.getByRole('status')).toContainText('正在核验');await expect(layer).toHaveCount(0);
 metadata.resolve();await tileSeen.promise;
 await expect(panel.getByRole('status')).toContainText('正在核验');await expect(layer).toHaveCount(0);
 tile.resolve();await expect(panel.getByRole('status')).toContainText('合成强度叠加已核验');await expect(layer).toBeVisible();
 expect(await actualColors(page)).toEqual({a:[0,255,64,255],b:[240,15,64,255],width:64,height:64});
 await page.getByLabel('合成瓦片画布',{exact:true}).scrollIntoViewIfNeeded();await page.screenshot({path:info.outputPath('delayed-result-verified-pixels.png'),fullPage:true});
});

test('hide cancels pending toggle pixels and show requires fresh metadata and bytes',async({page},info)=>{
 const state:{delay?:Promise<void>;captured?:()=>void;tileDelay?:Promise<void>;tileCaptured?:()=>void}={};
 await setup(page,state);const layer=page.getByLabel('实际合成强度图层',{exact:true}),panel=page.getByRole('region',{name:'非诊断合成结果叠加'});
 await expect(layer).toBeVisible();const oldUrl=await layer.locator('image').getAttribute('href');
 await page.getByRole('button',{name:'隐藏合成叠加',exact:true}).click();await expect(layer).toHaveCount(0);await expect(panel.getByRole('status')).toContainText('叠加已隐藏并清空');
 const metadata=gate(),metadataSeen=gate(),tile=gate(),tileSeen=gate();Object.assign(state,{delay:metadata.promise,captured:metadataSeen.resolve,tileDelay:tile.promise,tileCaptured:tileSeen.resolve});
 await page.getByRole('button',{name:'显示合成叠加',exact:true}).click();await metadataSeen.promise;await expect(layer).toHaveCount(0);
 metadata.resolve();await tileSeen.promise;await expect(layer).toHaveCount(0);
 await page.getByRole('button',{name:'隐藏合成叠加',exact:true}).click();await expect(panel.getByRole('status')).toContainText('叠加已隐藏并清空');tile.resolve();
 await expect(layer).toHaveCount(0);
 const next=gate();state.captured=next.resolve;await page.getByRole('button',{name:'显示合成叠加',exact:true}).click();await next.promise;
 await expect(layer).toBeVisible();await expect(layer.locator('image')).not.toHaveAttribute('href',oldUrl??'');
 expect(await actualColors(page)).toEqual({a:[0,255,64,255],b:[240,15,64,255],width:64,height:64});
 await page.getByLabel('合成瓦片画布',{exact:true}).scrollIntoViewIfNeeded();await page.screenshot({path:info.outputPath('toggle-reverified-pixels.png'),fullPage:true});
});
for(const reject of [401,409,500] as const)test(`show refusal ${reject} never revives previously delivered pixels`,async({page})=>{
 const state:{reject?:401|409|500}={};await setup(page,state);const layer=page.getByLabel('实际合成强度图层',{exact:true});await expect(layer).toBeVisible();
 await page.getByRole('button',{name:'隐藏合成叠加',exact:true}).click();await expect(layer).toHaveCount(0);state.reject=reject;
 await page.getByRole('button',{name:'显示合成叠加',exact:true}).click();
 await expect(page.getByRole('region',{name:'非诊断合成结果叠加'}).getByRole('alert').filter({hasText:reject===401?'登录已失效':'合成结果资格或依据版本已失效'})).toBeVisible();await expect(layer).toHaveCount(0);
 await page.getByRole('button',{name:'取消合成叠加',exact:true}).click();await expect(layer).toHaveCount(0);
});
