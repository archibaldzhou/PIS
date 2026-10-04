import {describe,it,expect} from 'vitest';
import {parseImpact} from './impact-api';
const make=()=>({caseId:'case',decision:{id:'decision',resultId:'result',adoptedRevisionId:'revision'},snapshot:{decisionId:'decision',resultId:'result',epoch:'a'.repeat(64),currentRevisionId:'revision',assignmentVersion:0,reportFrozen:true,reportReady:true,validity:{epoch:'b'.repeat(64),reasons:['MODEL_VERSION_CHANGED'],dependencies:{modelStateVersion:2}}},version:-1,sourceValid:false,consumable:false,pendingReview:true,history:[],executionAllowed:false});
describe('impact history is not current consumption',()=>{
 it('keeps frozen historical identity and precise epoch',()=>{expect(parseImpact(make(),'case','decision').snapshot.reportFrozen).toBe(true);});
 for(const patch of [{caseId:'foreign'},{executionAllowed:true},{consumable:true},{version:100},{snapshot:{}}])it('rejects forged identity, capability or malformed versions '+JSON.stringify(patch),()=>{expect(()=>parseImpact({...make(),...patch},'case','decision')).toThrow();});
 it('rejects history without bounded snapshot hashes',()=>{expect(()=>parseImpact({...make(),history:[{id:'x',version:0,snapshotHash:'bad'}]},'case','decision')).toThrow();});
});
