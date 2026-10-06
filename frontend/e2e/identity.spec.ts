import { test, expect } from '@playwright/test';
import { signInWorkflow } from './workflow-login';

test('real administrator provisions user; first login changes password; scope is automatic; revoke takes effect',async({page,browser})=>{
 await signInWorkflow(page);await page.getByRole('button',{name:'后台管理',exact:true}).click();await page.getByRole('button',{name:'添加用户',exact:true}).click();
 const username='synthetic.user.'+crypto.randomUUID();const employee='SYN-'+crypto.randomUUID();
 await page.getByLabel('登录名',{exact:true}).fill(username);await page.getByLabel('姓名',{exact:true}).fill('合成登记员');await page.getByLabel('工号',{exact:true}).fill(employee);await page.getByLabel('初始密码（首次登录必须修改）',{exact:true}).fill('Synthetic-initial-42!');
 await page.getByRole('button',{name:'添加人员工作范围',exact:true}).click();await page.getByLabel('工作范围',{exact:true}).click();await page.getByTitle('合成申请工作范围',{exact:true}).last().click();await page.getByLabel('默认工作范围',{exact:true}).click();await page.getByTitle('合成申请工作范围',{exact:true}).last().click();await page.getByLabel('变更原因',{exact:true}).fill('合成岗位入职');
 const saved=page.waitForResponse(r=>/\/admin\/[^/]+\/users$/.test(new URL(r.url()).pathname)&&r.request().method()==='POST');await page.getByRole('button',{name:'保存用户及权限',exact:true}).click();expect((await saved).status()).toBe(201);await expect(page.getByRole('cell',{name:employee,exact:true})).toBeVisible();
 const context=await browser.newContext();const staff=await context.newPage();
 try {
  await staff.goto('/');await staff.getByLabel('用户名',{exact:true}).fill(username);await staff.getByLabel('密码',{exact:true}).fill('Synthetic-initial-42!');await staff.getByRole('button',{name:'登录',exact:true}).click();await expect(staff.getByText('首次登录或密码重置 · 修改密码',{exact:true})).toBeVisible();
  expect((await staff.request.get('/api/requests/work-context')).status()).toBe(403);
  await staff.getByLabel('当前密码',{exact:true}).fill('Synthetic-initial-42!');await staff.getByLabel('新密码',{exact:true}).fill('Synthetic-new-password-42!');await staff.getByLabel('确认新密码',{exact:true}).fill('Synthetic-new-password-42!');await staff.getByRole('button',{name:'修改密码',exact:true}).click();
  await expect(staff.getByRole('heading',{name:'登录 PIS',exact:true})).toBeVisible();await staff.getByLabel('用户名',{exact:true}).fill(username);await staff.getByLabel('密码',{exact:true}).fill('Synthetic-new-password-42!');await staff.getByRole('button',{name:'登录',exact:true}).click();await staff.getByRole('button',{name:'申请登记工作区',exact:true}).click();
  await expect(staff.getByText('当前工作范围：合成申请工作范围',{exact:true})).toBeVisible();await expect(staff.getByLabel('授权工作范围')).toHaveCount(0);await expect(staff.getByRole('button',{name:'复核与模拟签署',exact:true})).toHaveCount(0);await expect(staff.getByRole('button',{name:'后台管理',exact:true})).toHaveCount(0);
  const hospitals=await (await page.request.get('/api/requests/admin/hospitals')).json() as {id:string}[];expect((await staff.request.get(`/api/requests/admin/${hospitals[0].id}/users`)).status()).toBe(403);
  const row=page.getByRole('row').filter({has:page.getByRole('cell',{name:employee,exact:true})});await row.getByRole('button',{name:'编辑权限',exact:true}).click();await page.getByRole('switch',{name:'账号启用',exact:true}).click();await page.getByLabel('变更原因',{exact:true}).fill('合成停用测试');
  await page.getByRole('button',{name:'保存用户及权限',exact:true}).click();await expect(page.getByRole('dialog')).toHaveCount(0);expect((await staff.request.get('/api/auth/me')).status()).toBe(401);
  await page.getByRole('tab',{name:'管理审计',exact:true}).click();await expect(page.getByRole('cell',{name:'合成停用测试',exact:true})).toBeVisible();await expect(page.getByRole('cell',{name:'ACCESS_DENIED',exact:true})).toBeVisible();
 } finally {await context.close();}
});
