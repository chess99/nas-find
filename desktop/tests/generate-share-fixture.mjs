// Synthetic interoperability fixture. Never use real credentials here.
import {mkdirSync,writeFileSync} from 'node:fs';
import {encodeConnection,connectionMatrix} from '../src/connection-share.js';
const config={server:'http://nas.example.internal:8765',password:'synthetic-中文-"#&+/%-password'};
const root=new URL('../../android/app/src/androidTest/assets/',import.meta.url);
mkdirSync(root,{recursive:true});
writeFileSync(new URL('connection-share.json',root),JSON.stringify({...config,uri:encodeConnection(config)},null,2)+'\n');
const gif=connectionMatrix(config).createDataURL(6,24).split(',')[1];
writeFileSync(new URL('connection-share.gif',root),Buffer.from(gif,'base64'));
