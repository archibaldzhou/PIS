// Test-only allowlist: never log arbitrary ProblemDetail strings, resource IDs, URLs or headers. Only server UUID trace IDs permit log correlation.
const codes=new Set(['CSRF_INVALID','UNAUTHENTICATED','ACCESS_DENIED','VALIDATION_FAILED','INVALID_REQUEST','VALIDATION_ERROR','INVALID_JSON','INTERNAL_ERROR','MISSING_PARAMETER','TYPE_MISMATCH','NOT_FOUND','METHOD_NOT_ALLOWED','PAYLOAD_TOO_LARGE','UNSUPPORTED_MEDIA_TYPE',
 'VIEWER_RATE','STORAGE_RATE','STORAGE_QUOTA','STORAGE_STATE','STORAGE_INTEGRITY','STORAGE_IO','STORAGE_NOT_FOUND',
 'VIEWER_BUSY','VIEWER_BINDING','AI_RESULT_INVALIDATED','AI_RESULT_NOT_FOUND','AI_RESULT_NOT_READY','AI_RESULT_EXISTS',
 'AI_RESULT_CORRUPT','AI_RESULT_BINDING','AI_RESULT_CONFLICT','AI_RESULT_GENERATOR_CHANGED','AI_RESULT_LIMIT',
 'AI_TASK_NOT_FOUND','AI_TASK_CONFLICT','AI_WORKER_DISABLED','AI_CONFLICT','AI_NOT_FOUND','DIGITAL_QC_NOT_READY',
 'IDEMPOTENCY_KEY_REUSED','COMMAND_BUSY','COMMAND_TIMEOUT']);
const record=(v:unknown):Record<string,unknown>=>v!==null&&typeof v==='object'&&!Array.isArray(v)?v as Record<string,unknown>:{};
const number=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>=0&&Number(v)<=1_000_000?v:null;
export function safeResultBody(bytes:Uint8Array){
 if(bytes.byteLength>8192)return {kind:'OVERSIZE_BODY'};
 let parsed:unknown;
 try{parsed=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));}catch{return {kind:'NON_JSON_BODY'};}
 const value=record(parsed),receipt=record(value.receipt);
 return {kind:'ALLOWLISTED_JSON',code:typeof value.code==='string'&&codes.has(value.code)?value.code:'UNRECOGNIZED_OR_ABSENT',
  traceId:typeof value.traceId==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(value.traceId)?value.traceId:null,
  status:number(value.status),replayed:typeof value.replayed==='boolean'?value.replayed:null,
  receipt:{status:number(receipt.status),version:number(receipt.version),
   resourceType:receipt.resourceType==='SYNTHETIC_AI_RESULT'?'SYNTHETIC_AI_RESULT':'UNEXPECTED_OR_ABSENT',
   validResourceId:typeof receipt.resourceId==='string'&&/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(receipt.resourceId)}};
}
export function safeTaskFacts(value:unknown){
 const v=record(value),j=record(v.job),binding=record(j.binding),decision=record(binding.decision);
 const states=new Set(['QUEUED','RUNNING','SYNTHETIC_SUCCEEDED','INVALIDATED','FAILED','CANCELLED','TIMEOUT','UNSUPPORTED']);
 return {effectiveState:typeof v.effectiveState==='string'&&states.has(v.effectiveState)?v.effectiveState:'UNKNOWN',
  taskVersion:number(j.version),generation:number(j.generation),publicationVersion:number(decision.publicationVersion),modelStateVersion:number(decision.stateVersion),
  clinicalExecutionAllowed:v.clinicalExecutionAllowed===false?false:'UNEXPECTED',executionAllowed:decision.executionAllowed===false?false:'UNEXPECTED',
  hasArtifact:typeof j.artifactId==='string',invalidated:v.invalidReason!==null};
}
export function safeUiErrors(texts:string[]){
 const messages=['读取达到开发速率或字节上限。','阅片请求达到有界配额。','合成结果回执不匹配','安全校验已失效，请重试','登录已失效，请重新登录',
  '合成结果资格或依据版本已失效，叠加不可用。','该任务已有结果，请使用原幂等请求或已知结果ID。','任务版本、租约或状态已变化，请保留原请求并重新核对。'];
 return {count:Math.min(texts.length,10),recognized:messages.filter(message=>texts.slice(0,10).some(text=>text.trim()===message)),unknownPresent:texts.slice(0,10).some(text=>!messages.includes(text.trim()))};
}
