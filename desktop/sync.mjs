import {mkdir,copyFile} from 'node:fs/promises';
await mkdir(new URL('./src/shared/',import.meta.url),{recursive:true});
for(const name of ['explorer.js','selection.js','explorer.css'])await copyFile(new URL('../nasfind/static/'+name,import.meta.url),new URL('./src/shared/'+name,import.meta.url));
