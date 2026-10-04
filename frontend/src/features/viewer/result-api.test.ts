import {describe,it,expect} from 'vitest';
import {parseResult} from './result-api';
import type {Manifest} from './api';
import {resultFixture,resultId} from '../../../test-fixtures/result';
const m:Manifest={content:{hospitalId:'h',requestId:'r',scanId:'s',slideId:'slide',objectId:'object',objectHash:'a'.repeat(64),manifestHash:'b'.repeat(64),scanVersion:2,provider:'SYN-RGB-PYRAMID-1',width:512,height:384,tileSize:128,maxLevel:9,tiles:[]},publicationVersion:1,capability:'SYNTHETIC_RGB_ONLY',calibration:'UNAVAILABLE_NO_PHYSICAL_SCALE'};
describe('strict synthetic result boundary',()=>{
 it('retains exact identities and never grants clinical execution',()=>{const v=parseResult(resultFixture(m),m,resultId);expect(v.result.binding.input.decision.executionAllowed).toBe(false);expect(v.result.binding.width).toBe(512);expect(v.intensities).toHaveLength(16);});
 it('rejects empty unknown unready and another result/case/scan/version',()=>{for(const value of [null,{}, {...resultFixture(m),result:{...resultFixture(m).result,state:'BUILDING'}}, {...resultFixture(m),result:{...resultFixture(m).result,requestId:'other'}}])expect(()=>parseResult(value,m,resultId)).toThrow();for(const next of [{...m,publicationVersion:2},{...m,content:{...m.content,scanId:'other'}},{...m,content:{...m.content,manifestHash:'c'.repeat(64)}}])expect(()=>parseResult(resultFixture(m),next,resultId)).toThrow();expect(()=>parseResult(resultFixture(m),m,'other')).toThrow();});
 it('rejects nonfinite or excessive geometry and heatmap limits',()=>{for(const x of [NaN,Infinity,-1,513])expect(()=>parseResult({...resultFixture(m),regions:[{x,y:0,width:1,height:1}]},m,resultId)).toThrow();for(const change of [{regions:[]},{regions:Array(5).fill({x:0,y:0,width:1,height:1})},{tileCount:2},{tileWidth:65},{intensities:[]},{intensities:Array(16).fill(256)},{executionAllowed:true},{schema:'UNKNOWN'}])expect(()=>parseResult({...resultFixture(m),...change},m,resultId)).toThrow();});
});
