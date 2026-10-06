import { test, expect, type Page } from '@playwright/test';
const scope='11111111-1111-4111-8111-111111111111';
const hospital='22222222-2222-4222-8222-222222222222';
async function start(page: Page) {
 await page.route('**/api/auth/me',r=>r.fulfill({json:{id:'admin',username:'synthetic.admin',displayName:'合成管理员',administration:true,passwordChangeRequired:false}}));
 await page.route('**/api/auth/csrf',r=>r.fulfill({json:{headerName:'X-CSRF-TOKEN',token:'synthetic-only'}}));
 await page.route('**/api/hello',r=>r.fulfill({json:{message:'Hello',application:'PIS'}}));
 await page.route('**/admin/hospitals',r=>r.fulfill({json:[{id:hospital,name:'合成三甲医院',manage:true}]}));
 await page.route('**/admin/*/catalog',r=>r.fulfill({json:{roles:[{code:'RECEPTION',name:'登记接诊员',permissions:['READ','WRITE'],available:true},{code:'REVIEWER',name:'复核医师',permissions:['READ','DIAGNOSE','SIGN'],available:true}],permissions:[{code:'READ',name:'申请查询',professional:false,menu:'requests'},{code:'WRITE',name:'申请登记',professional:false,menu:'registration'},{code:'DIAGNOSE',name:'诊断',professional:true,menu:'diagnosis'},{code:'SIGN',name:'模拟签署',professional:true,menu:'review'}],campuses:[],departments:[],sources:[],scopes:[{id:scope,name:'本院常规病理',campusId:'campus',departmentId:'department',sourceId:'source',enabled:true,version:0}]}}));
 await page.route('**/admin/*/users?*',r=>r.fulfill({json:{items:[],total:0,page:1}}));
 await page.route('**/admin/*/audit?*',r=>r.fulfill({json:[]}));
 await page.goto('/');await page.getByRole('button',{name:'后台管理',exact:true}).click();await page.getByRole('button',{name:'添加用户',exact:true}).click();
}
async function fill(page: Page) {
 await page.getByLabel('登录名',{exact:true}).fill('synthetic.reception');await page.getByLabel('姓名',{exact:true}).fill('合成登记员');await page.getByLabel('工号',{exact:true}).fill('SYN-001');await page.getByLabel('初始密码（首次登录必须修改）',{exact:true}).fill('Synthetic-ui-only-42!');
 await page.getByRole('button',{name:'添加人员工作范围',exact:true}).click();
 await page.getByLabel('工作范围',{exact:true}).click();await page.getByTitle('本院常规病理',{exact:true}).last().click();
 await page.getByLabel('默认工作范围',{exact:true}).click();await page.getByTitle('本院常规病理',{exact:true}).last().click();await page.getByLabel('变更原因',{exact:true}).fill('合成岗位配置');
}
test('administration creates typed role/scope configuration once and clears secrets on reopen',async({page})=>{
 await start(page);await fill(page);let writes=0;
 await page.route('**/admin/*/users',async r=>{writes++;expect(r.request().postDataJSON()).toMatchObject({username:'synthetic.reception',defaultScopeId:scope,assignments:[{scopeId:scope,permissions:['READ','WRITE'],qualificationVerified:false}]});await r.fulfill({status:201,json:{receipt:{resourceId:'new-user',version:0}}});});
 await page.getByRole('button',{name:'保存用户及权限',exact:true}).dblclick();await expect(page.getByText('服务器已保存配置。账号权限变更后需重新登录。')).toBeVisible();expect(writes).toBe(1);
 await page.getByRole('button',{name:'添加用户',exact:true}).click();await expect(page.getByLabel('登录名',{exact:true})).toHaveValue('');await expect(page.getByLabel('初始密码（首次登录必须修改）',{exact:true})).toHaveValue('');
 await page.screenshot({path:'test-results/identity-admin.png',fullPage:true});
});
test('unknown administration result freezes fields and reuses exact request identity',async({page})=>{
 await start(page);await fill(page);const writes: {body: string|null;key: string|undefined}[]=[];
 await page.route('**/admin/*/users',async r=>{writes.push({body:r.request().postData(),key:r.request().headers()['idempotency-key']});await r.abort();});
 await page.getByRole('button',{name:'保存用户及权限',exact:true}).click();await expect(page.getByRole('button',{name:'确认原请求结果'})).toBeVisible();await expect(page.getByLabel('登录名',{exact:true})).toBeDisabled();await page.getByRole('button',{name:'确认原请求结果'}).click();await expect.poll(()=>writes.length).toBe(2);expect(writes[1]).toEqual(writes[0]);
});
test('server default scope is automatic and unauthorized menu is absent',async({page})=>{
 await page.route('**/api/auth/me',r=>r.fulfill({json:{id:'reader',username:'reader',displayName:'合成查询员',administration:false}}));await page.route('**/api/hello',r=>r.fulfill({json:{message:'Hello',application:'PIS'}}));
 await page.route('**/requests/work-context',r=>r.fulfill({json:{defaultScopeId:scope,writableScopes:[],name:'本院常规病理',scopes:[{id:scope,name:'本院常规病理'}],menus:['requests','registration'],administration:false}}));
 await page.route('**/api/requests?*',r=>{expect(new URL(r.request().url()).searchParams.has('scopeId')).toBe(false);return r.fulfill({json:{items:[],total:0,page:1,pageSize:20}});});
 await page.goto('/');await page.getByRole('button',{name:'申请登记工作区'}).click();await expect(page.getByText('当前工作范围：本院常规病理')).toBeVisible();await expect(page.getByLabel('授权工作范围')).toHaveCount(0);await expect(page.getByRole('button',{name:'复核与模拟签署',exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'后台管理',exact:true})).toHaveCount(0);
});
