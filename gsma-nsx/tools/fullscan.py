import os,sys,zipfile,io,re,subprocess,tempfile,shutil,collections
S=sys.argv[1];ROOT=S+'/ota/img';FS=S+'/eu/fsck/fsck.erofs'
PAT=re.compile(rb'(?i)(network[ _\-]?settings?[ _\-]?exchange|gsma\.com|[a-z0-9.\-]*gsma[a-z0-9.\-]*\.(?:com|org|net|io)|(?<![a-z0-9])nsx(?![a-z0-9]))')
OBF=re.compile(rb'L[\w/$]*/nsx;|/nsx;')
stats=collections.Counter();hits=collections.defaultdict(set)
def scan_bytes(name,b):
    stats['scanned_bytes']+=len(b)
    for m in PAT.finditer(b):
        s=b[max(0,m.start()-40):m.end()+40]
        if OBF.search(b[max(0,m.start()-30):m.end()+2]):stats['obf_classname']+=1;continue
        hits[name].add(re.sub(rb'[^\x20-\x7e]',b'.',s).decode())
def scan_zip(name,data,depth=0):
    try:z=zipfile.ZipFile(io.BytesIO(data))
    except Exception:scan_bytes(name,data);return
    stats['zips']+=1
    for i in z.infolist():
        n=i.filename
        try:d=z.read(i)
        except Exception:stats['zip_read_err']+=1;continue
        if n.endswith(('.dex','.so','.xml','.json','.pb','.txt','.properties','.arsc','.bin','.conf','.cfg')) or n.startswith('assets/') :
            stats['entries']+=1;scan_bytes(f'{name}!{n}',d)
        if n.endswith(('.apk','.jar','.zip')) and depth<3:scan_zip(f'{name}!{n}',d,depth+1)
        if n=='apex_payload.img':scan_img(f'{name}!{n}',d)
def scan_img(name,d):
    t=tempfile.mkdtemp(dir=S+'/full');p=t+'/p.img';open(p,'wb').write(d);o=t+'/x';os.mkdir(o)
    r=subprocess.run([FS,'--extract='+o,p],capture_output=True)
    if r.returncode!=0 or not os.listdir(o):subprocess.run(['debugfs','-R','rdump / '+o,p],capture_output=True)
    stats['apex']+=1;walk(o,name+'!');shutil.rmtree(t)
def walk(root,prefix=''):
    for dp,dn,fn in os.walk(root):
        for f in fn:
            p=os.path.join(dp,f)
            if os.path.islink(p) or not os.path.isfile(p):continue
            rel=prefix+os.path.relpath(p,root)
            try:d=open(p,'rb').read()
            except Exception:continue
            stats['files']+=1
            if f.endswith(('.apk','.jar','.apex','.capex','.zip')):
                if f.endswith('.capex'):
                    try:
                        z=zipfile.ZipFile(io.BytesIO(d));inner=[i for i in z.namelist() if i.endswith('original_apex')]
                        if inner:scan_zip(rel+'!original_apex',z.read(inner[0]));continue
                    except Exception:pass
                scan_zip(rel,d)
            elif f.endswith(('.gz',)):
                import gzip
                try:dd=gzip.decompress(d)
                except Exception:dd=d
                scan_zip(rel,dd) if dd[:2]==b'PK' else scan_bytes(rel,dd)
            elif f.endswith('.ext4') or f.endswith('.img'):stats['skipped_img']+=1
            else:scan_bytes(rel,d)
for part in ['system','system_ext','product','vendor','vendor_dlkm','system_dlkm']:walk(ROOT+'/'+part,part+'/')
# modem raw image
for f in os.listdir(ROOT):
    if f.endswith('.ext4'):scan_bytes('modem/'+f,open(ROOT+'/'+f,'rb').read())
print(dict(stats))
for k in sorted(hits):
    for s in sorted(hits[k])[:6]:print(k,'|',s)
