import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {encodeConnection,canShare,CONNECTION_PREFIX,connectionMatrix} from '../src/connection-share.js';

test('mobile fixture and desktop agree on Unicode credentials and portable fields',()=>{
  const fixture=JSON.parse(readFileSync(new URL('../../android/app/src/androidTest/assets/connection-share.json',import.meta.url),'utf8'));
  assert.equal(encodeConnection(fixture),fixture.uri);
  const decoded=JSON.parse(Buffer.from(fixture.uri.slice(CONNECTION_PREFIX.length),'base64url').toString('utf8'));
  assert.deepEqual(decoded,{server:fixture.server,password:fixture.password});
  const extended={...fixture,share:'\\\\nas\\files',mount_path:'/Volumes/files',session:'never-share-this'};
  assert.equal(encodeConnection(extended),fixture.uri);
  assert.ok(connectionMatrix(fixture).getModuleCount()>20);
});

test('only saved and unchanged connections can be shared',()=>{
  const state={connected:true,remembered:true,savedServer:'http://nas.example.internal:8765',server:'http://nas.example.internal:8765/',password:''};
  assert.equal(canShare(state),true);
  for(const change of [{connected:false},{remembered:false},{savedServer:''},{server:'http://other.example.internal'},{password:'unsaved'}]){
    assert.equal(canShare({...state,...change}),false);
  }
});

test('empty secrets and excessive QR density are rejected',()=>{
  assert.throws(()=>encodeConnection({server:'http://nas',password:''}),/没有可分享/);
  assert.throws(()=>encodeConnection({server:'http://nas',password:'x'.repeat(2000)}),/过长/);
});
