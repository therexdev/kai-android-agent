"""Build connected fallback/face SVGs from the exact existing artwork; never edit raster pixels."""
from pathlib import Path
import copy
import xml.etree.ElementTree as ET

ET.register_namespace('', 'http://www.w3.org/2000/svg')
root = Path(__file__).resolve().parents[1] / 'app/src/main/assets/characters'
ns = '{http://www.w3.org/2000/svg}'
for name in ['kai', 'nova', 'spark', 'sage']:
    path = root / name / (name + '.svg')
    old = ET.parse(path).getroot()
    if old.find('.//*[@id="mesh-face"]') is not None:
        continue
    defs = copy.deepcopy(old.find(ns + 'defs'))
    gaze = copy.deepcopy(next(n for n in old.iter() if n.get('class') in {'kai3d-gaze', 'companion-gaze'}))
    svg = ET.Element(ns + 'svg', old.attrib)
    if name == 'kai': svg.set('viewBox', '-40 20 1104 1470')
    svg.append(defs)
    width, height = (1024, 1536) if name == 'kai' else (1199, 1312)
    ET.SubElement(svg, ns + 'image', {'id':'mesh-still', 'href':name + '-texture.' + ('webp' if name == 'kai' else 'png'),
        'width':str(width),'height':str(height),'clip-path':'url(#' + ('kai-texture-silhouette' if name == 'kai' else 'texture-silhouette') + ')'})
    face = ET.SubElement(svg, ns + 'g', {'id':'mesh-face'})
    face.append(gaze)
    if name == 'kai':
        body_mask = ET.SubElement(defs, ns + 'g', {'id':'mesh-body-mask'})
        for part in ['head', 'torso', 'upper-arm-left', 'forearm-left', 'upper-arm-right', 'forearm-right',
                     'thigh-left', 'shin-left', 'thigh-right', 'shin-right']:
            part_id = 'kai-' + part + '-cutout'
            old_mask = next(n for n in old.iter() if n.get('id') == part_id)
            for node in old_mask:
                if node.tag == ns + 'path':
                    node = copy.deepcopy(node); node.set('data-part', part_id); body_mask.append(node)
        cape = copy.deepcopy(next(n for n in old.iter() if n.get('class') == 'kai3d-cape'))
        cape.set('id', 'mesh-cape'); svg.insert(1, cape)
    path.write_text(ET.tostring(svg, encoding='unicode') + '\n')
