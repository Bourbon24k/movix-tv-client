import json,subprocess,os,zipfile
from pathlib import Path
root=Path(__file__).resolve().parent
os.chdir(root)
sdk=Path(os.environ['ANDROID_HOME']);bt=sdk/'build-tools/34.0.0';jar=sdk/'platforms/android-34/android.jar';build=root/'build'
import shutil
if build.exists():shutil.rmtree(build)
for p in ['gen','classes','dex']: (build/p).mkdir(parents=True)
def run(args):subprocess.run([str(x) for x in args],check=True)
deps=json.loads((root/'vendor/dependencies.json').read_text())
# dependency paths were recorded relative to the task workspace
workspace=root.parent.parent
jars=[];compiled=[];packages=[]
for n,d in enumerate(deps):
 for j in d['jars']:
  p=workspace/j
  if p.exists():jars.append(p)
 if d['res'] and any((workspace/d['res']).rglob('*.xml')):
  out=build/('libres'+str(n)+'.zip');run([bt/'aapt2','compile','--dir',workspace/d['res'],'-o',out]);compiled.append(out)
 if d['package']:packages.append(d['package'])
run([bt/'aapt2','compile','--dir','res','-o',build/'res.zip'])
args=[bt/'aapt2','link','--auto-add-overlay','-o',build/'unsigned.apk','-I',jar,'--manifest','AndroidManifest.xml','-A','assets','--java',build/'gen','--min-sdk-version','24','--target-sdk-version','34','--version-code','16','--version-name','2.2.0-movix','--extra-packages',':'.join(packages)]+compiled+[build/'res.zip']
run(args)
cp=':'.join(str(x) for x in [jar]+jars)
sources=list(Path('src').rglob('*.java'))+list((build/'gen').rglob('*.java'))
run(['javac','-encoding','UTF-8','--release','11','-classpath',cp,'-d',build/'classes']+sources)
run([bt/'d8','--release','--min-api','24','--lib',jar,'--output',build/'dex']+list((build/'classes').rglob('*.class'))+jars)
with zipfile.ZipFile(build/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
 for p in (build/'dex').glob('*.dex'):z.write(p,p.name)
run([bt/'zipalign','-f','4',build/'unsigned.apk',build/'aligned.apk'])
run([bt/'apksigner','sign','--ks','debug.keystore','--ks-pass','pass:android','--key-pass','pass:android','--out',build/'tvos-home.apk',build/'aligned.apk'])
print('Built',build/'tvos-home.apk')
