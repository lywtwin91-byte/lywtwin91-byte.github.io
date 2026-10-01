import sys
def varint(b,i):
    r=s=0
    while True:
        c=b[i];i+=1;r|=(c&0x7f)<<s;s+=7
        if c<0x80:return r,i
def parse(b):
    i=0;out=[]
    while i<len(b):
        k,i=varint(b,i);f,t=k>>3,k&7
        if t==0:v,i=varint(b,i)
        elif t==1:v=b[i:i+8];i+=8
        elif t==5:v=b[i:i+4];i+=4
        elif t==2:
            l,i=varint(b,i);v=b[i:i+l];i+=l
        else:raise ValueError(t)
        out.append((f,t,v))
    return out
def dump(b,d=0,maxd=6):
    for f,t,v in parse(b):
        if t==2:
            try:
                if d<maxd and len(v)>0:
                    sub=parse(v); 
                    s=None
                    try:
                        s=v.decode()
                        if all(c.isprintable() for c in s): print('  '*d+f'{f}: "{s}"');continue
                    except: pass
                    print('  '*d+f'{f}: {{');dump(v,d+1);print('  '*d+'}');continue
            except Exception: pass
            print('  '*d+f'{f}: {v[:80]!r}')
        else: print('  '*d+f'{f}: {v}')
if __name__=='__main__': dump(open(sys.argv[1],'rb').read())
