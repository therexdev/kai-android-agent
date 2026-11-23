'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const rig=require('../app/src/main/assets/characters/motion.js');
let checks=0;
function check(name,fn){fn();checks++;process.stdout.write('PASS '+name+'\n');}
check('normalized finite skin weights on every character',()=>{
 for(const s of Object.values(rig.specs))for(let y=0;y<=s.h;y+=13)for(let x=0;x<=s.w;x+=17){const w=rig.weights(s,x,y);assert(w.every(v=>Number.isFinite(v)&&v>=0&&v<=1));assert(w.reduce((a,b)=>a+b,0)<=1.00001);}
});
check('neutral skin exactly preserves all artwork positions',()=>{
 for(const s of Object.values(rig.specs)){const p={head:0,left:0,elbow:0,wrist:0,right:0,rightElbow:0,rightWrist:0};const bones=rig.bones(s,p),root=rig.matrix(0,0,0);
  for(let y=0;y<=s.h;y+=29)for(let x=0;x<=s.w;x+=31){const q=rig.deform(s,x,y,rig.weights(s,x,y),bones,root);assert(Math.abs(q[0]-x)<1e-8&&Math.abs(q[1]-y)<1e-8);}}
});
check('wave raises the paw above the elbow using two joints',()=>{
 for(const s of Object.values(rig.specs)){const p=rig.pose(s,1.2,'idle',0,1.2,false),b=rig.bones(s,p);const elbow=rig.apply(b[1],...s.elbow),wrist=rig.apply(b[2],...s.wrist);assert(p.left>=45&&p.elbow>=30);assert(wrist[1]<elbow[1]-30);assert(wrist[1]<s.shoulder[1]-40);}
});
check('wrist waves while the raised upper arm stays still',()=>{
 for(const s of Object.values(rig.specs)){let wrist=[],upper=[];for(let t=1;t<1.8;t+=.02){const p=rig.pose(s,t,'idle',0,t,false);wrist.push(p.wrist);upper.push(p.left);}assert(Math.max(...wrist)-Math.min(...wrist)>15);assert(Math.max(...upper)-Math.min(...upper)<.001);}
});
check('greeting starts and settles with zero arm rotation',()=>{
 for(const s of Object.values(rig.specs))for(const t of [0,2.85,3.2]){const p=rig.pose(s,t,'idle',0,t,false);assert(Math.abs(p.left)<1e-8&&Math.abs(p.elbow)<1e-8&&Math.abs(p.wrist)<1e-8);}
});
check('continuous neck deformation has no cut line or jump',()=>{
 for(const s of Object.values(rig.specs)){const p=rig.pose(s,1.3,'listening',0,1.3,false),b=rig.bones(s,p),root=rig.matrix(s.w/2,s.feet,p.body,0,p.lift);
  for(let y=s.head[0];y<s.head[1];y+=.5){const x=s.w/2,a=rig.deform(s,x,y,rig.weights(s,x,y),b,root),c=rig.deform(s,x,y+.5,rig.weights(s,x,y+.5),b,root);assert(Math.hypot(c[0]-a[0],c[1]-a[1])<.9);}}
});
check('waving never moves the face or planted feet with an arm bone',()=>{
 for(const s of Object.values(rig.specs)){
  const w=rig.weights(s,...s.eye);assert(w.slice(1).every(v=>v===0));
  for(let x=0;x<=s.w;x+=7){const f=rig.weights(s,x,s.feet);assert(f.slice(1).every(v=>v===0));}
 }
});
check('each SVG uses one uncut body texture and one face transform',()=>{
 for(const name of Object.keys(rig.specs)){const svg=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/characters',name,name+'.svg'),'utf8');assert.equal((svg.match(/<image /g)||[]).length,1);assert(svg.includes('id="mesh-still"'));assert(svg.includes('id="mesh-face"'));assert(!svg.includes('mask="url('));}
});
process.stdout.write('OK ('+checks+' rig checks)\n');
