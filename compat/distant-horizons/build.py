"""Build each patched DH target sequentially using the fork's Gradle wrapper."""
import argparse
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--fork', type=Path, default=root/'third_party/distant-horizons-tellus')
parser.add_argument('--output', type=Path, default=root/'output/dh-3.3')
parser.add_argument('--offline', action='store_true')
args = parser.parse_args()
out = args.output.resolve()
fork = args.fork.resolve()
for folder in ('logs', 'jars', 'test-results'):
    (out/folder).mkdir(parents=True, exist_ok=True)
assert 'mod_version=3.3.1-tellus.1' in (fork/'gradle.properties').read_text(), 'Apply the Tellus patches first'
for mc_ver, mc, loader in [('26.2.0','26.2','neoforge'), ('26.3.0','26.3','neoforge'),
        ('1.21.11','1.21.11','neoforge'), ('1.21.1','1.21.1','neoforge'), ('1.20.1','1.20.1','forge')]:
    print('Building', mc, flush=True)
    # The Manifold version definitions and common output are shared by targets.
    tasks = [':common:clean', ':fabric:clean', f':{loader}:clean']
    if mc == '26.2':
        tasks += [':api:test', ':core:test']
    tasks += [':fabric:remapJar', f':{loader}:remapJar']
    command = ['./gradlew', '--no-daemon', f'-PmcVer={mc_ver}', '--max-workers=2', '--console=plain']
    if args.offline:
        command.append('--offline')
    with (out/'logs'/f'dh-final-{mc}.log').open('w') as log:
        subprocess.run(command+tasks, cwd=fork, stdout=log, stderr=subprocess.STDOUT, check=True)
    for target in ('fabric', loader):
        source = fork/target/'build/libs'/f'DistantHorizons-{target}-3.3.1-tellus.1-{mc}.jar'
        shutil.copy2(source, out/'jars'/source.name)
    if mc == '26.2':
        for project in ('api', 'core'):
            shutil.copytree(fork/'coreSubProjects'/project/'build/test-results/test', out/'test-results'/project, dirs_exist_ok=True)
    print('Staged', mc, flush=True)
