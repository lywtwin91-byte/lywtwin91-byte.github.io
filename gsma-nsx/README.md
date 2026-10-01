# Pixel 베타 펌웨어 국가/통신사별 IMS 설정 현황 (GSMA NSX 간접 분석)

- 대상: `cubs_beta-ota-cp41.260831.011-f191b637.zip`
  (`google/cubs_beta/cubs:17/CP41.260831.011/16448103`, 보안 패치 2026-09-01)
- 결과 페이지: [`index.html`](index.html) · 데이터: [`data/countries.csv`](data/countries.csv), [`data/carriers.csv`](data/carriers.csv), [`data/data.json`](data/data.json)

## 요약

| 항목 | 값 |
|---|---|
| 통신사 전용 프로필(`*.pb`) | 639개 / 131개 국가 (+ `others.pb` 소규모 통신사 680개, 전체 215개 국가) |
| VoLTE 활성 | 통신사 601/639, 국가 129 |
| VoWiFi 활성 | 통신사 326, 국가 67 |
| VoNR 활성 | 통신사 76, 국가 15 — US 45, CA·FR·DE 각 5, GB·SE 각 3, CH 2, AT·BE·NL·IN·SG·AU·FI 각 1 |
| TS.43 Entitlement URL | 통신사 40 — AU, CA, CH, FR, GB, JP, LI, US, ZZ |
| 위성(NTN) 설정 | 통신사 68 — AU, CA, FR, GB, JP, US, ZZ |
| 전용 프로필이 있는데 VoLTE 미활성 | **KR (skt/kt/lguplus 3사 모두)**, ZM, PK(ufone, zong), 그 밖에 MVNO/테스트/IoT 프로필 |

> 한국 3사는 SIP·IMS 파라미터 오버라이드가 15~29개 들어 있지만(SKT·LGU+는 IMS APN도 있음), `carrier_volte_available_bool` 같은 VoLTE·VoWiFi·VoNR 활성 플래그는 설정돼 있지 않습니다. 그래서 프레임워크 기본값(false)이 적용됩니다.

## NSX 관련 신호 (Google–GSMA NSX VoLTE 제휴 보도와 대조)

보도 요약: Pixel 11부터 Google이 NSX를 이용해 VoLTE를 넓게 열고, 처음에는 China Mobile·China Unicom·China Telecom·CSL(홍콩)을 활성화했습니다. 그 밖의 NSX 등록 통신사는 기본은 꺼짐이고 사용자가 설정에서 켜는 방식이라고 합니다.

펌웨어(`ro.product.model=Pixel 11`)에서 확인한 내용:

1. **중국 3사·CSL**: `cmcc_cn`·`ct_cn`·`cu_cn`(2026-08-12), `csl_hk`(2026-07-01)는 키 2~8개짜리 최소 프로필이고 `carrier_volte_available_bool=true`입니다. 보도와 일치합니다.
2. **VoLTE 기본 꺼짐·사용자 활성화 패턴**: `carrier_volte_available_bool=true`이면서 `enhanced_4g_lte_on_by_default_bool=false`인 프로필이 **113개 / 65개국**입니다. 이 중 102개가 2026-03-11·18·25에 일괄 추가됐고, 대부분 키 2~6개의 템플릿형 구성입니다. → `carriers.csv`의 `volte_user_optin` 컬럼
3. **한국 3사**는 두 패턴 어디에도 해당하지 않습니다.
4. CarrierSettings 앱에는 서버에서 통신사 설정을 내려받아 적용하는 경로(`CronetDownloaderService`, `downloadAndApplyUpdatesIfAvailable`)가 있습니다. 그래서 펌웨어에 들어 있는 내용이 실제 기기 상태의 전부는 아닐 수 있습니다.

## 주의: NSX와의 관계

펌웨어(CarrierSettings `.pb`, 텔레포니 관련 APK의 dex)를 전부 검색했지만 NSX / Network Settings Exchange를 직접 가리키는 키나 문자열은 없었습니다.
이 데이터는 NSX가 배포하는 대상인 **통신사별 IMS 설정이 펌웨어에 반영된 범위**를 보여주는 간접 지표입니다. 각 값이 어떤 경로로 들어왔는지(NSX인지 아닌지)는 구분할 수 없습니다.

## 재현 절차

```bash
curl -O https://dl.google.com/developers/android/cinnamonbun/images/ota/cubs_beta-ota-cp41.260831.011-f191b637.zip
unzip cubs_beta-ota-*.zip payload.bin
go install github.com/ssut/payload-dumper-go@latest
payload-dumper-go -p product,system_ext,modem -o img payload.bin
fsck.erofs --extract=img/product img/product.img        # erofs-utils
pip install pycountry
python3 tools/build.py img/product/etc/CarrierSettings data "<build fingerprint>"
```

- `tools/pbraw.py`: protobuf wire-format 범용 디코더(공식 proto 스키마 없이 사용)
- `tools/cs.py`: CarrierSettings / MultiCarrierSettings(`others.pb`) / CarrierList(`carrier_list.pb`) 파서
- `tools/build.py`: 실제 적용값 산출(통신사 값 → `default.pb` → AOSP 기본값 순) 후 CSV/JSON 생성
- `tools/mcc_iso.json`: MCC→ISO 국가 매핑(`others.pb` 항목의 국가 추정용, 출처: `mobile-codes` 패키지)

## 원래 계획한 방법과 다른 점

- Pixel에는 `CarrierConfig.apk`의 assets XML이나 `/product/etc/apns-conf.xml`이 없습니다. 통신사 설정과 APN은 모두 `/product/etc/CarrierSettings/*.pb`에 들어 있습니다.
- 이 기기의 모뎀은 MediaTek이라(`PIXELMODEM` 컨테이너, MCF 파일) Qualcomm MBN이 없습니다. 운영자별 MCF `MTK_OPOTA_SBPID_*` 406종, `MTK_NWOTA_SBPID_*` 51종을 확인했지만, SBPID→통신사 매핑표가 공개돼 있지 않아 국가에 연결하지 못했습니다.
