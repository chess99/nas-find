import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Selection} from '../src/shared/selection.js';
test('four million rows select all as a constant-size state',()=>{
  const s=new Selection();s.selectAll();assert.equal(s.count(4000000),4000000);assert.deepEqual(s.payload(),{all:true,ranges:[]});
  s.choose(2345678,{ctrl:true});assert.equal(s.count(4000000),3999999);assert.equal(s.contains(2345678),false);
  s.choose(2345678,{ctrl:true});assert.deepEqual(s.payload(),{all:true,ranges:[]});
});
test('cross-page shift selection, disjoint ranges and reset',()=>{
  const s=new Selection();s.choose(490);s.choose(1100,{shift:true});assert.equal(s.count(2000),611);
  s.choose(700,{ctrl:true});assert.equal(s.count(2000),610);assert.equal(s.contains(700),false);
  s.choose(1999,{ctrl:true});assert.equal(s.count(2000),611);assert.equal(s.contains(1999),true);
  const captured=s.payload();s.clear();assert.equal(s.count(2000),0);assert.equal(captured.ranges.length,3);
});
test('all-selection keeps future rows while query is being counted',()=>{
  const s=new Selection();s.selectAll();assert.equal(s.count(500),500);assert.equal(s.count(10000),10000);
  s.choose(5,{ctrl:true});assert.equal(s.count(10000),9999);
});
