import sys,os,glob,struct
sys.path.insert(0,os.path.dirname(__file__))
from pbraw import parse,varint
def s(v):return v.decode('utf-8','replace')
def ints(v,t):
    if t==0:return [v]
    out=[];i=0
    while i<len(v):x,i=varint(v,i);out.append(x)
    return out
def conf(b):
    key=None;val=None
    for f,t,v in parse(b):
        if f==1:key=s(v)
        elif f==2:val=s(v)
        elif f in(3,4):val=v-(1<<64) if v>=1<<63 else v
        elif f==5:val=bool(v)
        elif f==6:val=val or [];val=[s(x) for ff,tt,x in parse(v) if ff==1]
        elif f==7:val=[x for ff,tt,y in parse(v) if ff==1 for x in ints(y,tt)]
        elif f==8:val=configs(v)
        elif f==9:val=struct.unpack('<d',v)[0]
    return key,val
def configs(b):
    d={}
    for f,t,v in parse(b):
        if f==2:k,val=conf(v);d[k]=val
    return d
def load(p):
    return load_bytes(open(p,'rb').read())
def load_multi(p):
    from pbraw import parse as P
    return [load_bytes(v) for f,t,v in P(open(p,'rb').read()) if f==2]
def load_bytes(b):
    r={'updated':None,'name':None,'version':None,'apns':[],'configs':{},'other':[]}
    for f,t,v in parse(b):
        if f==1:r['name']=s(v)
        elif f==2:r['version']=v
        elif f==3:
            for ff,tt,a in parse(v):
                if ff==2:
                    ap={};types=[]
                    for g,gt,x in parse(a):
                        if g==1:ap['name']=s(x)
                        elif g==2:ap['apn']=s(x)
                        elif g==3:types+=ints(x,gt)
                    ap['types']=types;r['apns'].append(ap)
        elif f==4:r['configs']=configs(v)
        elif f==8:r['updated']=parse(v)[0][2] if v else None
        else:r['other'].append(f)
    return r
def carrier_list(p):
    m={}
    for f,t,v in parse(open(p,'rb').read()):
        if f!=1:continue
        name=None;ids=[]
        for g,gt,x in parse(v):
            if g==1:name=s(x)
            elif g==2:
                cid={}
                for h,ht,y in parse(x):
                    cid[{1:'mccmnc',2:'spn',3:'imsi',4:'gid1',5:'gid2'}.get(h,h)]=s(y)
                ids.append(cid)
        m.setdefault(name,[]).extend(ids)
    return m
