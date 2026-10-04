import {test,expect} from '@playwright/test';
test('built assets render a real deep-link login and reload without a Vite server',async({page})=>{
 await page.route('**/api/auth/me',r=>r.fulfill({status:401,json:{code:'UNAUTHENTICATED'}}));
 await page.route('**/api/auth/csrf',r=>r.fulfill({json:{headerName:'X-CSRF-TOKEN',token:'synthetic-only'}}));
 const assets:string[]=[];page.on('response',r=>{if(r.url().endsWith('.js'))assets.push(r.url());});
 await page.goto('/engineering/deep/link');await expect(page.getByRole('heading',{name:'登录 PIS',exact:true})).toBeVisible();await page.reload();await expect(page.getByLabel('用户名',{exact:true})).toBeVisible();expect(assets.some(p=>p.includes('/assets/index-'))).toBe(true);expect(assets.some(p=>p.includes('@vite')||p.includes('/src/'))).toBe(false);
 await page.screenshot({path:'../docs/evidence/t41/dist-login.png',fullPage:true});
});
test('built app keeps unknown backend failure as an error, never success or empty data',async({page})=>{
 await page.route('**/api/auth/me',r=>r.fulfill({json:{id:'synthetic',username:'synthetic',displayName:'合成构建演练'}}));
 await page.route('**/api/hello',r=>r.fulfill({status:502,json:{code:'UPSTREAM_UNAVAILABLE'}}));
 await page.goto('/engineering/deep/link');await expect(page.getByText('连接失败',{exact:true})).toBeVisible();await expect(page.getByText('应用：PIS · API 连接成功',{exact:true})).toHaveCount(0);await page.screenshot({path:'../docs/evidence/t41/dist-error.png',fullPage:true});
});
