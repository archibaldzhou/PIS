import {test,expect,type Page} from '@playwright/test';
const rid='44444444-4444-4444-8444-444444444444',vid='55555555-5555-4555-8555-555555555555';
const scope='11111111-1111-4111-8111-111111111111';
const encounter={id:'22222222-2222-4222-8222-222222222222',patientId:'33333333-3333-4333-8333-333333333333',patientLabel:'合成患者',encounterNumber:'SYN-001'};
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
async function openOperations(page: Page) {
 await start(page); const d = { id: rid, scopeId: scope, version: 2, state: 'RECEIVED', requestNumber: 'DEV-AP-ADAPTER', patientId: encounter.patientId, patientLabel: encounter.patientLabel, encounterId: encounter.id, encounterNumber: encounter.encounterNumber, department: '合成科室', requestedAt: '2026-01-01T08:00:00Z', clinicalHistory: '合成资料', sampledAt: '2026-01-01T08:00:00Z', containers: [{ id: vid, site: 'Synthetic', laterality: 'UNKNOWN', materialQuantity: 1, fixative: 'Synthetic', fixedAt: '2026-01-01T08:10:00Z' }] };
 await page.route('**/api/requests?*', r => r.fulfill({ json: { items: [d], total: 1, page: 1, pageSize: 20 } })); await page.route('**/api/requests/' + rid, r => r.fulfill({ json: d })); await page.getByRole('button', { name: '刷新', exact: true }).click(); await page.getByRole('button', { name: '查看 DEV-AP-ADAPTER', exact: true }).click(); await page.getByRole('button', { name: '查看此申请运维', exact: true }).click();
}

const snapshot={requestId:rid,observedAt:'2026-10-04T00:00:00Z',pending:1,failed:2,readyObjects:0,capacityState:'NOT_CONFIGURED',volumeTotal:null,volumeUsable:null,encryption:'NOT_CONFIGURED',offsiteBackup:'NOT_CONFIGURED',recovery:'NOT_VERIFIED',events:[]};
test('operations shows failure and unavailable capacity without inventing recovery success',async({page})=>{
 await page.route('**/operations',r=>r.fulfill({json:snapshot}));await openOperations(page);
 await expect(page.getByText('未知 / 不可用',{exact:true})).toBeVisible();await expect(page.getByText('NOT_VERIFIED',{exact:true})).toBeVisible();await expect(page.getByText('本病例存在失败或死信，请在原接口流程人工核对',{exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'刷新运维快照',exact:true})).toBeEnabled();await page.screenshot({path:'../docs/evidence/t40/operations-ui.png',fullPage:true});
});
test('cancelled operations response cannot restore data and retry has an explicit current snapshot',async({page})=>{
 let release:()=>void=()=>{};const held=new Promise<void>(r=>{release=r;});let waiting=true;
 await page.route('**/operations',async r=>{const delayed=waiting;if(delayed)await held;await r.fulfill({json:{...snapshot,pending:delayed?99:1}});});
 const sent=page.waitForRequest(r=>r.url().endsWith('/operations'));await openOperations(page);await sent;await page.getByRole('button',{name:'取消运维等待',exact:true}).click();await expect(page.getByText('运维等待已取消，请重新读取。',{exact:true})).toBeVisible();waiting=false;release();await expect(page.getByText('NOT_VERIFIED',{exact:true})).toHaveCount(0);
 const response=page.waitForResponse(r=>r.url().endsWith('/operations'));await page.getByRole('button',{name:'刷新运维快照',exact:true}).click();expect((await response).status()).toBe(200);await expect(page.getByText('NOT_VERIFIED',{exact:true})).toBeVisible();
});
test('forbidden operations is an error and never an empty healthy dashboard',async({page})=>{
 await page.route('**/operations',r=>r.fulfill({status:403,json:{code:'ACCESS_DENIED',detail:'Synthetic denied'}}));await openOperations(page);await expect(page.getByRole('alert').last()).toBeVisible();await expect(page.getByText('NOT_VERIFIED',{exact:true})).toHaveCount(0);
});
test('leaving an in-flight operations snapshot discards the late response',async({page})=>{
 let release:()=>void=()=>{};const held=new Promise<void>(r=>{release=r;});
 await page.route('**/operations',async r=>{await held;await r.fulfill({json:snapshot});});
 const sent=page.waitForRequest(r=>r.url().endsWith('/operations'));await openOperations(page);await sent;
 await page.getByRole('button',{name:'申请单查询',exact:true}).click();release();
 await expect(page.getByRole('heading',{name:'申请单查询',exact:true})).toBeVisible();await expect(page.getByText('NOT_VERIFIED',{exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'刷新运维快照',exact:true})).toHaveCount(0);
});
