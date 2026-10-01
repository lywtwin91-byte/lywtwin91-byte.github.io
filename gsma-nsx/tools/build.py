"""Pixel CarrierSettings(.pb) -> 국가/통신사별 IMS 설정 현황 CSV/JSON.
usage: python3 build.py <product>/etc/CarrierSettings <outdir> [build_id]"""
import sys,os,glob,csv,json,collections,datetime
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from cs import load,load_multi,carrier_list
import pycountry

D,OUT=sys.argv[1].rstrip('/')+'/',sys.argv[2]
BUILD=sys.argv[3] if len(sys.argv)>3 else ''
# AOSP CarrierConfigManager 기본값 (default.pb 에 없을 때)
FW={'carrier_volte_available_bool':False,'carrier_wfc_ims_available_bool':False,
    'vonr_enabled_bool':False,'carrier_vt_available_bool':False,
    'carrier_cross_sim_ims_available_bool':False,'imssms.sms_over_ims_supported_bool':True,
    'carrier_nr_availabilities_int_array':[1,2],'satellite_attach_supported_bool':False,
    'ims.enable_presence_publish_bool':False,'carrier_ims_gba_required_bool':False}
DEF=load(D+'default.pb')['configs']
def eff(c,k):
    if k in c:return c[k],'carrier'
    if k in DEF:return DEF[k],'default.pb'
    return FW.get(k),'framework'
IMS_PREFIX=('ims.','imsvoice.','imssms.','imswfc.','imsemergency.','iwlan.','imsserviceentitlement.')
def country(iso):
    if iso=='zz' or len(iso)!=2:return 'ZZ','(International/MVNO/Test)'
    c=pycountry.countries.get(alpha_2=iso.upper())
    return iso.upper(),(c.name if c else iso.upper())

cl=carrier_list(D+'carrier_list.pb')
rows=[]
for p in sorted(glob.glob(D+'*.pb')):
    n=os.path.basename(p)[:-3]
    if n in('carrier_list','others','default','no_sim','1global_bootstrap'):continue
    r=load(p);c=r['configs']
    iso,cname=country(n.rsplit('_',1)[-1])
    ids=cl.get(n,[])
    mcc=sorted({i['mccmnc'] for i in ids if 'mccmnc' in i})
    mvno=sorted({f"{i['mccmnc']}{str(k).upper()}={v}" for i in ids for k,v in i.items() if k!='mccmnc'})
    volte,src=eff(c,'carrier_volte_available_bool')
    nr,_=eff(c,'carrier_nr_availabilities_int_array')
    ims_keys=[k for k in c if k.startswith(IMS_PREFIX)]
    apn_ims=any(5 in a['types'] or a.get('apn','').lower()=='ims' for a in r['apns'])
    sat=any(k.startswith('satellite') or 'satellite' in k for k in c)
    ent=c.get('imsserviceentitlement.entitlement_server_url_string','')
    rows.append({
      'carrier':n,'country_iso':iso,'country':cname,'mcc_mnc':' '.join(mcc),'mvno_match':' '.join(mvno),
      'config_version':r['version'],
      'config_updated':datetime.datetime.utcfromtimestamp(r['updated']).date().isoformat() if r['updated'] else '',
      'volte':volte,'volte_src':src,
      'vowifi':eff(c,'carrier_wfc_ims_available_bool')[0],
      'vonr':eff(c,'vonr_enabled_bool')[0],
      'nr_sa':2 in (nr or []),
      'vilte':eff(c,'carrier_vt_available_bool')[0],
      'cross_sim_calling':eff(c,'carrier_cross_sim_ims_available_bool')[0],
      'sms_over_ims':eff(c,'imssms.sms_over_ims_supported_bool')[0],
      'rcs_presence':eff(c,'ims.enable_presence_publish_bool')[0],
      'ts43_entitlement':bool(ent),'entitlement_url':ent,
      'gba_required':eff(c,'carrier_ims_gba_required_bool')[0],
      'volte_provisioning_required':c.get('carrier_volte_provisioning_required_bool',False),
      'satellite':sat,
      'ims_apn':apn_ims,
      'ims_param_keys':len(ims_keys),
      'total_keys':len(c),
    })
# others.pb: 소규모 통신사(MCCMNC 키) - 국가는 MCC로 역매핑
mcc2iso=collections.Counter()
for r in rows:
    for m in r['mcc_mnc'].split():
        if r['country_iso']!='ZZ':mcc2iso[(m[:3],r['country_iso'])]+=1
mccmap={k:v.upper() for k,v in json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)),'mcc_iso.json'))).items()}
for (m,iso),k in mcc2iso.most_common():mccmap[m]=iso  # 실제 파일 국가 우선
others=[]
for r in load_multi(D+'others.pb'):
    nm=r['name']
    iso=country(nm.rsplit('_',1)[-1])[0] if not nm[:3].isdigit() else mccmap.get(nm[:3],'??')
    others.append({'id':nm,'country_iso':iso,'apns':len(r['apns']),
                   'ims_apn':any(5 in a['types'] for a in r['apns']),
                   'volte':r['configs'].get('carrier_volte_available_bool',False),'keys':len(r['configs'])})

os.makedirs(OUT,exist_ok=True)
with open(OUT+'/carriers.csv','w',newline='',encoding='utf-8-sig') as f:
    w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
agg=collections.OrderedDict()
for r in sorted(rows,key=lambda r:r['country']):
    a=agg.setdefault(r['country_iso'],{'country_iso':r['country_iso'],'country':r['country'],'carriers':0,
       'volte':0,'vowifi':0,'vonr':0,'nr_sa':0,'vilte':0,'ts43_entitlement':0,'satellite':0,'ims_detailed':0,
       'others_pb_entries':0,'mcc':set()})
    a['carriers']+=1
    for k in('volte','vowifi','vonr','nr_sa','vilte','ts43_entitlement','satellite'):a[k]+=bool(r[k])
    a['ims_detailed']+=r['ims_param_keys']>=5
    a['mcc'].update(m[:3] for m in r['mcc_mnc'].split())
for o in others:
    iso=o['country_iso']
    if iso not in agg:
        agg[iso]={'country_iso':iso,'country':country(iso.lower())[1] if iso!='??' else '(unknown)','carriers':0,
          'volte':0,'vowifi':0,'vonr':0,'nr_sa':0,'vilte':0,'ts43_entitlement':0,'satellite':0,'ims_detailed':0,
          'others_pb_entries':0,'mcc':set()}
    agg[iso]['others_pb_entries']+=1
    if o['id'][:3].isdigit():agg[iso]['mcc'].add(o['id'][:3])
countries=[]
for a in sorted(agg.values(),key=lambda a:(-a['carriers'],a['country'])):
    a['mcc']=' '.join(sorted(a['mcc']));countries.append(a)
with open(OUT+'/countries.csv','w',newline='',encoding='utf-8-sig') as f:
    w=csv.DictWriter(f,fieldnames=list(countries[0]));w.writeheader();w.writerows(countries)
json.dump({'build':BUILD,'carriers':rows,'countries':countries,'others':others},
          open(OUT+'/data.json','w',encoding='utf-8'),ensure_ascii=False)
print(len(rows),'carriers,',len(countries),'countries,',len(others),'others.pb entries')
