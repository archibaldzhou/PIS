import {describe,it,expect} from 'vitest';
import {parseSnapshot} from './api';
const view={requestId:'synthetic',observedAt:'2026-10-04T00:00:00Z',pending:0,failed:0,readyObjects:0,capacityState:'NOT_CONFIGURED',volumeTotal:null,volumeUsable:null,encryption:'NOT_CONFIGURED',offsiteBackup:'NOT_CONFIGURED',recovery:'NOT_VERIFIED',events:[]};
describe('restricted operations contract',()=>{
 it('keeps unknown distinct from true zero',()=>{expect(parseSnapshot(view,'synthetic')).toMatchObject({pending:0,volumeUsable:null});});
 it('rejects cross resource, fabricated recovery, count and capacity',()=>{for(const patch of [{requestId:'other'},{recovery:'PASS'},{pending:-1},{volumeUsable:0},{capacityState:'HEALTHY'},{events:Array(21).fill({})}])expect(()=>parseSnapshot({...view,...patch},'synthetic')).toThrow();});
});
