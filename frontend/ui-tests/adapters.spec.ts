import {test,expect,type Page} from '@playwright/test';
const rid='44444444-4444-4444-8444-444444444444',vid='55555555-5555-4555-8555-555555555555',caseId='66666666-6666-4666-8666-666666666666';
const scope='11111111-1111-4111-8111-111111111111';
const encounter={id:'22222222-2222-4222-8222-222222222222',patientId:'33333333-3333-4333-8333-333333333333',patientLabel:'合成患者',encounterNumber:'SYN-001'};
const item={id:vid,requestId:rid,caseId,sourceId:scope,adapter:'HIS',externalId:'SYN-ONE',sequence:1,payloadHash:'a'.repeat(64),version:0,state:'QUEUED',attempts:0,attemptId:null,leaseUntil:null,nextAt:'2026-10-04T00:00:00Z',errorCode:'',locallyRecorded:false};
const view={requestId:rid,caseId,patientId:encounter.patientId,sourceId:scope,page:1,items:[item],capabilities:{HL7_FHIR:'NOT_CONFIGURED',VENDOR_HIS_EMR_BILLING_DEVICE:'NOT_CONFIGURED'}};
async function start(page: Page) {
  await page.route('**/api/auth/me', route => route.fulfill({ json: { id: 'synthetic-user', username: 'synthetic', displayName: '合成用户' } }));
  await page.route('**/api/auth/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'synthetic-only' } }));
  await page.route('**/api/hello', route => route.fulfill({ json: { application: 'PIS', message: 'Hello World' } }));
  await page.route('**/api/requests/scopes', route => route.fulfill({ json: [{ id: scope, name: '合成院区 / 科室' }] }));
  await page.route('**/api/requests?*', route => route.fulfill({ json: { items: [], total: 0, page: 1, pageSize: 20 } }));
  await page.route('**/api/requests/encounters?*', route => route.fulfill({ json: [encounter] }));
  await page.goto('/');
  await page.getByRole('button', { name: '申请登记工作区' }).click();
  await page.getByRole('combobox', { name: '授权工作范围' }).click();
  await page.getByText('合成院区 / 科室', { exact: true }).last().click();
}
async function openAdapters(page: Page) {
 await start(page); const d = { id: rid, scopeId: scope, version: 2, state: 'RECEIVED', requestNumber: 'DEV-AP-ADAPTER', patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterId: encounter.id, encounterNumber: encounter.encounterNumber, department: '合成科室', requestedAt: '2026-01-01T08:00:00Z', clinicalHistory: '合成资料', sampledAt: '2026-01-01T08:00:00Z', containers: [{ id: vid, site: 'Synthetic', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] };
 await page.route('**/api/requests?*', r => r.fulfill({ json: { items: [d], total: 1, page: 1, pageSize: 20 } })); await page.route('**/api/requests/' + rid, r => r.fulfill({ json: d })); await page.getByRole('button', { name: '刷新', exact: true }).click(); await page.getByRole('button', { name: '查看 DEV-AP-ADAPTER', exact: true }).click(); await page.getByRole('button', { name: '处理此申请接口', exact: true }).click();
}

async function routes(page:Page){await page.route('**/adapters?*',r=>r.fulfill({json:view}));await page.route('**/adapters/*/history',r=>r.fulfill({json:[]}));}
async function prepare(page:Page){await page.getByRole('button',{name:'处理消息 '+vid,exact:true}).click();await page.getByLabel('核对接口病例UUID').fill(caseId);await page.getByLabel('接口操作原因').fill('Synthetic explicit claim');await page.getByRole('button',{name:'核对本地接口操作',exact:true}).click();}
test('adapter cancelled wait keeps exact retry key and prevents dirty switching until receipt settles',async({page})=>{
 await routes(page);const writes:{key:string|undefined;body:string|null}[]=[];let release:()=>void=()=>{};const held=new Promise<void>(r=>{release=r;});
 await page.route('**/adapters/'+vid+'/CLAIM',async r=>{writes.push({key:r.request().headers()['idempotency-key'],body:r.request().postData()});if(writes.length===1){await held;await r.fulfill({json:{receipt:{status:200,resourceType:'SYNTHETIC_ADAPTER_MESSAGE',resourceId:vid,version:1},replayed:false}});}else await r.fulfill({json:{receipt:{status:200,resourceType:'SYNTHETIC_ADAPTER_MESSAGE',resourceId:vid,version:1},replayed:true}});});
 await openAdapters(page);await prepare(page);await page.getByRole('button',{name:'返回修改接口',exact:true}).click();expect(writes).toHaveLength(0);await page.getByRole('button',{name:'核对本地接口操作',exact:true}).click();const first=page.waitForRequest(r=>r.url().endsWith('/CLAIM'));await page.getByRole('button',{name:'确认仅本地合成操作',exact:true}).click();await first;await expect.poll(()=>writes.length).toBe(1);await page.getByRole('button',{name:'取消接口等待',exact:true}).click();await expect(page.getByRole('button',{name:'原键确认接口操作',exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'新建合成接口消息',exact:true})).toBeDisabled();release();
 const replay=page.waitForResponse(r=>r.url().endsWith('/CLAIM'));await page.getByRole('button',{name:'原键确认接口操作',exact:true}).click();expect((await replay).status()).toBe(200);await expect.poll(()=>writes.length).toBe(2);expect(writes[1]).toEqual(writes[0]);await expect(page.getByRole('button',{name:'原键确认接口操作',exact:true})).toHaveCount(0);
});
test('adapter path changes discard inapplicable fields and explicit dirty confirmation preserves safe navigation',async({page})=>{
 await routes(page);await openAdapters(page);await page.getByLabel('适配器路径').selectOption('BILLING');await page.getByLabel('模拟金额最小单位（非支付）').fill('50');await page.getByLabel('适配器路径').selectOption('DEVICE');await expect(page.getByLabel('模拟金额最小单位（非支付）')).toHaveCount(0);await page.getByLabel('核对材料UUID').fill(vid);await page.getByLabel('适配器路径').selectOption('BILLING');await expect(page.getByLabel('模拟金额最小单位（非支付）')).toHaveValue('');await page.getByRole('button',{name:'处理消息 '+vid,exact:true}).click();await expect(page.getByRole('dialog',{name:'清除未保存的接口输入？'})).toBeVisible();await page.getByRole('button',{name:'继续编辑接口',exact:true}).click();await expect(page.getByLabel('适配器路径')).toHaveValue('BILLING');await page.getByRole('button',{name:'处理消息 '+vid,exact:true}).click();await page.getByRole('button',{name:'清除并切换接口',exact:true}).click();await expect(page.getByLabel('接口操作',{exact:true})).toBeVisible();await expect(page.getByRole('dialog',{name:'清除未保存的接口输入？'})).toBeHidden();await page.screenshot({path:'../docs/evidence/t39/adapter-ui.png',fullPage:true});
});
test('adapter explicit stale conflict remains an error and does not mark local acknowledgement',async({page})=>{
 await routes(page);await page.route('**/adapters/'+vid+'/CLAIM',r=>r.fulfill({status:409,json:{code:'ADAPTER_VERSION'}}));await openAdapters(page);await prepare(page);await page.getByRole('button',{name:'确认仅本地合成操作',exact:true}).click();await expect(page.getByText('消息版本已变化，请刷新核对。',{exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'原键确认接口操作',exact:true})).toHaveCount(0);await expect(page.getByRole('cell',{name:'QUEUED',exact:true})).toBeVisible();await expect(page.getByRole('cell',{name:'ACKED',exact:true})).toHaveCount(0);
});
test('late queue response cannot restore an interface after navigation away',async({page})=>{
 let release:()=>void=()=>{};const held=new Promise<void>(r=>{release=r;});let captured=false,settled=false;await page.route('**/adapters?*',async r=>{captured=true;await held;await r.fulfill({json:view});settled=true;});await openAdapters(page);await expect.poll(()=>captured).toBe(true);await page.getByRole('button',{name:'申请单查询',exact:true}).click();release();await expect.poll(()=>settled).toBe(true);await expect(page.getByRole('heading',{name:'申请单查询',exact:true})).toBeVisible();await expect(page.getByText('本地合成接口 · 非医院联通',{exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'处理消息 '+vid,exact:true})).toHaveCount(0);
});
