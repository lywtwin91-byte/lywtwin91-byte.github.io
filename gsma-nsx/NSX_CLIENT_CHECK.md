# 펌웨어에 NSX 직접 연동 코드가 있는가? — 검증 근거

대상: `cubs_beta` CP41.260831.011 (Pixel 11 베타) OTA

## 1. 전체 바이너리 문자열 스캔 (`tools/fullscan.py`)

- 범위: system, system_ext, product, vendor, vendor_dlkm, system_dlkm 파티션 전체 + 모뎀 이미지
  - 파일 7,127개, APK/JAR/ZIP 607개(최대 3단계 중첩), APEX 51개(내부 이미지까지 풀어서), 내부 항목 201,703개(dex, so, xml, json, pb, arsc, assets), 총 **8.87GB**
  - erofs-utils 1.9.4로 추출(1.7.1은 일부 파일을 손상시켜 사용하지 않음)
- 검색 패턴: `network settings exchange`(구분자 변형 포함), `gsma.com` 및 `*gsma*.{com,org,net,io}` 도메인, 앞뒤가 영숫자가 아닌 `nsx`

| 패턴 | 결과 |
|---|---|
| Network Settings Exchange (모든 변형) | **0건** |
| GSMA 도메인 | 10건, 모두 무관: eSIM SM-DP+(`lpa.ds.gsma.com`, `SMDP.GSMA.COM` — EuiccGoogle), RCS XML 네임스페이스(`www.gsma.com/rcs` — Google Messages), Verizon eSIM(`gsma2.vzw.otgeuicc.com` — MyVerizonServices) |
| `nsx` 토큰 | 137종 문맥, 모두 무관: ARM64 기계어/압축 데이터의 우연한 바이트, ISO 639-3 언어코드 목록(`nsx-Latn-AO`), 난독화 리소스명(`res/NsX.xml`), 난독화 클래스명(`Lm35/nsx;` 등), ML 모델 바이너리 |

## 2. 실제 원격 설정 경로 (디컴파일, `CarrierSettings.apk`)

통신사 설정이 기기 밖에서 바뀌는 경로는 아래 둘뿐이고, 둘 다 Google 인프라를 거칩니다.

1. **Phenotype 서버 플래그 오버라이드**: `CarrierSettingsOverride__carrier_settings_overrides`. GMS Phenotype으로 전달되는 Google `CarrierSettings` proto이며, 통신사 이름(canonical name)별로 펌웨어 값 위에 덮어씁니다.
2. **설정 파일 다운로드**(`UpdateService.downloadSettings`):
   - URL은 펌웨어에 없습니다. Phenotype 플래그 `CarrierSettings__update_config`의 `carrier_settings_url`(컴파일된 기본값은 비어 있음)을 형식 문자열로 쓰고, 여기에 `제품명 / 통신사명 또는 "others" / 버전`을 채웁니다(`String.format(url, productAlias, carrier|others, version)`).
   - 받는 데이터는 Google `CarrierSettings` .pb 형식이며 Cronet으로 다운로드합니다.

## 결론과 한계

- 펌웨어에는 NSX를 식별할 수 있는 주소, 문자열, 데이터 형식이 없습니다. 기기가 쓰는 설정 형식은 처음부터 끝까지 Google `CarrierSettings` proto입니다. 따라서 NSX 데이터는 Google 서버에서 이 형식으로 변환된 뒤 위 1·2번 경로로 들어온다고 보는 것이 펌웨어 증거와 일치합니다.
- **증명할 수 없는 것**:
  - 2번의 다운로드 주소는 서버가 정해 주므로, 그 서버가 어디인지는 펌웨어로 확정할 수 없습니다(형식으로 보면 Google 배포 서버).
  - GMS Core가 출시 후 내려받는 동적 모듈과 Phenotype 플래그 값은 펌웨어에 없습니다.
  - 정적 분석이라 실제 네트워크 트래픽은 확인하지 않았습니다.
