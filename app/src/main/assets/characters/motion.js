/* KAI connected 2D skin. Bundled art + trusted native state only. No network or audio capture. */
(function (host) {
  'use strict';
  var PI = Math.PI, RAD = PI / 180;
  function clamp(x, a, b) { return Math.max(a, Math.min(b, x)); }
  function smooth(a, b, x) { var t = clamp((x - a) / (b - a), 0, 1); return t * t * t * (10 + t * (-15 + 6 * t)); }
  function mix(a, b, t) { return a + (b - a) * t; }
  function rotate(x, y, degrees) { var a = degrees * RAD; return [x * Math.cos(a) - y * Math.sin(a), x * Math.sin(a) + y * Math.cos(a)]; }
  function matrix(x, y, degrees, dx, dy) {
    var a = degrees * RAD, c = Math.cos(a), s = Math.sin(a);
    return [c, s, -s, c, x - c * x + s * y + (dx || 0), y - s * x - c * y + (dy || 0)];
  }
  function multiply(a, b) { return [a[0]*b[0]+a[2]*b[1], a[1]*b[0]+a[3]*b[1], a[0]*b[2]+a[2]*b[3], a[1]*b[2]+a[3]*b[3], a[0]*b[4]+a[2]*b[5]+a[4], a[1]*b[4]+a[3]*b[5]+a[5]]; }
  function apply(m, x, y) { return [m[0]*x+m[2]*y+m[4], m[1]*x+m[3]*y+m[5]]; }
  var specs = {
    spark: {w:1199,h:1312,view:[-70,-35,1339,1392],neck:[599,764],head:[670,830],shoulder:[375,797],elbow:[273,914],wrist:[225,1005],armEdge:[[730,433],[810,390],[880,340],[950,302],[1050,265],[1120,240]],eye:[599,477],mouth:[599,647],feet:1250,vigor:1.0},
    nova: {w:1199,h:1312,view:[-70,-35,1339,1392],neck:[599,731],head:[655,800],shoulder:[464,802],elbow:[402,901],wrist:[354,984],armEdge:[[730,458],[770,490],[824,489],[910,454],[980,430],[1090,415],[1120,0]],eye:[599,444],mouth:[599,614],feet:1250,vigor:.85},
    sage: {w:1199,h:1312,view:[-70,-35,1339,1392],neck:[599,755],head:[650,830],shoulder:[364,816],elbow:[260,938],wrist:[174,1050],armEdge:[[760,390],[800,372],[820,382],[850,366],[900,351],[950,338],[1000,324],[1050,300],[1100,250],[1150,200],[1200,0]],eye:[599,462],mouth:[599,614],feet:1250,vigor:.55},
    kai: {w:1024,h:1536,view:[-40,20,1104,1470],neck:[512,680],head:[605,765],shoulder:[335,758],elbow:[260,850],wrist:[164,954],armEdge:[[680,358],[760,335],[850,300],[950,235],[1040,180],[1100,140],[1140,0]],eye:[512,410],mouth:[512,534],feet:1380,vigor:.9}
  };
  function edge(spec, y) {
    var a = spec.armEdge;
    for (var i=1;i<a.length;i++) if (y<a[i][0]) return mix(a[i-1][1],a[i][1],clamp((y-a[i-1][0])/(a[i][0]-a[i-1][0]),0,1));
    return a[a.length-1][1];
  }
  function weights(spec, x, y) {
    var head = 1-smooth(spec.head[0],spec.head[1],y), start=spec.armEdge[0][0];
    var armEnd=spec.armEdge[spec.armEdge.length-1][0];
    var arm = smooth(start,start+70,y)*(1-smooth(armEnd-40,armEnd,y));
    var left = arm*(1-smooth(edge(spec,y)-5,edge(spec,y)+5,x));
    var right = arm*smooth(spec.w-edge(spec,y)-5,spec.w-edge(spec,y)+5,x);
    var vx=spec.wrist[0]-spec.elbow[0],vy=spec.wrist[1]-spec.elbow[1],len=Math.sqrt(vx*vx+vy*vy);
    var along=((x-spec.elbow[0])*vx+(y-spec.elbow[1])*vy)/len;
    var ralong=((spec.w-x-spec.elbow[0])*vx+(y-spec.elbow[1])*vy)/len;
    var lower=smooth(-40,45,along),hand=smooth(len-24,len+24,along);
    var rlower=smooth(-40,45,ralong),rhand=smooth(len-24,len+24,ralong);
    // Arm influence replaces head/body influence only in the shoulder's own region.
    return [(1-left-right)*head,left*(1-lower),left*lower*(1-hand),left*lower*hand,right*(1-rlower),right*rlower*(1-rhand),right*rlower*rhand];
  }
  function pose(spec, t, state, since, wave, audible) {
    var v=spec.vigor, p={head:0,body:0,lift:0,left:0,elbow:0,wrist:0,right:0,rightElbow:0,rightWrist:0,gazeX:0,gazeY:0};
    p.lift=-2.7*(.5-.5*Math.cos(t*PI/2.25));
    p.head=.65*Math.sin(t*.67)+.30*Math.sin(t*1.13); p.body=.22*Math.sin(t*.71);
    if (state==='listening') { p.head=-2.5+.35*Math.sin(t*.8); p.lift-=1.6; }
    if (state==='thinking'||state==='searching'||state==='review') { p.head=2.7+.45*Math.sin(t*.65);p.gazeY=-9;p.gazeX=5; }
    if (state==='error') p.head=-2.4;
    if (state==='speaking' && audible) {
      p.head+=.7*Math.sin(t*3.2)*Math.sin(t*.9);
      var beat=Math.pow(Math.max(0,Math.sin(t*1.65)),3)*v;
      p.right=-8*beat;p.rightElbow=-13*beat;p.rightWrist=-7*beat;
    }
    if (state==='success' && t-since<1.6) { var joy=Math.sin(clamp((t-since)/1.6,0,1)*PI);p.lift-=9*v*joy*joy;p.head-=2*joy; }
    if (wave>=0 && wave<2.85) {
      var up=smooth(.18,.83,wave), down=1-smooth(1.98,2.85,wave), hold=up*down;
      var anticipate=Math.sin(clamp(wave/.34,0,1)*PI)*(1-up);
      var wag=Math.sin((wave-.82)*PI*5.2)*smooth(.82,1.02,wave)*(1-smooth(1.80,2.05,wave));
      p.left=hold*(spec===specs.sage?55:80)-5*anticipate;
      p.elbow=hold*(spec===specs.sage?38:55)+2.3*wag;
      p.wrist=hold*5+18*v*wag;
      p.head-=2.5*hold;p.body+=1.2*hold;p.lift-=3.5*v*hold;
    }
    return p;
  }
  function bones(spec, p) {
    var left=matrix(spec.shoulder[0],spec.shoulder[1],p.left);
    var fore=multiply(left,matrix(spec.elbow[0],spec.elbow[1],p.elbow));
    var hand=multiply(fore,matrix(spec.wrist[0],spec.wrist[1],p.wrist));
    var right=matrix(spec.w-spec.shoulder[0],spec.shoulder[1],p.right);
    var rfore=multiply(right,matrix(spec.w-spec.elbow[0],spec.elbow[1],p.rightElbow));
    var rhand=multiply(rfore,matrix(spec.w-spec.wrist[0],spec.wrist[1],p.rightWrist));
    return [matrix(spec.neck[0],spec.neck[1],p.head),left,fore,hand,right,rfore,rhand];
  }
  function deform(spec,x,y,w,b,root) {
    var dx=0,dy=0;
    for(var k=0;k<7;k++) { var m=b[k],s=w[k];dx+=s*((m[0]-1)*x+m[2]*y+m[4]);dy+=s*(m[1]*x+(m[3]-1)*y+m[5]); }
    x+=dx;y+=dy;
    var planted=1-smooth(spec.feet-180,spec.feet+10,y), q=apply(root,x,y);
    return [mix(x,q[0],planted),mix(y,q[1],planted)];
  }
  // The same pure skeleton math is exercised by the build's deterministic geometry tests.
  if (typeof module!=='undefined' && module.exports) { module.exports={specs:specs,weights:weights,pose:pose,bones:bones,deform:deform,matrix:matrix,apply:apply,multiply:multiply,smooth:smooth};return; }
  var document=host.document;
  if (!document || host.KaiMotion) return;
  var body=document.body, spec=specs[body.dataset.character]||specs.kai;
  var placeholder=document.getElementById('character-skin'), presented=document.createElement('canvas');presented.id='character-skin';
  var presenter=presented.getContext('2d',{alpha:true}),mounted=false,frameBytes=null,frameImage=null;
  // Keep WebGL offscreen and present its finished pixels through the ordinary canvas path.
  // Older transparent WebViews can render a valid GPU buffer yet omit its direct surface.
  var canvas=document.createElement('canvas');canvas.id='character-gpu';canvas.style.display='none';body.appendChild(canvas);
  var face=document.getElementById('mesh-face'), base=document.getElementById('mesh-still');
  var gl=null, program, buffer, indices, texture, count=0, vertexCount=0, resized=true;
  var boneData=new Float32Array(63),rootData=new Float32Array(9),boneLocation,rootLocation,cape=document.getElementById('mesh-cape');
  if(cape){
    var behind=document.createElementNS('http://www.w3.org/2000/svg','svg'),svg=face.ownerSVGElement;
    behind.setAttribute('viewBox',svg.getAttribute('viewBox'));behind.setAttribute('aria-hidden','true');
    behind.style.zIndex='0';
    var capeDefs=document.createElementNS('http://www.w3.org/2000/svg','defs');capeDefs.appendChild(svg.querySelector('#kai-cape-shade').cloneNode(true));
    behind.appendChild(capeDefs);behind.appendChild(cape);body.insertBefore(behind,placeholder);
    cape.removeAttribute('class');
  }
  var ready=false, failure='', disposed=false, pending=0,lastFrame=-1,origin=performance.now(),clock=0,state='idle',stateSince=0,waveAt=-10;
  var motion=false, suspended=true, audible=false, mouth=0, transition=0, previousPose=null,currentPose=null,frames=0;
  var eyes=document.querySelector('.companion-eyes,.kai3d-eyes'), lips=document.querySelector('.companion-mouth-open,.kai3d-mouth');
  var smile=document.querySelector('.companion-smile,.kai3d-smile'), brows=document.querySelector('.companion-brows,.kai3d-brows');
  var target=pose(spec,0,'idle',0,-1,false), lastWave=false;
  function initGL(image) {
    // Keep the completed skin available when the frame loop stops or WebView composites later.
    gl=canvas.getContext('webgl',{alpha:true,antialias:false,premultipliedAlpha:true,preserveDrawingBuffer:true,depth:false,stencil:false,powerPreference:'low-power'});
    if(!gl) return false;
    function shader(type,text) {var s=gl.createShader(type);gl.shaderSource(s,text);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error('shader');return s;}
    program=gl.createProgram();
    gl.attachShader(program,shader(gl.VERTEX_SHADER,'attribute vec2 position;attribute vec2 uv;attribute vec4 weightsA;attribute vec3 weightsB;uniform mat3 bones[7];uniform mat3 root;uniform vec2 feet;uniform vec4 view;varying vec2 tex;float ease(float a,float b,float x){float t=clamp((x-a)/(b-a),0.0,1.0);return t*t*t*(10.0+t*(-15.0+6.0*t));}void main(){vec3 p=vec3(position,1.0);float total=dot(weightsA,vec4(1.0))+dot(weightsB,vec3(1.0));vec3 q=(1.0-total)*p+weightsA.x*(bones[0]*p)+weightsA.y*(bones[1]*p)+weightsA.z*(bones[2]*p)+weightsA.w*(bones[3]*p)+weightsB.x*(bones[4]*p)+weightsB.y*(bones[5]*p)+weightsB.z*(bones[6]*p);q.xy=mix(q.xy,(root*q).xy,1.0-ease(feet.x,feet.y,q.y));tex=uv;gl_Position=vec4(2.0*(q.x-view.x)/view.z-1.0,1.0-2.0*(q.y-view.y)/view.w,0,1);}'));
    gl.attachShader(program,shader(gl.FRAGMENT_SHADER,'precision mediump float;uniform sampler2D art;varying vec2 tex;void main(){gl_FragColor=texture2D(art,tex);}'));
    gl.linkProgram(program);if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw Error('program');gl.useProgram(program);
    var cols=128, rows=144, size=(cols+1)*(rows+1),verts=new Float32Array(size*11);vertexCount=size;
    for(var j=0;j<=rows;j++)for(var i=0;i<=cols;i++) {var n=j*(cols+1)+i,x=spec.w*i/cols,y=spec.h*j/rows;verts.set([x,y,i/cols,j/rows],n*11);verts.set(weights(spec,x,y),n*11+4);}
    var ids=new Uint16Array(cols*rows*6),at=0;
    for(var r=0;r<rows;r++)for(var c=0;c<cols;c++){var v=r*(cols+1)+c;ids.set([v,v+1,v+cols+1,v+1,v+cols+2,v+cols+1],at);at+=6;}
    buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buffer);gl.bufferData(gl.ARRAY_BUFFER,verts,gl.STATIC_DRAW);
    indices=gl.createBuffer();gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,indices);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,ids,gl.STATIC_DRAW);count=ids.length;
    [['position',2,0],['uv',2,8],['weightsA',4,16],['weightsB',3,32]].forEach(function(a){var loc=gl.getAttribLocation(program,a[0]);gl.enableVertexAttribArray(loc);gl.vertexAttribPointer(loc,a[1],gl.FLOAT,false,44,a[2]);});
    boneLocation=gl.getUniformLocation(program,'bones[0]');rootLocation=gl.getUniformLocation(program,'root');gl.uniform2f(gl.getUniformLocation(program,'feet'),spec.feet-180,spec.feet+10);
    var art=document.createElement('canvas');art.width=image.naturalWidth;art.height=image.naturalHeight;
    var ctx=art.getContext('2d');ctx.scale(art.width/spec.w,art.height/spec.h);
    var silhouette=document.querySelector('#texture-silhouette path,#kai-texture-silhouette path');
    if(silhouette)ctx.clip(new Path2D(silhouette.getAttribute('d')));
    ctx.drawImage(image,0,0,spec.w,spec.h);
    var parts=document.querySelectorAll('#mesh-body-mask path');
    if(parts.length){
      var mask=document.createElement('canvas');mask.width=art.width;mask.height=art.height;var mc=mask.getContext('2d');mc.scale(art.width/spec.w,art.height/spec.h);mc.fillStyle='#fff';
      for(var z=0;z<parts.length;z++){
        var part=new Path2D(parts[z].getAttribute('d'));mc.fill(part);
        if(parts[z].getAttribute('data-part')==='kai-head-cutout'){mc.globalCompositeOperation='destination-out';mc.lineWidth=8;mc.stroke(part);mc.globalCompositeOperation='source-over';}
      }
      ctx.save();ctx.setTransform(1,0,0,1,0,0);ctx.globalCompositeOperation='destination-in';ctx.drawImage(mask,0,0);ctx.restore();
    }
    texture=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,texture);gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL,true);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,art);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
    gl.enable(gl.BLEND);gl.blendFunc(gl.ONE,gl.ONE_MINUS_SRC_ALPHA);gl.clearColor(0,0,0,0);
    if(gl.getError()!==gl.NO_ERROR)throw Error('texture');return true;
  }
  function fit() {
    var layout=mounted?presented:placeholder,w=layout.clientWidth,h=layout.clientHeight,ratio=Math.min(host.devicePixelRatio||1,1.5,900/Math.max(w,h));
    var pw=Math.max(1,Math.round(w*ratio)),ph=Math.max(1,Math.round(h*ratio));
    if(canvas.width!==pw||canvas.height!==ph){canvas.width=pw;canvas.height=ph;}
    if(!frameImage||presented.width!==pw||presented.height!==ph){presented.width=pw;presented.height=ph;frameBytes=new Uint8Array(pw*ph*4);frameImage=presenter.createImageData(pw,ph);}
    gl.viewport(0,0,pw,ph);
    var v=spec.view.slice(),scale=Math.min(w/v[2],h/v[3]);
    var vw=w/scale,vh=h/scale;gl.uniform4f(gl.getUniformLocation(program,'view'),v[0]-(vw-v[2])/2,v[1]-(vh-v[3])/2,vw,vh);
    resized=false;
  }
  function draw(now) {
    if(disposed)return;
    clock=(now-origin)/1000;
    var dt=lastFrame<0?0:clamp((now-lastFrame)/1000,0,.08);lastFrame=now;
    var p=pose(spec,motion?clock:0,state,stateSince,motion?clock-waveAt:-1,audible&&motion);
    if(!motion) {p.head=state==='listening'?-2:state==='thinking'?2:0;p.lift=0;p.body=0;}
    var blend=motion?smooth(0,.40,clock-transition):1;
    if(previousPose)for(var key in p)p[key]=mix(previousPose[key],p[key],blend);
    currentPose=p;
    var root=matrix(spec.w/2,spec.feet,p.body,0,p.lift), b=bones(spec,p),head=multiply(root,b[0]);
    face.setAttribute('transform','matrix('+head.join(' ')+')');
    if(cape)cape.setAttribute('transform','matrix('+root.join(' ')+')');
    // Actual playback gates the mouth. This is expressive motion, not phoneme lip sync.
    mouth=motion?mix(mouth,audible?1:0,1-Math.exp(-dt/(audible ? .07 : .11))):0;
    var syllable=.25+.75*Math.abs(Math.sin(clock*11.8)*Math.sin(clock*7.3+.8));
    if(lips){lips.style.opacity=String(mouth);lips.style.transformOrigin=spec.mouth[0]+'px '+spec.mouth[1]+'px';lips.style.transform='scale('+(.92+.08*syllable)+','+(.24+.76*syllable)+')';}
    if(smile)smile.style.opacity=String(1-mouth);
    var bt=(clock+({kai:.7,nova:1.4,spark:0,sage:2.1}[body.dataset.character]||0))%5.7;
    var blink=motion?1-.94*Math.pow(Math.max(0,1-Math.abs(bt-3.7)/.13),2):1;
    if(eyes){eyes.style.transformOrigin=spec.eye[0]+'px '+spec.eye[1]+'px';eyes.style.transform='translate('+p.gazeX+'px,'+p.gazeY+'px) scaleY('+blink+')';}
    if(brows)brows.style.transform='translateY('+(!motion&&lastWave?-4:-2*Math.max(0,1-Math.abs(clock-waveAt-1.2)/1.2))+'px)';
    if(ready&&gl){
      if(resized)fit();
      for(var i=0;i<7;i++){var m=b[i];boneData.set([m[0],m[1],0,m[2],m[3],0,m[4],m[5],1],i*9);}
      rootData.set([root[0],root[1],0,root[2],root[3],0,root[4],root[5],1]);
      gl.uniformMatrix3fv(boneLocation,false,boneData);gl.uniformMatrix3fv(rootLocation,false,rootData);
      gl.clear(gl.COLOR_BUFFER_BIT);gl.drawElements(gl.TRIANGLES,count,gl.UNSIGNED_SHORT,0);
      // Transfer actual pixels instead of a GPU mailbox. Some old WebViews report a valid
      // buffer but silently omit shared GPU surfaces from their transparent native view.
      gl.readPixels(0,0,canvas.width,canvas.height,gl.RGBA,gl.UNSIGNED_BYTE,frameBytes);
      var stride=canvas.width*4,dest=frameImage.data;
      for(var row=0;row<canvas.height;row++)dest.set(frameBytes.subarray((canvas.height-row-1)*stride,(canvas.height-row)*stride),row*stride);
      for(var pixel=0;pixel<dest.length;pixel+=4){var alpha=dest[pixel+3];if(alpha>0&&alpha<255){var straight=255/alpha;dest[pixel]*=straight;dest[pixel+1]*=straight;dest[pixel+2]*=straight;}}
      presenter.putImageData(frameImage,0,0);
      if(!mounted){presented.style.cssText=placeholder.style.cssText;placeholder.parentNode.replaceChild(presented,placeholder);mounted=true;}
    } else {
      // Whole-image fallback stays connected even on devices without a working GPU context.
      base.setAttribute('transform','matrix('+root.join(' ')+')');face.setAttribute('transform','matrix('+root.join(' ')+')');
    }
    frames++;
  }
  function tick(now) {pending=0;if(disposed||suspended)return;if(lastFrame<0||now-lastFrame>=32)draw(now);pending=host.requestAnimationFrame(tick);}
  function update() {
    var now=performance.now(),t=(now-origin)/1000,next=body.dataset.state||'idle';
    if(next!==state){previousPose=currentPose;transition=t;state=next;stateSince=t;}
    var nextWave=body.classList.contains('waving');if(nextWave&&!lastWave){waveAt=t;previousPose=currentPose;transition=t;}lastWave=nextWave;
    motion=!body.classList.contains('motion-off');suspended=body.classList.contains('suspended');audible=body.dataset.audible==='true';
    if(pending)host.cancelAnimationFrame(pending);pending=0;
    if(!suspended){draw(now);if(motion)pending=host.requestAnimationFrame(tick);}
  }
  function fallback(reason) {failure=typeof reason==='string'?reason:'texture-load';ready=false;presented.style.display='none';base.style.display='';body.dataset.renderer='connected-still';update();}
  canvas.addEventListener('webglcontextlost',function(e){e.preventDefault();gl=null;fallback('context-lost');});
  host.addEventListener('resize',function(){resized=true;if(!suspended)draw(performance.now());});
  host.KaiMotion={update:update,dispose:function(){disposed=true;if(pending)host.cancelAnimationFrame(pending);if(gl){gl.deleteTexture(texture);gl.deleteBuffer(buffer);gl.deleteBuffer(indices);gl.deleteProgram(program);}gl=null;},inspect:function(){return {ready:ready,renderer:body.dataset.renderer,failure:failure,frames:frames,motion:motion,suspended:suspended,mouth:mouth,state:state,pose:currentPose,wave:clock-waveAt,vertices:vertexCount};}};
  var image=new Image();
  image.onload=function(){if(disposed)return;try{ready=initGL(image);if(ready){body.dataset.renderer='connected-mesh';base.style.display='none';update();}else fallback('context-unavailable');}catch(e){fallback(e.message||'initialization');}};
  image.onerror=fallback;image.src=base.getAttribute('href');update();
})(typeof window!=='undefined'?window:this);
