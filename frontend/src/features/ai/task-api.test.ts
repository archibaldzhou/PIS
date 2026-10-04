import {describe,it,expect} from 'vitest';
import {parseJob,parseView} from './task-api';
const j={id:'task',requestId:'request',scanId:'scan',assessmentId:'assessment',state:'QUEUED',version:0,generation:0,progress:null,leaseId:null,artifactId:null,bindingHash:'a'.repeat(64),binding:{decision:{executionAllowed:false,requestId:'request',scanId:'scan',modelDigest:'b'.repeat(64),objectHash:'c'.repeat(64)},preprocessing:'SYN-PRE',configDigest:'d'.repeat(64)}};
describe('synthetic task boundaries',()=>{
 it('preserves unknown progress and explicit nonclinical status',()=>{expect(parseJob(j,'request').progress).toBeNull();expect(parseView({job:j,effectiveState:'QUEUED',invalidReason:null,clinicalExecutionAllowed:false,history:[]},'request','task').clinicalExecutionAllowed).toBe(false);});
 it('rejects another request, scan binding and invented successful clinical state',()=>{expect(()=>parseJob(j,'other')).toThrow();for(const patch of [{state:'DIAGNOSED'},{scanId:'other'},{binding:{...j.binding,decision:{...j.binding.decision,executionAllowed:true}}}])expect(()=>parseJob({...j,...patch},'request')).toThrow();});
 it('rejects nonfinite values, excessive attempts and forged digests',()=>{for(const patch of [{progress:NaN},{progress:101},{version:-1},{generation:4},{bindingHash:'unknown'}])expect(()=>parseJob({...j,...patch},'request')).toThrow();});
 it('rejects mismatched detail and clinical permission',()=>{const v={job:j,effectiveState:'QUEUED',invalidReason:null,clinicalExecutionAllowed:false,history:[]};expect(()=>parseView(v,'request','other')).toThrow();expect(()=>parseView({...v,clinicalExecutionAllowed:true},'request','task')).toThrow();});
});
