#!/usr/bin/env python3
"""Build a separately reviewed/hash-pinned, Google-free app_process bridge DEX.
No device access, installation, credential use or APK extraction. Use official SDK/JDK tools.
"""
import argparse, hashlib, json, pathlib, re, subprocess, zipfile

def compiler_command(javac, java):
    if (javac is None)==(java is None): raise ValueError('exactly one compiler route required')
    return [str(javac)] if javac is not None else [str(java),'com.sun.tools.javac.Main']

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--android-jar',required=True,type=pathlib.Path)
    compiler=p.add_mutually_exclusive_group(required=True)
    compiler.add_argument('--javac',type=pathlib.Path)
    compiler.add_argument('--java',type=pathlib.Path,help='JDK compiler module when javac launcher is unavailable')
    p.add_argument('--d8',required=True,type=pathlib.Path)
    p.add_argument('--termux-signer-sha256',help='optional one/two reviewed comma-separated public pins; default auth-trust.properties')
    p.add_argument('--out',required=True,type=pathlib.Path)
    a=p.parse_args()
    root=pathlib.Path(__file__).resolve().parents[1]
    trust=root/'auth-trust.properties'
    if a.termux_signer_sha256 is None:
        a.termux_signer_sha256 = next((line.split('=',1)[1].strip() for line in trust.read_text().splitlines() if line.startswith('termuxSignerSha256=')), '')
    pins=a.termux_signer_sha256.split(',')
    if len(pins)>2 or len(set(pins))!=len(pins) or not all(re.fullmatch('[0-9a-f]{64}',pin) for pin in pins): p.error('expected one or two reviewed public signer pins')
    for tool in (a.android_jar,a.javac or a.java,a.d8):
        if not tool.is_file(): p.error('required SDK/JDK file unavailable')
    names=['BridgeCli','BridgeHandshake','BinderBootstrap','StandalonePackages','PackageIdentityReader','AuthPeerVerifier','AuthPeerPolicy','AuthProtocol']
    sources=[root/'app/src/main/java/com/shaterguy/termuxwatchdog'/f'{name}.java' for name in names]
    out=a.out.resolve()
    if out.exists() and any(out.iterdir()): p.error('output directory must be empty; never reuse stale compiled classes')
    out.mkdir(parents=True,exist_ok=True)
    classes=out/'classes'; classes.mkdir(exist_ok=True)
    generated=out/'generated/BuildConfig.java'; generated.parent.mkdir(exist_ok=True)
    generated.write_text('package com.shaterguy.termuxwatchdog; final class BuildConfig { static final String TERMUX_SIGNER_SHA256 = "'+a.termux_signer_sha256+'"; }\n')
    subprocess.run(compiler_command(a.javac,a.java)+['-source','17','-target','17','-classpath',str(a.android_jar),'-d',str(classes),*map(str,sources),str(generated)],check=True)
    jar=out/'watchdog-bridge.jar'
    with zipfile.ZipFile(jar,'w',compression=zipfile.ZIP_STORED) as archive:
        for f in sorted(classes.rglob('*.class')):
            archive.writestr(zipfile.ZipInfo(f.relative_to(classes).as_posix(),(1980,1,1,0,0,0)),f.read_bytes())
    dex=out/'dex'; dex.mkdir(exist_ok=True)
    subprocess.run([str(a.d8),'--release','--min-api','26','--lib',str(a.android_jar),'--output',str(dex),str(jar)],check=True)
    output=dex/'classes.dex'
    manifest={'schema':'watchdog-bridge-build-v2','entrypoint':'com.shaterguy.termuxwatchdog.BridgeCli','token_adapter_implemented':True,'token_transport_enabled':True,'google_sdk_included':False,'supported_operations':['probe','status','access','refresh'],'mutual_hello_required':True,
              'termux_signer_sha256':a.termux_signer_sha256,'dex_sha256':hashlib.sha256(output.read_bytes()).hexdigest(),
              'sources':{str(f.relative_to(root)):hashlib.sha256(f.read_bytes()).hexdigest() for f in sources+[trust]}}
    (out/'build-manifest.json').write_text(json.dumps(manifest,indent=2,sort_keys=True)+'\n')
    print(json.dumps({'ok':True,'dex_sha256':manifest['dex_sha256'],'token_adapter_implemented':True,'token_transport_enabled':True,'google_sdk_included':False,'supported_operations':['probe','status','access','refresh'],'mutual_hello_required':True}))
if __name__=='__main__': main()
