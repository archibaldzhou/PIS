import {describe,it,expect} from 'vitest';
import {parseView} from './decision-api';
const id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',result='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
export const decisionFixture=()=>({caseId:id,resultId:result,version:-1,assignmentVersion:0,page:1,executionAllowed:false,ready:true,invalidReason:null,target:{id:'revision',caseId:id,version:0,templateCode:'SYN-REPORT',templateVersion:1,assignmentVersion:0,fields:{gross:'',microscopy:'',diagnosis:'Synthetic manual',notes:''},authorId:'synthetic',reason:'Synthetic',createdAt:'2026-01-01T00:00:00Z'},source:{id:result,artifactHash:'a'.repeat(64),binding:{resultId:result,input:{caseId:id,preprocessing:'SYN-PRE-1',configDigest:'b'.repeat(64),decision:{executionAllowed:false,modelDigest:'c'.repeat(64)}}}},history:[]});
describe('synthetic human decision bindings',()=>{
 it('accepts exact nonclinical source and target',()=>expect(parseView(decisionFixture(),id,result,1).target?.fields.diagnosis).toBe('Synthetic manual'));
 it.each(['case','result','execution','hash','target','page','unknown'] as const)('rejects %s mismatch',kind=>{const v=decisionFixture();if(kind==='case')v.caseId='other';if(kind==='result')v.source.id='other';if(kind==='execution')v.executionAllowed=true;if(kind==='hash')v.source.artifactHash='invalid';if(kind==='target')v.target.caseId='other';if(kind==='page')v.page=2;if(kind==='unknown')v.source.binding.input.decision.executionAllowed=true;expect(()=>parseView(v,id,result,1)).toThrow();});
 it('retains unavailable history without falsely claiming ready',()=>{const v={...decisionFixture(),ready:false,invalidReason:'RESULT_DEPENDENCY_CHANGED'};expect(parseView(v,id,result,1).ready).toBe(false);});
});
