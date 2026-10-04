import { expect } from '@playwright/test';
import { createHash } from 'node:crypto';
import { test, forbidden } from './viewer-fixture';
import {safeResultBody,safeTaskFacts,safeUiErrors} from './result-diagnostics';
test('accepted synthetic task renders exact result pixels and model revocation clears consumption', async ({page,browser,source},info)=>{
  // CI 37173320165 reached actual pixels but exhausted 30s during toggle.
  // This scenario includes model/task/result creation and revocation; requests stay bounded.
  test.setTimeout(60_000);
  const started=Date.now();
  let previous=started;
  const phase=(name:string)=>{const now=Date.now();console.log('T36_RESULT_PHASE '+JSON.stringify({phase:name,elapsedMs:now-started,sincePreviousMs:now-previous}));previous=now;};
  phase('model-contract-start');
  const {scope,rid,headers,scanPath,scanId,viewerPath}=source;
  // T34: registry is metadata only; exact current source qualification never permits execution.
  const aiPath=`${scanPath}/${scanId}/ai`,modelsPath=`/api/requests/ai/scopes/${scope}/models`;
  const aiScanResponse=await page.request.get(`${aiPath}?publicationVersion=2`);expect(aiScanResponse.status()).toBe(200);
  const aiScan=await aiScanResponse.json() as {manifestHash:string;calibrationVersion:number|null};
  const modelMetadata={digest:'a'.repeat(64),preprocessing:'SYN-PRE-1',configSchema:'SYN-AI-CONFIG-1',maxTiles:16,inputContract:'SYN-RGB-PYRAMID-1',pathology:'SYN-PATH',stain:'SYN-STAIN',scannerCode:'SYN-DEVICE',scannerVersion:'SYN-V1',quality:'ALL_DIGITAL_QC_PASS',calibration:'PIXEL_ONLY',approvalScope:'SYNTHETIC_CONTRACT_ONLY',evidence:'DECLARED_SYNTHETIC',evidenceRef:'SYN-REFERENCE',limitations:'Synthetic contract fixture; no diagnostic performance evidence'};
  const modelResponse=await page.request.post(modelsPath,{headers:headers(),data:{modelId:crypto.randomUUID(),expectedHead:-1,code:'SYN-E2E',metadata:modelMetadata,reason:'Synthetic registry'}});expect(modelResponse.status()).toBe(200);
  const modelId=(await modelResponse.json() as {receipt:{resourceId:string}}).receipt.resourceId;
  expect((await page.request.post(`${modelsPath}/${modelId}/state`,{headers:headers(),data:{expectedVersion:0,state:'VALIDATION_ONLY',reason:'Synthetic validation only'}})).status()).toBe(200);
  expect((await page.request.post(`${aiPath}/profile`,{headers:headers(),data:{publicationVersion:2,manifestHash:aiScan.manifestHash,expectedVersion:-1,profile:{pathology:'SYN-PATH',stain:'SYN-STAIN',scannerVersion:'SYN-V1'},reason:'Synthetic source metadata'}})).status()).toBe(200);
  const aiHeaders=headers(),aiInput={modelVersionId:modelId,stateVersion:1,profileVersion:0,publicationVersion:2,manifestHash:aiScan.manifestHash,calibrationVersion:aiScan.calibrationVersion,reason:'Synthetic exact qualification'};
  const aiResult=await page.request.post(`${aiPath}/assess`,{headers:aiHeaders,data:aiInput});expect(aiResult.status(),await aiResult.text()).toBe(200);
  const firstReceipt=await aiResult.json() as {receipt:{resourceId:string;resourceType:string;version:number;status:number};replayed:boolean};
  expect(firstReceipt.replayed).toBe(false);expect(firstReceipt.receipt).toMatchObject({resourceType:'AI_ASSESSMENT',version:0,status:200});const aiId=firstReceipt.receipt.resourceId;
  const decision=await page.request.get(`${aiPath}/assess/${aiId}`);expect(decision.status()).toBe(200);const firstDecision=await decision.json() as Record<string,unknown>;
  expect(firstDecision).toMatchObject({outcome:'VALIDATION_ONLY_APPLICABLE',executionAllowed:false,modelVersionId:modelId,manifestHash:aiScan.manifestHash});
  // T07's required replay signal is in Result; the HTTP header is optional.
  const replay=await page.request.post(`${aiPath}/assess`,{headers:aiHeaders,data:aiInput});expect(replay.status(),await replay.text()).toBe(200);
  expect(await replay.json()).toEqual({...firstReceipt,replayed:true});
  const reread=await page.request.get(`${aiPath}/assess/${aiId}`);expect(reread.status()).toBe(200);const repeatedDecision=await reread.json() as Record<string,unknown>;
  expect(repeatedDecision).toEqual(firstDecision);expect(createHash('sha256').update(JSON.stringify(repeatedDecision)).digest('hex')).toBe(createHash('sha256').update(JSON.stringify(firstDecision)).digest('hex'));
  const changed=await page.request.post(`${aiPath}/assess`,{headers:aiHeaders,data:{...aiInput,reason:'Synthetic different intent'}});
  expect(changed.status(),await changed.text()).toBe(409);expect(await changed.json()).toMatchObject({code:'IDEMPOTENCY_KEY_REUSED'});
  await page.getByRole('button',{name:'核对此扫描AI适用契约',exact:true}).click();await page.getByRole('button',{name:`选择模型 ${modelId}`,exact:true}).click();await page.getByRole('button',{name:'核对当前扫描适用资料',exact:true}).click();await page.getByLabel('AI操作原因',{exact:true}).fill('Synthetic browser assessment');await page.getByRole('button',{name:'判定合成适用性（不执行）',exact:true}).click();await expect(page.getByRole('region',{name:'AI模型契约管理'}).getByRole('status')).toContainText('VALIDATION_ONLY_APPLICABLE');
  await page.screenshot({path:info.outputPath('real-service-synthetic-ai-contract.png'),fullPage:true});
  phase('model-contract-complete');
  // T35: real PostgreSQL task/outbox, bounded local fixture and immutable non-diagnostic artifact.
  await page.getByRole('button',{name:'进入合成契约任务（非临床）',exact:true}).click();
  await page.getByLabel('合成任务操作原因',{exact:true}).fill('Synthetic technical task');
  await page.getByRole('button',{name:'创建合成契约任务',exact:true}).click();
  await expect(page.getByRole('region',{name:'合成契约任务'}).getByRole('status')).toContainText('QUEUED');
  await expect(page.getByRole('button',{name:'原键确认任务操作',exact:true})).toBeDisabled();
  await page.getByLabel('合成任务操作原因',{exact:true}).fill('Synthetic claim');await page.getByRole('button',{name:'领取合成任务',exact:true}).click();
  await expect(page.getByRole('region',{name:'合成契约任务'}).getByRole('status')).toContainText('RUNNING');await expect(page.getByRole('button',{name:'原键确认任务操作',exact:true})).toBeDisabled();
  await page.getByLabel('合成任务操作原因',{exact:true}).fill('Synthetic local fixture');await page.getByRole('button',{name:'运行有界非诊断夹具',exact:true}).click();
  await expect(page.getByRole('region',{name:'合成契约任务'}).getByRole('status')).toContainText('SYNTHETIC_SUCCEEDED');await expect(page.getByRole('button',{name:'原键确认任务操作',exact:true})).toBeDisabled();
  const taskState=JSON.parse(await page.getByLabel('任务精确版本',{exact:true}).innerText()) as {job:{id:string;artifactId:string};clinicalExecutionAllowed:boolean};expect(taskState.clinicalExecutionAllowed).toBe(false);
  const taskPath=`/api/requests/${rid}/synthetic-tasks/${taskState.job.id}`;
  const artifact=await page.request.post(`${taskPath}/artifact`,{headers:headers()});expect(artifact.status(),await artifact.text()).toBe(200);expect(await artifact.text()).toContain('NON_DIAGNOSTIC_SYNTHETIC_CONTRACT_ONLY');
  const again=await page.request.post(`${taskPath}/artifact`,{headers:headers()});expect(again.status()).toBe(200);expect(await again.body()).toEqual(await artifact.body());
  await page.screenshot({path:info.outputPath('real-service-synthetic-worker.png'),fullPage:true});
  phase('accepted-task-complete');
  // T36: actual immutable package and PNG from the accepted T35 artifact, over real OSD.
  await page.getByLabel('合成任务操作原因',{exact:true}).fill('Synthetic non diagnostic visual fixture');
  const taskFacts=safeTaskFacts(JSON.parse(await page.getByLabel('任务精确版本',{exact:true}).innerText()) as unknown);
  expect(taskFacts).toMatchObject({effectiveState:'SYNTHETIC_SUCCEEDED',taskVersion:2,generation:1,publicationVersion:2,modelStateVersion:1,clinicalExecutionAllowed:false,executionAllowed:false,hasArtifact:true,invalidated:false});
  const createPath=`/api/requests/${rid}/synthetic-results`;
  const generated=page.waitForResponse(r=>new URL(r.url()).pathname===createPath&&r.request().method()==='POST',{timeout:10_000});
  let httpStatus:number|null=null,bodySummary:ReturnType<typeof safeResultBody>|null=null;
  try {
    await page.getByRole('button',{name:'生成非诊断合成视觉结果',exact:true}).click();
    const response=await generated;httpStatus=response.status();bodySummary=safeResultBody(await response.body());
    const submitted=response.request().postDataJSON() as {taskId:string;expectedTaskVersion:number;reason:string};
    expect(submitted).toEqual({taskId:taskState.job.id,expectedTaskVersion:taskFacts.taskVersion,reason:'Synthetic non diagnostic visual fixture'});
    if(!response.ok())await expect(page.getByRole('button',{name:'原键确认任务操作',exact:true})).toBeEnabled();
    expect(httpStatus,JSON.stringify(bodySummary)).toBe(200);
    expect(bodySummary).toMatchObject({replayed:false,receipt:{status:200,version:0,resourceType:'SYNTHETIC_AI_RESULT',validResourceId:true}});
    await expect(page.getByLabel('已确认合成结果ID',{exact:true})).toBeVisible();
  } finally {
    const diagnostic={httpStatus,body:bodySummary,lastConfirmedTask:taskFacts,ui:safeUiErrors(await page.getByRole('region',{name:'合成契约任务'}).getByRole('alert').allTextContents())};
    console.log('T36_RESULT_CREATE_DIAGNOSTIC '+JSON.stringify(diagnostic));
    await info.attach('synthetic-result-create-diagnostic',{body:JSON.stringify(diagnostic),contentType:'application/json'});
  }
  phase('result-create-complete');
  const resultCode=page.getByLabel('已确认合成结果ID',{exact:true});await expect(resultCode).toBeVisible();const visualId=await resultCode.innerText();
  const visualPath=`/api/requests/${rid}/synthetic-results/${visualId}`;
  const metadata=await page.request.get(visualPath);expect(metadata.status(),await metadata.text()).toBe(200);
  const visual=await metadata.json() as {epoch:string;tileHash:string;intensities:number[];result:{id:string;taskId:string;binding:{input:{decision:{modelDigest:string;manifestHash:string}}}};executionAllowed:boolean};
  expect(visual.executionAllowed).toBe(false);expect(visual.result.id).toBe(visualId);expect(visual.result.taskId).toBe(taskState.job.id);expect(visual.result.binding.input.decision.manifestHash).toBe(aiScan.manifestHash);
  const binary=await page.request.get(`${visualPath}/tiles/0?epoch=${visual.epoch}`);expect(binary.status()).toBe(200);expect(binary.headers()['cache-control']).toContain('private');expect(createHash('sha256').update(await binary.body()).digest('hex')).toBe(visual.tileHash);expect((await binary.body()).subarray(0,8)).toEqual(Buffer.from([137,80,78,71,13,10,26,10]));
  await page.getByRole('button',{name:'返回当前扫描AI契约',exact:true}).click();const manifestResponse=page.waitForResponse(r=>new URL(r.url()).pathname===viewerPath&&r.request().method()==='GET',{timeout:10_000});
  await page.getByRole('button',{name:'返回当前扫描阅片',exact:true}).click();
  expect((await manifestResponse).status(),'returned viewer manifest').toBe(200);
  await expect(page.getByRole('status').filter({hasText:'真实合成PNG已加载'})).toBeVisible();
  await expect.poll(async()=>source.canvas.evaluate(node=>{const c=node as HTMLCanvasElement;const ctx=c.getContext('2d');if(!ctx)return 0;const p=ctx.getImageData(0,0,c.width,c.height).data;let pink=0;for(let i=0;i<p.length;i+=16)if(p[i]>140&&p[i+2]>120&&p[i]>p[i+1]+15)pink++;return pink;})).toBeGreaterThan(500);
  phase('returned-viewer-ready');
  await page.getByLabel('精确合成结果ID',{exact:true}).fill(visualId);const browserMetadata=page.waitForResponse(r=>new URL(r.url()).pathname===visualPath,{timeout:10_000});
  const browserTile=page.waitForResponse(r=>new URL(r.url()).pathname===`${visualPath}/tiles/0`,{timeout:10_000});
  await page.getByRole('button',{name:'核验并加载合成叠加',exact:true}).click();
  await Promise.all([
    browserMetadata.then(async delivered=>{expect(delivered.status(),'browser result metadata').toBe(200);expect(await delivered.json()).toEqual(visual);}),
    browserTile.then(async delivered=>{expect(delivered.status(),'browser result PNG').toBe(200);expect(createHash('sha256').update(await delivered.body()).digest('hex')).toBe(visual.tileHash);}),
  ]);
  const overlayPanel=page.getByRole('region',{name:'非诊断合成结果叠加'});
  await expect(overlayPanel.getByRole('alert').filter({hasText:/损坏|失效|失败/})).toHaveCount(0);
  await expect(overlayPanel.getByRole('status')).toContainText('合成强度叠加已核验');
  const resultLayer=page.getByLabel('实际合成强度图层',{exact:true});await expect(resultLayer).toBeVisible();
  const readResultPixel=()=>resultLayer.locator('image').evaluate(async node=>{const image=new Image();image.src=(node as SVGImageElement).href.baseVal;await image.decode();const canvas=document.createElement('canvas');canvas.width=64;canvas.height=64;const context=canvas.getContext('2d');if(!context)throw Error('Missing canvas');context.drawImage(image,0,0);return Array.from(context.getImageData(8,8,1,1).data);});
  expect(await test.step('decode first accepted PNG pixels',readResultPixel,{timeout:5000})).toEqual([visual.intensities[0],255-visual.intensities[0],64,255]);
  phase('first-real-pixels-complete');
  await page.getByLabel('合成瓦片画布',{exact:true}).scrollIntoViewIfNeeded();await page.screenshot({path:info.outputPath('real-service-synthetic-result-overlay.png'),fullPage:true});
  phase('toggle-start');
  await page.getByRole('button',{name:'隐藏合成叠加',exact:true}).click();await expect(resultLayer).toHaveCount(0);
  const shownMetadata=page.waitForResponse(r=>new URL(r.url()).pathname===visualPath,{timeout:10_000});
  const shownTile=page.waitForResponse(r=>new URL(r.url()).pathname===`${visualPath}/tiles/0`,{timeout:10_000});
  let metadataStatus:number|null=null,tileStatus:number|null=null;
  try {
    await page.getByRole('button',{name:'显示合成叠加',exact:true}).click();
    await Promise.all([
      shownMetadata.then(async r=>{metadataStatus=r.status();phase('toggle-metadata-response');expect(metadataStatus,JSON.stringify(safeResultBody(await r.body()))).toBe(200);expect(await r.json()).toEqual(visual);}),
      shownTile.then(async r=>{tileStatus=r.status();phase('toggle-png-response');expect(tileStatus).toBe(200);expect(createHash('sha256').update(await r.body()).digest('hex')).toBe(visual.tileHash);}),
    ]);
    await expect(overlayPanel.getByRole('status')).toContainText('合成强度叠加已核验',{timeout:5000});
    await expect(resultLayer).toBeVisible({timeout:5000});
    expect(await test.step('decode newly authorized toggle PNG pixels',readResultPixel,{timeout:5000})).toEqual([visual.intensities[0],255-visual.intensities[0],64,255]);
    phase('toggle-real-pixels-complete');
  } finally {
    console.log('T36_RESULT_TOGGLE_DIAGNOSTIC '+JSON.stringify({elapsedMs:Date.now()-started,metadataStatus,tileStatus,layerCount:await resultLayer.count(),ui:safeUiErrors(await overlayPanel.getByRole('alert').allTextContents())}));
  }
  phase('model-revocation-start');
  expect((await page.request.post(`${modelsPath}/${modelId}/state`,{headers:headers(),data:{expectedVersion:1,state:'DISABLED',reason:'Synthetic recall'}})).status()).toBe(200);
  await expect(page.getByRole('alert').filter({hasText:'合成结果资格或依据版本已失效'})).toBeVisible({timeout:7000});await expect(resultLayer).toHaveCount(0);
  expect((await page.request.get(visualPath)).status()).toBe(409);expect((await page.request.get(`${visualPath}/tiles/0?epoch=${visual.epoch}`)).status()).toBe(409);
  expect((await page.request.get(`${aiPath}/assess/${aiId}`)).status()).toBe(409);expect((await page.request.post(`${aiPath}/assess`,{headers:aiHeaders,data:aiInput})).status()).toBe(409);
  expect((await page.request.post(`${taskPath}/artifact`,{headers:headers()})).status()).toBe(409);
  await expect(page.getByLabel('合成瓦片画布',{exact:true}).locator('canvas').first()).toBeVisible();
  phase('model-revocation-complete');
  await forbidden(browser,[visualPath,`${visualPath}/tiles/0?epoch=${visual.epoch}`,modelsPath]);
  phase('cross-role-rejection-complete');
  const anonymous=await browser.newContext({baseURL:'http://127.0.0.1:5173'});
  try {
    for(const path of [visualPath,`${visualPath}/tiles/0?epoch=${visual.epoch}`])expect((await anonymous.request.get(path,{timeout:10_000})).status()).toBe(401);
  } finally {await anonymous.close();}
  phase('anonymous-rejection-complete');
});
