import {test,expect,type Page} from '@playwright/test';
import {render} from './viewer-fixture';
const rid='44444444-4444-4444-8444-444444444444';
const job={id:'task-a',requestId:rid,scanId:'scan-qc',assessmentId:'assessment-a',state:'QUEUED',version:0,generation:0,progress:null,leaseId:null,artifactId:null,bindingHash:'a'.repeat(64),binding:{decision:{executionAllowed:false,requestId:rid,scanId:'scan-qc',modelDigest:'b'.repeat(64),objectHash:'c'.repeat(64)},preprocessing:'SYN-PRE',configDigest:'d'.repeat(64)}};
const detail={job,effectiveState:'QUEUED',invalidReason:null,clinicalExecutionAllowed:false,history:[]};
async function start(page:Page){await page.route('**/synthetic-tasks?*',r=>r.fulfill({json:{requestId:rid,scanId:'scan-qc',page:1,jobs:[job,{...job,id:'task-b'}],capability:'SYNTHETIC_CONTRACT_ONLY_NO_CLINICAL_EXECUTION'}}));await page.route('**/synthetic-tasks/task-*',r=>r.fulfill({json:{...detail,job:{...job,id:r.request().url().endsWith('task-b')?'task-b':'task-a'}}}));await render(page);await page.getByRole('button',{name:'此扫描合成任务队列',exact:true}).click();await page.getByRole('button',{name:'刷新合成任务',exact:true}).click();await page.getByRole('button',{name:'核验任务 task-a',exact:true}).click();await expect(page.getByRole('region',{name:'合成契约任务'}).getByRole('status')).toContainText('未知');}
test('synthetic queue has unknown progress, explicit dirty cancellation and independent task identity',async({page},info)=>{await start(page);await page.getByLabel('合成任务操作原因',{exact:true}).fill('Unsaved synthetic reason');await page.getByRole('button',{name:'核验任务 task-b',exact:true}).click();await expect(page.getByRole('dialog')).toBeVisible();await page.getByRole('button',{name:'保留任务表单',exact:true}).click();await expect(page.getByLabel('合成任务操作原因',{exact:true})).toHaveValue('Unsaved synthetic reason');await page.getByRole('button',{name:'核验任务 task-b',exact:true}).click();await page.getByRole('button',{name:'清除并重新核对',exact:true}).click();await expect(page.getByLabel('任务精确版本',{exact:true})).toContainText('task-b');await expect(page.getByLabel('合成任务操作原因',{exact:true})).toHaveValue('');await expect(page.getByRole('dialog')).toBeHidden();await page.screenshot({path:info.outputPath('synthetic-task-queue.png'),fullPage:true});});
function signal(){let resolve:()=>void=()=>{throw Error('Gate not initialized');};const promise=new Promise<void>(r=>{resolve=r;});return {promise,resolve};}
test('delayed cancelled wait cannot write into another task; unresolved command retains exact retry key', async ({page}) => {
 await start(page);
 const firstGate=signal(), firstCaptured=signal(), firstFinished=signal();
 const retryGate=signal(), retryCaptured=signal();
 const csrfGate=signal(), csrfCaptured=signal();
 const received:{body:unknown;key:string}[]=[];
 const applied=new Map<string,unknown>();
 let executions=0;
 await page.route('**/synthetic-tasks/task-a/actions/CLAIM',async route=>{
  const captured={body:route.request().postDataJSON() as unknown,key:route.request().headers()['idempotency-key']};
  expect(captured.key).toBeTruthy();
  received.push(captured);
  const replayed=applied.has(captured.key);
  if(replayed) expect(captured.body).toEqual(applied.get(captured.key));
  else {applied.set(captured.key,captured.body);executions++;}
  const first=received.length===1;
  if(first){firstCaptured.resolve();await firstGate.promise;}
  else {retryCaptured.resolve();await retryGate.promise;}
  await route.fulfill({json:{receipt:{status:200,resourceType:'SYNTHETIC_AI_TASK',resourceId:'task-a',version:1},replayed}});
  if(first)firstFinished.resolve();
 });
 const retry=page.getByRole('button',{name:'原键确认任务操作',exact:true});
 const stop=page.getByRole('button',{name:'停止等待任务请求',exact:true});
 const taskB=page.getByRole('button',{name:'核验任务 task-b',exact:true});
 const status=page.getByRole('region',{name:'合成契约任务'}).getByRole('status');
 await page.getByLabel('合成任务操作原因',{exact:true}).fill('Synthetic claim');
 await page.getByRole('button',{name:'领取合成任务',exact:true}).click();
 await firstCaptured.promise;
 expect(received).toHaveLength(1);
 expect(received[0].body).toEqual({expectedVersion:0,generation:0,leaseId:null,reason:'Synthetic claim'});
 await expect(taskB).toBeDisabled();
 const aborted=page.waitForEvent('requestfailed',r=>r.url().endsWith('/synthetic-tasks/task-a/actions/CLAIM'));
 await stop.click();
 await aborted;
 await expect(retry).toBeEnabled();
 await expect(taskB).toBeDisabled();
 // Busy is rendered before fetchCsrf completes. Hold that actual request to prove
 // that a disabled retry button is not evidence that the command was captured.
 await page.route('**/api/auth/csrf',async route=>{
  csrfCaptured.resolve();await csrfGate.promise;
  await route.fulfill({json:{headerName:'X-CSRF-TOKEN',token:'synthetic-only'}});
 });
 await page.route('**/synthetic-tasks/task-a',route=>route.fulfill({json:{...detail,job:{...job,state:'RUNNING',version:1,generation:1,leaseId:'lease'},effectiveState:'RUNNING'}}));
 await retry.click();
 await csrfCaptured.promise;
 await expect(retry).toBeDisabled();
 expect(received).toHaveLength(1);
 csrfGate.resolve();
 await retryCaptured.promise;
 expect(received).toHaveLength(2);
 expect(received[1]).toEqual(received[0]);
 expect(executions).toBe(1);
 await expect(taskB).toBeDisabled();
 await expect(status).toContainText('QUEUED');
 const response=page.waitForResponse(r=>r.url().endsWith('/synthetic-tasks/task-a/actions/CLAIM'));
 retryGate.resolve();
 const confirmed=await response;
 expect(confirmed.status()).toBe(200);
 expect(await confirmed.json()).toEqual({receipt:{status:200,resourceType:'SYNTHETIC_AI_TASK',resourceId:'task-a',version:1},replayed:true});
 await expect(status).toContainText('RUNNING');
 await expect(stop).toBeDisabled();
 await expect(retry).toBeDisabled();
 await expect(taskB).toBeEnabled();
 await expect(page.getByLabel('合成任务操作原因',{exact:true})).toHaveValue('');
 await taskB.click();
 await expect(page.getByLabel('任务精确版本',{exact:true})).toContainText('task-b');
 await expect(status).toContainText('QUEUED');
 // Deliver the original response only after a different task has been selected.
 firstGate.resolve();await firstFinished.promise;
 await expect(page.getByLabel('任务精确版本',{exact:true})).toContainText('task-b');
 await expect(status).toContainText('QUEUED');
 expect(received).toHaveLength(2);expect(executions).toBe(1);
});
test('revoked task and failed requests are errors, never empty success or zero progress',async({page})=>{await start(page);await page.route('**/synthetic-tasks/task-b',r=>r.fulfill({status:404,json:{code:'AI_TASK_NOT_FOUND'}}));await page.getByRole('button',{name:'核验任务 task-b',exact:true}).click();await expect(page.getByRole('alert').filter({hasText:'合成任务或当前资格不可用'})).toBeVisible();await expect(page.getByLabel('任务精确版本',{exact:true})).toHaveCount(0);});
test('technical artifact is explicitly fetched and revoked reads clear previously shown content',async({page})=>{await start(page);await page.route('**/synthetic-tasks/task-b',r=>r.fulfill({json:{...detail,job:{...job,id:'task-b',state:'SYNTHETIC_SUCCEEDED',version:2,generation:1,progress:100,leaseId:'lease',artifactId:'artifact'},effectiveState:'SYNTHETIC_SUCCEEDED'}}));await page.getByRole('button',{name:'核验任务 task-b',exact:true}).click();await page.route('**/synthetic-tasks/task-b/artifact',r=>r.fulfill({contentType:'application/octet-stream',body:'PIS-SYNTHETIC-STORAGE-V1\nNON_DIAGNOSTIC_SYNTHETIC_CONTRACT_ONLY\nSYN-CONTRACT-WORKER-1\n'}));await expect(page.getByLabel('合成技术产物',{exact:true})).toHaveCount(0);await page.getByRole('button',{name:'读取非诊断技术产物',exact:true}).click();await expect(page.getByLabel('合成技术产物',{exact:true})).toContainText('NON_DIAGNOSTIC');await page.route('**/synthetic-tasks/task-b/artifact',r=>r.fulfill({status:409,json:{code:'AI_CONFLICT'}}));await page.getByRole('button',{name:'读取非诊断技术产物',exact:true}).click();await expect(page.getByRole('alert').filter({hasText:'AI依据版本已变化'})).toBeVisible();await expect(page.getByLabel('合成技术产物',{exact:true})).toHaveCount(0);});
