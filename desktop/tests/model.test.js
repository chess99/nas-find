import {test} from 'node:test';
import assert from 'node:assert/strict';
import {LatestQuery,localPath,moveSelection} from '../src/model.js';
test('mapped path is used only when its share matches',()=>{
  const c={share:'\\\\nas\\Disk1',drive:'Z:',prefer_drive:true};
  assert.equal(localPath(c,'资料/a.txt','\\\\NAS\\Disk1'),'Z:\\资料\\a.txt');
  assert.equal(localPath(c,'资料/a.txt','\\\\other\\share'),'\\\\nas\\Disk1\\资料\\a.txt');
  assert.equal(moveSelection(0,-1,4),0);assert.equal(moveSelection(3,10,4),3);assert.equal(moveSelection(0,1,0),-1);
});
test('typing coalesces pending work and drops stale responses',async()=>{
  const sent=[],rendered=[],pending=[];
  const query=new LatestQuery(args=>{sent.push(args);return new Promise(resolve=>pending.push(resolve));},data=>rendered.push(data),assert.fail);
  const run=query.request('old');query.request('middle');query.request('latest');
  assert.deepEqual(sent,['old']);pending.shift()('old response');await new Promise(setImmediate);
  assert.deepEqual(sent,['old','latest']);assert.deepEqual(rendered,[]);
  pending.shift()('latest response');await run;assert.deepEqual(rendered,['latest response']);
});
test('clear or disconnect invalidates even an in-flight result',async()=>{
  let resolve;const rendered=[];const query=new LatestQuery(()=>new Promise(r=>resolve=r),x=>rendered.push(x),assert.fail);
  const run=query.request('x');query.invalidate();resolve('stale');await run;assert.deepEqual(rendered,[]);
});
