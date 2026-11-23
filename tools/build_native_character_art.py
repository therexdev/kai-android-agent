"""Rasterize the existing SVG face layers/contours for Android's native mesh view.
Requires skia-python and Pillow at build-tool time; Android has no extra dependency.
Original source textures are never overwritten.
"""
from pathlib import Path
import copy,json,re,subprocess,sys,xml.etree.ElementTree as E
from PIL import Image
import skia
ROOT=Path(__file__).resolve().parents[1];A=ROOT/'app/src/main/assets/characters';OUT=A/'native';OUT.mkdir(exist_ok=True)
N='{http://www.w3.org/2000/svg}';E.register_namespace('',N[1:-1])
specs=json.loads(subprocess.check_output(['node','-e',"console.log(JSON.stringify(require('./app/src/main/assets/characters/motion.js').specs))"],cwd=ROOT))
def dom(e):
 data=E.tostring(e);stream=skia.MemoryStream(data);return skia.SVGDOM.MakeFromStream(stream)
def svg(w,h):return E.Element(N+'svg',{'width':str(w),'height':str(h),'viewBox':f'0 0 {w} {h}'})
def polygon(d):
 p=skia.Path()
 for op,x,y in re.findall(r'([ML])([0-9.\-]+) ([0-9.\-]+)',d):
  if op=='M':p.moveTo(float(x),float(y))
  else:p.lineTo(float(x),float(y))
 p.close();return p
def save(surface,path,crop=True):
 image=Image.fromarray(surface.makeImageSnapshot().toarray(colorType=skia.ColorType.kRGBA_8888_ColorType),'RGBA')
 box=image.getbbox() if crop else (0,0,image.width,image.height)
 assert box
 image.crop(box).save(path,format='WEBP',lossless=True,method=6)
 return list(box)
manifest={}
for id,s in specs.items():
 out=OUT/id;out.mkdir(exist_ok=True);original=E.parse(A/id/(id+'.svg')).getroot();w,h=s['w'],s['h'];layers={}
 texture=skia.Image.MakeFromEncoded(skia.Data.MakeFromFileName(str(A/id/(id+'-texture.'+('webp' if id=='kai' else 'png')))))
 skin=skia.Surface(w,h);c=skin.getCanvas();outline=next(n for n in original.iter() if n.get('id') in ['texture-silhouette','kai-texture-silhouette']).find(N+'path').get('d');c.clipPath(polygon(outline),doAntiAlias=True);c.drawImageRect(texture,skia.Rect.MakeWH(w,h))
 if id=='kai':
  mask=skia.Surface(w,h);doc=svg(w,h)
  for node in original.find('.//*[@id="mesh-body-mask"]'):
   node=copy.deepcopy(node);node.set('fill','white');node.attrib.pop('stroke',None);doc.append(node)
  dom(doc).render(mask.getCanvas());c.drawImage(mask.makeImageSnapshot(),0,0,paint=skia.Paint(BlendMode=skia.BlendMode.kDstIn))
  doc=svg(w,h);defs=E.SubElement(doc,N+'defs');defs.append(copy.deepcopy(original.find('.//*[@id="kai-cape-shade"]')));doc.append(copy.deepcopy(original.find('.//*[@id="mesh-cape"]')))
  surface=skia.Surface(w,h);dom(doc).render(surface.getCanvas());layers['cape']=save(surface,out/'cape.webp')
 save(skin,out/'skin.webp',False)
 gaze=next(n for n in original.iter() if n.get('class') in ['companion-gaze','kai3d-gaze'])
 for part,classes in [('eyes',['companion-eyes','kai3d-eyes']),('attentive',['companion-eyes','kai3d-eyes']),('brows',['companion-brows','kai3d-brows']),('smile',['companion-smile','kai3d-smile']),('mouth',['companion-mouth-open','kai3d-mouth'])]:
  if part=='attentive' and id!='kai':continue
  doc=svg(w,h);defs=E.SubElement(doc,N+'defs')
  for node in original.find(N+'defs'):
   if node.tag in [N+'linearGradient',N+'radialGradient']:defs.append(copy.deepcopy(node))
  parent=E.SubElement(doc,N+'g',{k:v for k,v in gaze.attrib.items() if k!='filter'})
  node=copy.deepcopy(next(n for n in gaze if n.get('class') in classes));node.set('opacity','1');parent.append(node)
  for e in node.iter():
   e.attrib.pop('filter',None)
   if e.get('class')=='kai3d-happy':e.set('opacity','0' if part=='attentive' else '1')
   if e.get('class')=='kai3d-attentive':e.set('opacity','1' if part=='attentive' else '0')
  surface=skia.Surface(w,h);dom(doc).render(surface.getCanvas());layers[part]=save(surface,out/(part+'.webp'))
 manifest[id]={'spec':s,'layers':layers}
 print(id,layers,flush=True)
(OUT/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
