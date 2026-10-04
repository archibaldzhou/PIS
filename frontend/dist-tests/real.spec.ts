import {test,expect} from '@playwright/test';
test('actual dist deep link refresh preserves real cookie and enforces CSRF through loopback proxy',async({page,context})=>{
 const loaded:string[]=[];page.on('response',r=>{if(new URL(r.url()).pathname.endsWith('.js'))loaded.push(new URL(r.url()).pathname);});
 await page.goto('/engineering/deep/link');await expect(page.getByRole('heading',{name:'登录 PIS',exact:true})).toBeVisible();expect(loaded.some(p=>p.startsWith('/assets/'))).toBe(true);expect(loaded.some(p=>p.includes('/src/')||p.includes('@vite'))).toBe(false);
 expect((await page.request.post('/api/auth/login',{form:{username:'synthetic.reader',password:'Synthetic-test-only-42!'}})).status()).toBe(403);
 await page.getByLabel('用户名',{exact:true}).fill('synthetic.reader');await page.getByLabel('密码',{exact:true}).fill('Synthetic-test-only-42!');await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page.getByText('应用：PIS · API 连接成功',{exact:true})).toBeVisible();
 const cookie=(await context.cookies()).find(c=>c.name==='PIS_SESSION');expect(cookie?.httpOnly).toBe(true);expect(cookie?.sameSite).toBe('Lax');expect(cookie?.path).toBe('/');
 await page.reload();await expect(page.getByText('应用：PIS · API 连接成功',{exact:true})).toBeVisible();
 const forbidden=await page.request.get('/api/unknown-operation');expect(forbidden.status()).toBe(403);expect(forbidden.headers()['content-type']).toContain('application/problem+json');
 expect((await page.request.get('/assets/not-a-build.js')).status()).toBe(404);expect((await page.request.get('/actuator/env')).status()).toBe(404);
 expect((await page.request.post('/api/auth/logout')).status()).toBe(403);expect((await page.request.get('/api/auth/me')).status()).toBe(200);
 await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('heading',{name:'登录 PIS',exact:true})).toBeVisible();expect((await page.request.get('/api/auth/me')).status()).toBe(401);
 await page.screenshot({path:'../docs/evidence/t41/dist-real-service.png',fullPage:true});
});
