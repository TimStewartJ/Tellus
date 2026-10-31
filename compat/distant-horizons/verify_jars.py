"""Verify the loader-specific Tellus DH release; provide matching API and Iris jars."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
from zipfile import ZipFile
from check_iris_linkage import ClassFile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('release', type=Path, help='Directory containing jars/')
parser.add_argument('--api', type=Path, required=True, help='Official DH API 7.1.0 jar')
parser.add_argument('--iris', type=Path, action='append', required=True)
args = parser.parse_args()
version = '3.3.1-tellus.1'
records = []
shared_hashes = {}
api_prefix = 'com/seibel/distanthorizons/api/'
core_prefix = 'com/seibel/distanthorizons/core/'
script_dir = Path(__file__).resolve().parent
with ZipFile(args.api) as official:
    for mc in ('1.20.1', '1.21.1', '1.21.11', '26.2', '26.3'):
        java = 17 if mc == '1.20.1' else 25 if mc.startswith('26.') else 21
        for loader in ('fabric', 'forge' if mc == '1.20.1' else 'neoforge'):
            name = f'DistantHorizons-{loader}-{version}-{mc}.jar'
            path = args.release/'jars'/name
            with ZipFile(path) as jar:
                assert jar.testzip() is None, name
                manifest = jar.read('META-INF/MANIFEST.MF').decode()
                assert f'Implementation-Version: {version}' in manifest, name
                # Forge 1.20.1 scans this directory whenever the manifest enables multi-release loading.
                assert 'multi-release: true' not in manifest.lower() or any(
                    n.startswith('META-INF/versions/') for n in jar.namelist()
                ), f'{name}: Multi-Release is enabled but META-INF/versions is missing'
                gl = 'com/seibel/distanthorizons/common/render/openGl/terrain/GlBlockTextureAtlas.class'
                assert struct.unpack('>H', jar.read(gl)[6:8])[0] == java + 44, name
                preserved = 0
                for entry in official.namelist():
                    if entry.startswith(api_prefix) and entry.endswith('.class'):
                        a, b = ClassFile(official.read(entry)), ClassFile(jar.read(entry))
                        assert {m for m in a.methods if a.member_access[m] & 5} <= b.methods, (name, entry)
                        assert {f for f in a.fields if a.member_access[f] & 5} <= b.fields, (name, entry)
                        preserved += 1
                gate = ClassFile(jar.read(api_prefix+'interfaces/override/worldGenerator/IDhApiWorldGenerator.class'))
                assert ('getGenerationAvailability', '(IIIB)B') in gate.methods
                assert struct.unpack('>H', gate.data[6:8])[0] == 52
                render_proxy = core_prefix+'render/DhApiRenderProxy'
                assert (9, render_proxy, 'getDhBlockRatioAtlasTextureGlId', 'I') in set(ClassFile(jar.read(gl)).dh_refs())
                mod_info = jar.read('com/seibel/distanthorizons/coreapi/ModInfo.class')
                assert version.encode() in mod_info
                assert core_prefix+'generation/queues/WorldGenerationTaskSelector.class' in jar.namelist()
                lang_name = 'assets/distanthorizons/lang/en_us.json'
                language = json.loads(jar.read(lang_name))
                assert len(language) >= 480 and any(k.startswith('distanthorizons.config.') for k in language)
                # All loaders must contain identical translations and core/API bytecode.
                # This also guards against the resource separation behind the 3.3.1 hotfix.
                shared = [n for n in jar.namelist() if n.startswith((api_prefix, core_prefix, 'com/seibel/distanthorizons/coreapi/')) and n.endswith('.class')]
                for entry in shared + [lang_name]:
                    digest = hashlib.sha256(jar.read(entry)).hexdigest()
                    assert shared_hashes.setdefault(entry, digest) == digest, (name, entry)
                if loader == 'fabric':
                    metadata = json.loads(jar.read('fabric.mod.json'))
                    assert metadata['version'] == version
                    assert mc in str(metadata['depends']['minecraft'])
                else:
                    meta = next(n for n in ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml') if n in jar.namelist())
                    metadata = jar.read(meta).decode()
                    assert f'version = "{version}"' in metadata and mc in metadata
            iris_results = []
            for iris in args.iris:
                result = subprocess.run([sys.executable, str(script_dir/'check_iris_linkage.py'), str(iris), str(path)], capture_output=True, text=True)
                assert result.returncode == 0, result.stdout + result.stderr
                iris_results.append({'file': iris.name, 'references': int(result.stdout.split()[0])})
            records.append({'file': name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'bytes': path.stat().st_size,
                'minecraft': mc, 'loader': loader, 'java': java, 'api_classes': preserved, 'translations': len(language), 'iris': iris_results})
            print(f'PASS {name}: archive, metadata, Java {java}, {preserved} API classes, translations, Iris linkage')
(args.release/'manifest.json').write_text(json.dumps(records, indent=2)+'\n')
(args.release/'SHA256SUMS.txt').write_text(''.join(r['sha256']+'  '+r['file']+'\n' for r in records))
print('All 10 release jars verified.')
